package dev.donutvision;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Searches only chunks already sent to the client, incrementally to avoid stalls. */
public final class BlockEsp {
    record Target(BlockPos pos, int color) { }

    // Last slider position is unlimited; it still cannot see server-hidden/unloaded chunks.
    public static final int UNLIMITED = -1;
    public static final int[] RANGE_STEPS = {
            10, 12, 16, 20, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, UNLIMITED
    };
    private static final int MAX_CLIENT_CHUNK_RADIUS = 32;
    private static final int MAX_VISIBLE_TARGETS = 2048;
    private static final int SECTIONS_PER_TICK = 12;

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
    private static int radius = 24;
    private static int mode = 0; // 0 ores, 1 containers, 2 both
    private static List<Target> matches = List.of();
    private static final ArrayList<Target> building = new ArrayList<>();
    private static List<ChunkPoint> scanChunks = List.of();
    private static int chunkIndex = 0;
    private static int sectionIndex = 0;
    private static int ticksSinceScan = 100;
    private static int ticksSincePublish = 0;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static ClientLevel scanLevel;
    private static boolean scanning = false;
    private static final Predicate<BlockState> INTERESTING = state -> colorFor(state.getBlock()) != 0;

    private record ChunkPoint(int x, int z) { }
    private BlockEsp() { }

    public static int stepIndex(int value) {
        if (value == UNLIMITED) return RANGE_STEPS.length - 1;
        int index = 0;
        for (int i = 0; i < RANGE_STEPS.length - 1; i++) {
            if (value >= RANGE_STEPS[i]) index = i;
        }
        return index;
    }
    public static int stepValue(int index) {
        return RANGE_STEPS[Math.max(0, Math.min(RANGE_STEPS.length - 1, index))];
    }
    public static String rangeLabel(int value) {
        return value == UNLIMITED ? "∞ (loaded chunks)" : value + " blocks";
    }
    public static void nextRadius() { setRadius(stepValue((stepIndex(radius) + 1) % RANGE_STEPS.length)); }
    public static int radius() { return radius; }
    public static void setRadius(int blocks) {
        // Old settings values are snapped to the new range. -1 represents infinity.
        radius = blocks == UNLIMITED ? UNLIMITED : Math.max(10, Math.min(512, blocks));
        clear();
    }
    public static void nextMode() { setMode((mode + 1) % 3); }
    public static int mode() { return mode; }
    public static void setMode(int value) { mode = Math.max(0, Math.min(value, 2)); clear(); }
    public static boolean filterEnabled(Filter filter) { return filterEnabled[filter.ordinal()]; }
    public static void setFilter(Filter filter, boolean enabled) {
        filterEnabled[filter.ordinal()] = enabled;
        clear();
    }
    public static void toggleFilter(Filter filter) { setFilter(filter, !filterEnabled(filter)); }
    public static String modeLabel() {
        return switch (mode) {
            case 0 -> "Ores";
            case 1 -> "Storage blocks";
            default -> "Ores + storage";
        };
    }

    public static void clear() {
        matches = List.of();
        building.clear();
        scanChunks = List.of();
        scanning = false;
        chunkIndex = 0;
        sectionIndex = 0;
        ticksSinceScan = 100;
    }

