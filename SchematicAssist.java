package dev.donutvision;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Opt-in, HOLD-RMB schematic printer for normal vanilla block interactions.
 *
 * Unlike the old miss-click helper, this does NOT rely on crosshair position.
 * Every fifth client tick while RMB is held it finds a missing schematic block
 * within ordinary survival reach, selects its matching HOTBAR item, and asks
 * Minecraft to place it against a REAL adjacent face. This permits working on
 * walls and overhanging/floating structures as long as each individual new block
 * has some existing support. Vanilla survival servers normally reject arbitrary
 * unsupported mid-air placement, so this class NEVER promises such placement.
 *
 * It will not change direction-dependent block states, break existing blocks,
 * automatically move the player, spoof reach, or bypass server checks.
 */
public final class SchematicAssist {
    private static final int PLACEMENT_INTERVAL_TICKS = 5;
    private static final int RETRY_COOLDOWN_TICKS = 30;
    private static final double MAX_REACH = 4.4;
    private static final int SCAN_RANGE = 5;
    private static final int ITEM_LOOKUP_RANGE = 16; // Hotbar selection can look farther than placement range.
    private static int lastMissingHotbar;
    private static int lastMissingSupport;
    private static int lastSchematicTargets;

    private static boolean enabled;
    private static double buildRadius = 4.25;
    private static Method schematicWorldGetter;
    private static boolean resolved;
    private static long tickCount;
    private static long nextActionTick;
    private static Level lastSchematic;
    private static String status = "OFF";
    private static final Map<Long, Long> recentAttempts = new HashMap<>();

    private SchematicAssist() { }

    public static boolean enabled() { return enabled; }
    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    public static String status() { return status; }
    public static double snapRadius() { return buildRadius; } // Legacy settings compatibility.
    public static void setSnapRadius(double blocks) {
        if (Double.isFinite(blocks)) buildRadius = Math.max(1.5, Math.min(MAX_REACH, blocks));
    }

    public static void toggle() { setEnabled(!enabled); }
    public static void setEnabled(boolean value) {
        enabled = value;
        nextActionTick = 0;
        recentAttempts.clear();
        status = enabled ? "Hold RMB: auto-select + build within reach" : "OFF";
    }

    /** Consumes the normal RMB use action only while our printer is armed. */
    public static boolean handleUse(Minecraft mc) {
        return enabled && litematicaLoaded() && mc.player != null && mc.level != null
                && mc.screen == null && !Freecam.active()
                && (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.ENTITY);
    }

    /** Call from END_CLIENT_TICK: this is not tied to the crosshair or click spam. */
    public static void tick(Minecraft mc) {
        tickCount++;
        if (!enabled) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gameMode.isSpectator()) {
            status = "No playable world";
            return;
        }
        if (!litematicaLoaded()) {
            status = "Install Litematica + MaLiLib";
            return;
        }
        if (mc.screen != null || Freecam.active()) {
            // Keep the last useful diagnostic visible when the user opens our GUI.
            return;
        }
        if (!mc.options.keyUse.isDown()) {
            status = "Ready - hold RMB (F12 enables/disables)";
            return;
        }
        if (tickCount < nextActionTick) return;
        Level schematic = schematicWorld();
        if (schematic == null) {
            status = "Litematica: no active schematic placement";
            nextActionTick = tickCount + 20;
            return;
        }
        if (lastSchematic != schematic) {
            lastSchematic = schematic;
            recentAttempts.clear();
        }
        recentAttempts.entrySet().removeIf(e -> e.getValue() <= tickCount);

        Candidate candidate = findNext(mc, schematic);
        if (candidate == null) {
            // Fix v1.9.7: item selection must NOT require a placeable support face.
            // Select the material for a nearby unfinished blueprint block even if
            // the player has to move or provide a support before it can be placed.
            HotbarTarget preview = findHotbarTarget(mc, schematic);
            if (preview != null) {
                int current = mc.player.getInventory().getSelectedSlot();
                if (current != preview.hotbarSlot()) {
                    mc.player.getInventory().setSelectedSlot(preview.hotbarSlot());
                    status = "Auto-selected slot " + (preview.hotbarSlot() + 1)
                            + " (move closer / add support)";
                } else if (lastMissingSupport > 0) {
                    status = "Selected slot " + (current + 1) + "; no reachable support";
                } else {
                    status = "Selected slot " + (current + 1) + "; move closer to build";
                }
            } else if (lastMissingHotbar > 0) {
                status = "Missing matching hotbar block (slots 1-9)";
            } else if (lastSchematicTargets > 0) {
                status = "Blueprint block nearby, but not placeable here";
            } else {
                status = "No unfinished schematic block within 16 blocks";
            }
            nextActionTick = tickCount + 10;
            return;
        }

        if (mc.player.getInventory().getSelectedSlot() != candidate.hotbarSlot()) {
            mc.player.getInventory().setSelectedSlot(candidate.hotbarSlot());
            status = "Auto-selected hotbar slot " + (candidate.hotbarSlot() + 1);
            nextActionTick = tickCount + 1; // Allow the normal carried-item sync a tick.
            return;
        }

