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
 * DonutVision Schematic Printer. It reads the placed schematic's virtual world,
 * NOT Litematica Easy Place / its hotkeys or click handler.
 *
 * The player opts in using the DonutVision GUI or F12, then HOLDS RMB. On each
 * attempt we find a missing block near the player, select the correct item from
 * hotbar slots 1..9, and use a normal client interaction against a visible real
 * neighbor. The server performs its usual distance and placement checks.
 *
 * This deliberately does not spoof reach, place unsupported blocks in mid-air,
 * or edit real world blocks locally. Complex directional states may differ.
 */
public final class SchematicAssist {
    private static final double VANILLA_REACH = 4.4;
    private static final int MAX_SCAN = 5;
    private static final int HOTBAR_PREVIEW_RANGE = 10;
    private static final int PLACE_DELAY_TICKS = 4;
    private static final int RETRY_DELAY_TICKS = 30;

    private static boolean enabled;
    private static double radius = 4.25;
    private static String status = "OFF";
    private static boolean reflected;
    private static Method worldGetter;
    private static String reflectionError = "";
    private static Level lastSchematic;
    private static long ticks;
    private static long nextAttempt;
    private static long clickUntil;
    private static final Map<Long, Long> attemptedPositions = new HashMap<>();

    private SchematicAssist() {}

