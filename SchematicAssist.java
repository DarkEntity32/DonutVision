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
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/**
 * A conservative, opt-in miss-click helper for Litematica schematics.
 *
 * For each real right-click, it searches near the crosshair for the nearest
 * MISSING, orientation-independent schematic block. It picks a matching item
 * ONLY from the hotbar, and uses vanilla block interaction against a real,
 * visible neighbouring block. This is not an automatic builder / printer.
 *
 * Litematica remains optional: its schematic world is accessed via reflection
 * so DonutVision does not need to compile against malilib/Litematica APIs.
 * For stairs, slabs, observers, doors etc. use Litematica's Easy Place instead:
 * those blocks have additional state properties we deliberately don't guess.
 */
public final class SchematicAssist {
    private static boolean enabled;
    private static double snapRadius = 1.75;
    private static Method schematicWorldGetter;
    private static boolean resolved;
    private static long nextPlaceNanos;

    private SchematicAssist() { }

    public static boolean enabled() { return enabled; }

    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    public static void toggle() {
        enabled = !enabled;
        nextPlaceNanos = 0L;
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        nextPlaceNanos = 0L;
    }

    public static double snapRadius() { return snapRadius; }

    public static void setSnapRadius(double blocks) {
        if (Double.isFinite(blocks)) {
            snapRadius = Math.max(0.75, Math.min(3.0, blocks));
        }
    }

    /** Called before Minecraft's normal right-click. Return true to consume it. */
    public static boolean handleUse(Minecraft mc) {
        if (!enabled || !litematicaLoaded() || mc.player == null || mc.level == null
                || mc.gameMode == null || mc.screen != null || mc.gameMode.isSpectator()
                || Freecam.active()) return false;

        // Entity interactions are left to Minecraft; we only assist building.
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) {
            return false;
        }

        Level schematic = schematicWorld();
        if (schematic == null) return false;

        // Guard repeated startUseItem() calls while right-click is held.
        long now = System.nanoTime();
        if (now < nextPlaceNanos) return true;

        Vec3 eye = mc.player.getEyePosition();
        Vec3 nearCursor = mc.hitResult instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK
                ? hit.getLocation()
                : eye.add(mc.player.getLookAngle().scale(4.0));

        Candidate best = findClosestPlaceable(mc, schematic, eye, nearCursor);
        if (best == null) {
            // In assist mode never place a random block outside the schematic.
            return true;
        }

        mc.player.getInventory().setSelectedSlot(best.hotbarSlot());
        InteractionResult result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, best.hit());
        nextPlaceNanos = now + 200_000_000L;
        if (result.consumesAction()) {
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        return true;
    }

    private static Candidate findClosestPlaceable(Minecraft mc, Level schematic, Vec3 eye, Vec3 aim) {
        int range = (int) Math.ceil(snapRadius + 0.5);
        BlockPos origin = BlockPos.containing(aim);
        Candidate best = null;
        double bestDistanceSq = snapRadius * snapRadius;

        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos target = origin.offset(dx, dy, dz);
                    Vec3 center = new Vec3(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);
                    double score = center.distanceToSqr(aim);
                    if (score > bestDistanceSq) continue;
                    if (!mc.level.hasChunkAt(target) || !mc.level.getBlockState(target).isAir()) continue;

                    BlockState desired = schematic.getBlockState(target);
                    if (desired.isAir() || !desired.getProperties().isEmpty()) continue;
                    Item requested = desired.getBlock().asItem();
                    int slot = hotbarSlot(mc, requested);
                    if (slot < 0) continue;

                    BlockHitResult hit = findReachableSupport(mc, eye, target);
                    if (hit == null) continue;

                    bestDistanceSq = score;
                    best = new Candidate(target, slot, hit);
                }
            }
        }
        return best;
    }

    private static int hotbarSlot(Minecraft mc, Item item) {
        if (item == null) return -1;
        int current = mc.player.getInventory().getSelectedSlot();
        ItemStack selected = mc.player.getInventory().getItem(current);
        if (!selected.isEmpty() && selected.getItem() == item) return current;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) return i;
        }
        return -1;
    }

    private static BlockHitResult findReachableSupport(Minecraft mc, Vec3 eye, BlockPos target) {
        for (Direction face : new Direction[]{Direction.UP, Direction.NORTH,
                Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN}) {
            BlockPos support = target.relative(face.getOpposite());
            if (!mc.level.hasChunkAt(support)) continue;
            BlockState supportingState = mc.level.getBlockState(support);
            if (supportingState.isAir() || !supportingState.getProperties().isEmpty()
                    || supportingState.getBlock().getClass() != net.minecraft.world.level.block.Block.class
                    || mc.level.getBlockEntity(support) != null) continue;

            Vec3 click = new Vec3(target.getX() + 0.5 - face.getStepX() * 0.5,
                    target.getY() + 0.5 - face.getStepY() * 0.5,
                    target.getZ() + 0.5 - face.getStepZ() * 0.5);
            if (eye.distanceToSqr(click) > 4.5 * 4.5) continue;
            BlockHitResult visibility = mc.level.clip(new ClipContext(
                    eye, click, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (visibility.getType() != HitResult.Type.BLOCK
                    || !visibility.getBlockPos().equals(support)
                    || visibility.getDirection() != face) continue;
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
                } catch (ReflectiveOperationException ignored) {
                    // The Litematica package name can differ across versions.
                }
            }
        }
        if (schematicWorldGetter == null) return null;
        try {
            Object world = schematicWorldGetter.invoke(null);
            return world instanceof Level ? (Level) world : null;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private record Candidate(BlockPos target, int hotbarSlot, BlockHitResult hit) { }
}