        // Let the server decide if the placement is legal. Never modify world state locally.
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, candidate.hit());
        recentAttempts.put(candidate.target().asLong(), tickCount + RETRY_COOLDOWN_TICKS);
        nextActionTick = tickCount + PLACEMENT_INTERVAL_TICKS;
        if (result.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
            status = "Trying " + candidate.target().toShortString() + " (server verifies)";
        } else {
            status = "Placement rejected / needs support";
        }
    }

    private static Candidate findNext(Minecraft mc, Level schematic) {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos base = BlockPos.containing(eye);
        Candidate best = null;
        double bestRank = Double.MAX_VALUE;
        double reachSqr = MAX_REACH * MAX_REACH;
        double scanSqr = buildRadius * buildRadius;
        lastMissingHotbar = 0;
        lastMissingSupport = 0;
        lastSchematicTargets = 0;

        for (int dy = -SCAN_RANGE; dy <= SCAN_RANGE; dy++) {
            for (int dx = -SCAN_RANGE; dx <= SCAN_RANGE; dx++) {
                for (int dz = -SCAN_RANGE; dz <= SCAN_RANGE; dz++) {
                    BlockPos target = base.offset(dx, dy, dz);
                    Vec3 center = Vec3.atCenterOf(target);
                    double distSq = center.distanceToSqr(eye);
                    if (distSq > scanSqr || distSq > reachSqr) continue;
                    if (recentAttempts.containsKey(target.asLong())) continue;
                    if (!mc.level.hasChunkAt(target) || !mc.level.getBlockState(target).isAir()) continue;
                    if (mc.player.getBoundingBox().intersects(new AABB(target))) continue;

                    BlockState desired = schematic.getBlockState(target);
                    if (desired.isAir()) continue;
                    lastSchematicTargets++;
                    // A property-bearing block (logs, stairs, slabs, etc) can still
                    // be selected; rotation and final state need separate handling.
                    Item wanted = desired.getBlock().asItem();
                    int slot = hotbarSlot(mc, wanted);
                    if (slot < 0) { lastMissingHotbar++; continue; }
                    BlockHitResult hit = reachableSupport(mc, eye, target);
                    if (hit == null) { lastMissingSupport++; continue; }

                    double rank = target.getY() * 1000.0 + distSq;
                    if (best == null || rank < bestRank) {
                        bestRank = rank;
                        best = new Candidate(target, slot, hit);
                    }
                }
            }
        }
        return best;
    }

    /** Inventory picking independently of server placement range and support checks. */
    private static HotbarTarget findHotbarTarget(Minecraft mc, Level schematic) {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos base = BlockPos.containing(eye);
        HotbarTarget best = null;
        double bestDist = Double.MAX_VALUE;
        int missing = lastMissingHotbar;
        int found = lastSchematicTargets;
        for (int dy = -ITEM_LOOKUP_RANGE; dy <= ITEM_LOOKUP_RANGE; dy++) {
            for (int dx = -ITEM_LOOKUP_RANGE; dx <= ITEM_LOOKUP_RANGE; dx++) {
                for (int dz = -ITEM_LOOKUP_RANGE; dz <= ITEM_LOOKUP_RANGE; dz++) {
                    BlockPos target = base.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(target) || !mc.level.getBlockState(target).isAir()) continue;
                    BlockState desired = schematic.getBlockState(target);
                    if (desired.isAir()) continue;
                    found++;
                    int slot = hotbarSlot(mc, desired.getBlock().asItem());
                    if (slot < 0) { missing++; continue; }
                    double dist = Vec3.atCenterOf(target).distanceToSqr(eye);
                    if (best == null || dist < bestDist) {
                        bestDist = dist;
                        best = new HotbarTarget(target, slot);
                    }
                }
            }
        }
        lastMissingHotbar = missing;
        lastSchematicTargets = found;
        return best;
    }

    private static int hotbarSlot(Minecraft mc, Item wanted) {
        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);
        if (!held.isEmpty() && held.getItem() == wanted) return selected;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == wanted) return slot;
        }
        return -1;
    }

    private static BlockHitResult reachableSupport(Minecraft mc, Vec3 eye, BlockPos target) {
        for (Direction face : new Direction[]{Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            BlockPos support = target.relative(face.getOpposite());
            if (!mc.level.hasChunkAt(support)) continue;
            BlockState supportingState = mc.level.getBlockState(support);
            // Don't click container blocks or interactive block entities.
            if (supportingState.isAir() || mc.level.getBlockEntity(support) != null) continue;

            Vec3 click = new Vec3(target.getX() + 0.5 - 0.5 * face.getStepX(),
                    target.getY() + 0.5 - 0.5 * face.getStepY(),
                    target.getZ() + 0.5 - 0.5 * face.getStepZ());
            if (eye.distanceToSqr(click) > MAX_REACH * MAX_REACH) continue;
            Vec3 insideSupport = click.add(-face.getStepX() * 0.02,
                    -face.getStepY() * 0.02, -face.getStepZ() * 0.02);
            BlockHitResult visible = mc.level.clip(new ClipContext(
                    eye, insideSupport, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(support)
                    || visible.getDirection() != face) continue;
            return new BlockHitResult(click, face, support, false);
        }
        return null;
    }

    private static Level schematicWorld() {
        if (!resolved) {
            resolved = true;
            for (String name : new String[]{
                    "fi.dy.masa.litematica.world.SchematicWorldHandler",
                    "litematica.world.SchematicWorldHandler"}) {
                try {
                    schematicWorldGetter = Class.forName(name).getMethod("getSchematicWorld");
                    break;
                } catch (ReflectiveOperationException ignored) { }
            }
        }
        if (schematicWorldGetter == null) return null;
        try {
            Object result = schematicWorldGetter.invoke(null);
            return result instanceof Level level ? level : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private record Candidate(BlockPos target, int hotbarSlot, BlockHitResult hit) { }
    private record HotbarTarget(BlockPos target, int hotbarSlot) { }
}