    public static boolean enabled() { return enabled; }
    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }
    public static String status() { return status; }
    public static double snapRadius() { return radius; }
    public static void setSnapRadius(double value) {
        if (Double.isFinite(value)) radius = Math.max(1.5, Math.min(VANILLA_REACH, value));
    }
    public static void toggle() { setEnabled(!enabled); }
    public static void setEnabled(boolean value) {
        enabled = value;
        nextAttempt = 0;
        clickUntil = 0;
        attemptedPositions.clear();
        status = enabled ? "ON - hold RMB to build" : "OFF - normal block placing";
    }

    /**
     * Called at the head of Minecraft.startUseItem. Never swallow the RMB use
     * when the printer is disabled or no schematic world is accessible.
     * When armed and a schematic exists, the printer owns the RMB action:
     * clicking a random block won't accidentally place an incorrect item.
     */
    public static boolean handleUse(Minecraft mc) {
        if (!enabled || !litematicaLoaded() || mc.player == null || mc.level == null
                || mc.screen != null || Freecam.active() || mc.gameMode == null
                || mc.gameMode.isSpectator()) return false;
        if (schematicWorld() == null) return false;
        clickUntil = ticks + 4;
        return true;
    }

    public static void tick(Minecraft mc) {
        ticks++;
        if (!enabled) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null
                || mc.gameMode.isSpectator()) { status = "No game world"; return; }
        if (!litematicaLoaded()) { status = "Litematica is not installed"; return; }
        if (mc.screen != null || Freecam.active()) return;

        Level schematic = schematicWorld();
        if (schematic == null) {
            status = reflectionError.isEmpty()
                    ? "No Litematica schematic world"
                    : "Cannot read Litematica (see log)";
            return;
        }
        if (schematic != lastSchematic) {
            lastSchematic = schematic;
            attemptedPositions.clear();
        }
        if (!mc.options.keyUse.isDown() && ticks > clickUntil) {
            status = "ON - hold RMB to build";
            return;
        }
        if (ticks < nextAttempt) return;
        attemptedPositions.entrySet().removeIf(entry -> entry.getValue() <= ticks);

        // Placement is searched independently of where the crosshair points.
        SearchResult search = findPlaceable(mc, schematic);
        if (search.placeable() != null) {
            Candidate candidate = search.placeable();
            int current = mc.player.getInventory().getSelectedSlot();
            if (current != candidate.hotbarSlot()) {
                mc.player.getInventory().setSelectedSlot(candidate.hotbarSlot());
                status = "Hotbar -> slot " + (candidate.hotbarSlot() + 1);
                nextAttempt = ticks + 2; // allow held-item packet to synchronize
                return;
            }
            // Vanilla interaction with a real neighboring face; no reach bypass.
            InteractionResult result = mc.gameMode.useItemOn(
                    mc.player, InteractionHand.MAIN_HAND, candidate.hit());
            attemptedPositions.put(candidate.pos().asLong(), ticks + RETRY_DELAY_TICKS);
            nextAttempt = ticks + PLACE_DELAY_TICKS;
            if (result.consumesAction()) {
                mc.player.swing(InteractionHand.MAIN_HAND);
                status = "Placing: " + candidate.pos().toShortString();
            } else {
                status = "Placement refused; check support";
            }
            return;
        }

        // Previously broken: picking was entirely skipped when no placement
        // face was available. A second pass finds the closest missing item
        // regardless of available support, then updates the selected hotbar.
        HotbarTarget missing = findMissingItem(mc, schematic);
        if (missing != null) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (selected != missing.slot()) {
                mc.player.getInventory().setSelectedSlot(missing.slot());
                status = "Hotbar -> slot " + (missing.slot() + 1)
                        + " (move closer / add support)";
            } else {
                status = "Item ready in slot " + (selected + 1)
                        + "; move closer / add support";
            }
        } else if (search.missingItems()) {
            status = "Required blocks missing in hotbar 1-9";
        } else if (search.hasTargets()) {
            status = "No visible support within reach";
        } else {
            status = "No unfinished schematic near player";
        }
        nextAttempt = ticks + 10;
    }

    private static SearchResult findPlaceable(Minecraft mc, Level schematic) {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        Candidate best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        boolean hasTargets = false;
        boolean missingItems = false;
        for (int dy = -MAX_SCAN; dy <= MAX_SCAN; dy++) {
            for (int dx = -MAX_SCAN; dx <= MAX_SCAN; dx++) {
                for (int dz = -MAX_SCAN; dz <= MAX_SCAN; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(eye);
                    if (distance > radius * radius) continue;
                    if (attemptedPositions.containsKey(pos.asLong())) continue;
                    if (!mc.level.hasChunkAt(pos) || !mc.level.getBlockState(pos).isAir()) continue;
                    if (mc.player.getBoundingBox().intersects(new AABB(pos))) continue;
                    BlockState desired = schematic.getBlockState(pos);
                    if (desired.isAir()) continue;
                    hasTargets = true;
                    int slot = hotbarSlot(mc, desired.getBlock().asItem());
                    if (slot == -1) { missingItems = true; continue; }
                    BlockHitResult face = findVisibleSupport(mc, eye, pos);
                    if (face == null) continue;
                    // Start at the lowest accessible layer, then nearest position.
                    double score = pos.getY() * 1000.0 + distance;
                    if (score < bestScore) {
                        bestScore = score;
                        best = new Candidate(pos, slot, face);
                    }
                }
            }
        }
        return new SearchResult(best, hasTargets, missingItems);
    }

    private static HotbarTarget findMissingItem(Minecraft mc, Level schematic) {
        Vec3 eye = mc.player.getEyePosition();
        BlockPos origin = BlockPos.containing(eye);
        HotbarTarget best = null;
        double bestDist = Double.POSITIVE_INFINITY;
        // Limit the secondary preview scan to a small loaded area. It has no
        // effect on where server-side placements are allowed.
        for (int dy = -HOTBAR_PREVIEW_RANGE; dy <= HOTBAR_PREVIEW_RANGE; dy++) {
            for (int dx = -HOTBAR_PREVIEW_RANGE; dx <= HOTBAR_PREVIEW_RANGE; dx++) {
                for (int dz = -HOTBAR_PREVIEW_RANGE; dz <= HOTBAR_PREVIEW_RANGE; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!mc.level.hasChunkAt(pos) || !mc.level.getBlockState(pos).isAir()) continue;
                    BlockState desired = schematic.getBlockState(pos);
                    if (desired.isAir()) continue;
                    int slot = hotbarSlot(mc, desired.getBlock().asItem());
                    if (slot < 0) continue;
                    double distance = Vec3.atCenterOf(pos).distanceToSqr(eye);
                    if (distance < bestDist) {
                        bestDist = distance;
                        best = new HotbarTarget(slot);
                    }
                }
            }
        }
        return best;
    }

    private static int hotbarSlot(Minecraft mc, Item desired) {
        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack held = mc.player.getInventory().getItem(selected);
        if (!held.isEmpty() && held.getItem() == desired) return selected;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == desired) return slot;
        }
        return -1;
    }

    private static BlockHitResult findVisibleSupport(Minecraft mc, Vec3 eye, BlockPos target) {
        for (Direction side : new Direction[]{Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            BlockPos neighbor = target.relative(side.getOpposite());
            if (!mc.level.hasChunkAt(neighbor)) continue;
            BlockState existing = mc.level.getBlockState(neighbor);
            if (existing.isAir() || mc.level.getBlockEntity(neighbor) != null) continue;
            Vec3 click = new Vec3(target.getX() + 0.5 - 0.5 * side.getStepX(),
                    target.getY() + 0.5 - 0.5 * side.getStepY(),
                    target.getZ() + 0.5 - 0.5 * side.getStepZ());
            if (eye.distanceToSqr(click) > VANILLA_REACH * VANILLA_REACH) continue;
            Vec3 inside = click.add(-side.getStepX() * 0.02,
                    -side.getStepY() * 0.02, -side.getStepZ() * 0.02);
            BlockHitResult ray = mc.level.clip(new ClipContext(
                    eye, inside, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (ray.getType() != HitResult.Type.BLOCK
                    || !ray.getBlockPos().equals(neighbor)
                    || ray.getDirection() != side) continue;
            return new BlockHitResult(click, side, neighbor, false);
        }
        return null;
    }

    private static Level schematicWorld() {
        if (!reflected) {
            reflected = true;
            for (String name : new String[]{
                    "fi.dy.masa.litematica.world.SchematicWorldHandler",
                    "litematica.world.SchematicWorldHandler"}) {
                try {
                    worldGetter = Class.forName(name).getMethod("getSchematicWorld");
                    reflectionError = "";
                    break;
                } catch (ReflectiveOperationException e) {
                    reflectionError = e.getClass().getSimpleName() + " for " + name;
                }
            }
            if (worldGetter == null) {
                System.err.println("[DonutVision/Printer] " + reflectionError);
            }
        }
        if (worldGetter == null) return null;
        try {
            Object value = worldGetter.invoke(null);
            if (value instanceof Level level) return level;
            return null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            reflectionError = e.toString();
            System.err.println("[DonutVision/Printer] Failed to read schematic: " + e);
            return null;
        }
    }

    private record Candidate(BlockPos pos, int hotbarSlot, BlockHitResult hit) {}
    private record SearchResult(Candidate placeable, boolean hasTargets, boolean missingItems) {}
    private record HotbarTarget(int slot) {}
}