    public static void tick(Minecraft mc, boolean enabled) {
        if (!enabled || mc.level == null || mc.player == null) return;
        ClientLevel level = mc.level;
        Vec3 scanPosition = Freecam.active() ? Freecam.position() : mc.player.position();
        BlockPos center = BlockPos.containing(scanPosition);
        // Rebuild when the camera/player shifts or a new world is loaded.
        boolean moved = Math.abs(center.getX() - scanCenter.getX()) > 12
                || Math.abs(center.getZ() - scanCenter.getZ()) > 12
                || (radius != UNLIMITED && Math.abs(center.getY() - scanCenter.getY()) > 12);
        if (scanLevel != level || moved) {
            clear();
        }
        if (!scanning) {
            if (++ticksSinceScan < 80) return;
            begin(level, center, mc.player.blockPosition());
        }
        // Process at most a few chunk sections per tick; do not scan the entire map in one frame.
        int processed = 0;
        long deadline = System.nanoTime() + 2_500_000L;
        while (scanning && processed < SECTIONS_PER_TICK && System.nanoTime() < deadline) {
            if (chunkIndex >= scanChunks.size() || building.size() >= MAX_VISIBLE_TARGETS) {
                finish();
                break;
            }
            ChunkPoint point = scanChunks.get(chunkIndex);
            LevelChunk chunk = level.getChunkSource().getChunk(point.x(), point.z(), ChunkStatus.FULL, false);
            if (chunk == null) {
                chunkIndex++;
                sectionIndex = 0;
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= sections.length) {
                chunkIndex++;
                sectionIndex = 0;
                continue;
            }
            int index = sectionIndex++;
            processed++;
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(INTERESTING)) continue;
            // Section coordinates are derived from the world, not guessed from Y=0.
            int baseY = level.getSectionYFromSectionIndex(index) * 16;
            int baseX = point.x() << 4;
            int baseZ = point.z() << 4;
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (radius != UNLIMITED && Math.abs(y - scanCenter.getY()) > radius) continue;
                for (int lx = 0; lx < 16; lx++) {
                    int x = baseX + lx;
                    if (radius != UNLIMITED && Math.abs(x - scanCenter.getX()) > radius) continue;
                    for (int lz = 0; lz < 16; lz++) {
                        int z = baseZ + lz;
                        if (radius != UNLIMITED && Math.abs(z - scanCenter.getZ()) > radius) continue;
                        int color = colorFor(section.getBlockState(lx, ly, lz).getBlock());
                        if (color != 0) {
                            building.add(new Target(new BlockPos(x, y, z), color));
                            if (building.size() >= MAX_VISIBLE_TARGETS) break;
                        }
                    }
                    if (building.size() >= MAX_VISIBLE_TARGETS) break;
                }
                if (building.size() >= MAX_VISIBLE_TARGETS) break;
            }
        }
        // Show partial results during a long scan instead of forcing the player to wait.
        if (scanning && ++ticksSincePublish >= 6) {
            matches = List.copyOf(building);
            ticksSincePublish = 0;
        }
    }

    private static void begin(ClientLevel level, BlockPos center, BlockPos playerPos) {
        scanLevel = level;
        scanCenter = center.immutable();
        ticksSinceScan = 0;
        ticksSincePublish = 0;
        building.clear();
        matches = List.of();
        chunkIndex = 0;
        sectionIndex = 0;
        int rangeChunks = radius == UNLIMITED ? MAX_CLIENT_CHUNK_RADIUS : Math.min(
                MAX_CLIENT_CHUNK_RADIUS, (radius + 15) / 16 + 1);
        // A server sends chunks around the *real* player, not around a detached Freecam.
        int pivotX = (radius == UNLIMITED ? playerPos.getX() : center.getX()) >> 4;
        int pivotZ = (radius == UNLIMITED ? playerPos.getZ() : center.getZ()) >> 4;
        ArrayList<ChunkPoint> loaded = new ArrayList<>();
        for (int cx = pivotX - rangeChunks; cx <= pivotX + rangeChunks; cx++) {
            for (int cz = pivotZ - rangeChunks; cz <= pivotZ + rangeChunks; cz++) {
                if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null) {
                    loaded.add(new ChunkPoint(cx, cz));
                }
            }
        }
        // Nearest chunks first so the box budget favours useful nearby targets.
        loaded.sort(Comparator.comparingLong(p ->
                (long)(p.x() - pivotX) * (p.x() - pivotX) +
                (long)(p.z() - pivotZ) * (p.z() - pivotZ)));
        scanChunks = loaded;
        scanning = true;
    }

    private static void finish() {
        matches = List.copyOf(building);
        scanning = false;
        ticksSinceScan = 0;
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
    static List<Target> targets() { return matches; }
}
