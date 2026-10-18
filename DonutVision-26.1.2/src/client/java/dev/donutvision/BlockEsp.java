package dev.donutvision;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.List;

/** Renders color-coded wireframe outlines around blocks already in loaded client chunks. */
public final class BlockEsp {
    private record Target(BlockPos pos, int color) { }

    private static List<Target> matches = List.of();
    public enum Filter {
        DIAMOND("Diamond"), DEBRIS("Ancient debris"), EMERALD("Emerald"),
        GOLD("Gold"), CHESTS("Chests / barrels"), ENDER("Ender chest"),
        SHULKERS("Shulker boxes");

        private final String label;
        Filter(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final boolean[] filterEnabled = new boolean[Filter.values().length];
    static { java.util.Arrays.fill(filterEnabled, true); }
    private static int radius = 12;
    private static int mode = 0; // 0 ores, 1 containers, 2 both
    private static int ticks = 0;

    private BlockEsp() { }

    public static void install() {
        LevelRenderEvents.COLLECT_SUBMITS.register(BlockEsp::render);
    }

    public static void nextRadius() {
        setRadius(radius >= 24 ? 8 : radius + 2);
    }

    public static int radius() {
        return radius;
    }

    public static void setRadius(int blocks) {
        radius = Math.max(8, Math.min(24, (blocks / 2) * 2));
        clear();
    }

    public static void nextMode() {
        setMode((mode + 1) % 3);
    }

    public static int mode() { return mode; }

    public static void setMode(int value) {
        mode = Math.max(0, Math.min(value, 2));
        clear();
    }

    public static boolean filterEnabled(Filter filter) { return filterEnabled[filter.ordinal()]; }

    public static void setFilter(Filter filter, boolean enabled) {
        filterEnabled[filter.ordinal()] = enabled;
        clear();
    }

    public static void toggleFilter(Filter filter) {
        setFilter(filter, !filterEnabled(filter));
    }

    public static String modeLabel() {
        return switch (mode) {
            case 0 -> "Ores";
            case 1 -> "Storage blocks";
            default -> "Ores + storage";
        };
    }

    public static void clear() {
        matches = List.of();
        ticks = 0;
    }

    public static void tick(Minecraft mc, boolean enabled) {
        if (!enabled || mc.level == null || mc.player == null) return;
        if (++ticks < 15) return;
        ticks = 0;

        ClientLevel level = mc.level;
        BlockPos center = BlockPos.containing(Freecam.active() ? Freecam.position() : mc.player.position());
        int r = radius();
        int minY = Math.max(level.getMinY(), center.getY() - r);
        int maxY = Math.min(level.getMaxY() - 1, center.getY() + r);
        List<Target> found = new ArrayList<>();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        for (int y = minY; y <= maxY; y++) {
            for (int x = center.getX() - r; x <= center.getX() + r; x++) {
                for (int z = center.getZ() - r; z <= center.getZ() + r; z++) {
                    p.set(x, y, z);
                    if (!level.hasChunkAt(p)) continue;
                    BlockState state = level.getBlockState(p);
                    int c = colorFor(state.getBlock());
                    if (c != 0) {
                        found.add(new Target(p.immutable(), c));
                        if (found.size() >= 512) {
                            matches = List.copyOf(found);
                            return;
                        }
                    }
                }
            }
        }
        matches = List.copyOf(found);
    }

    private static int colorFor(Block block) {
        if (mode != 1) {
            if (filterEnabled(Filter.DEBRIS) && block == Blocks.ANCIENT_DEBRIS) return 0xFFFF8800;
            if (filterEnabled(Filter.DIAMOND) && (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)) return 0xFF00E5FF;
            if (filterEnabled(Filter.EMERALD) && (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)) return 0xFF16FF57;
            if (filterEnabled(Filter.GOLD) && (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE)) return 0xFFFFDD00;
        }
        if (mode != 0) {
            if (filterEnabled(Filter.CHESTS) && (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL)) return 0xFFFF55AA;
            if (filterEnabled(Filter.ENDER) && block == Blocks.ENDER_CHEST) return 0xFFAA55FF;
            if (filterEnabled(Filter.SHULKERS) && block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) return 0xFFFF55AA;
        }
        return 0;
    }

    private static void render(LevelRenderContext ctx) {
        if (!DonutVisionClient.espEnabled() || matches.isEmpty()) return;
        Vec3 camera = ctx.levelState().cameraRenderState.pos;
        PoseStack stack = ctx.poseStack();
        for (Target target : matches) {
            stack.pushPose();
            stack.translate(target.pos.getX() - camera.x,
                            target.pos.getY() - camera.y,
                            target.pos.getZ() - camera.z);
            // 26.1.2 has no submitShapeOutline on SubmitNodeCollector.
            // Submit line geometry using its supported custom geometry callback.
            final int outlineColor = target.color;
            ctx.submitNodeCollector().submitCustomGeometry(stack, RenderTypes.lines(),
                    (pose, vertexConsumer) -> {
                        PoseStack outlineStack = new PoseStack();
                        outlineStack.last().pose().set(pose.pose());
                        ShapeRenderer.renderShape(outlineStack, vertexConsumer,
                                Shapes.block(), 0.0, 0.0, 0.0, outlineColor, 2.0f);
                    });
            stack.popPose();
        }
    }
}
