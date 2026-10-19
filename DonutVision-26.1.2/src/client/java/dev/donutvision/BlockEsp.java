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
import java.util.HashSet;
import java.util.Set;
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
    // Larger batches help distant ESP targets appear sooner; work remains time-budgeted.
    private static final int SECTIONS_PER_TICK = 36;
    private static final long SCAN_TIME_BUDGET_NS = 3_500_000L;
    private static final int SCAN_REFRESH_TICKS = 200;
    private static final int MAX_SPAWNERS = 512;

    public enum Filter {
        DIAMOND("Diamonds"), DEBRIS("Ancient debris"), EMERALD("Emeralds"),
        GOLD("Gold"), REDSTONE("Redstone blocks"),
        CHESTS("Chests"), BARRELS("Barrels"), ENDER("Ender chests"),
        SHULKERS("Shulker boxes"), HOPPERS("Hoppers"),
        DISPENSERS("Dispensers / droppers"), FURNACES("Furnaces");
        private final String label;
        Filter(String label) { this.label = label; }
        public String label() { return label; }
    }

    private static final boolean[] filterEnabled = new boolean[Filter.values().length];
    static { java.util.Arrays.fill(filterEnabled, true); }
    private static int radius = 24;
    private static int mode = 0; // 0 ores, 1 storage, 2 ores+storage, 3 storage+redstone, 4 all
    private static List<Target> matches = List.of();
    private static final ArrayList<Target> building = new ArrayList<>();
    private static List<Target> spawnerMatches = List.of();
    private static final ArrayList<Target> spawnerBuilding = new ArrayList<>();
    private static final Set<BlockPos> knownSpawners = new HashSet<>();
    private static List<ChunkPoint> scanChunks = List.of();
    private static int chunkIndex = 0;
    private static int sectionIndex = 0;
    private static int ticksSinceScan = SCAN_REFRESH_TICKS;
    private static int ticksSincePublish = 0;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static ClientLevel scanLevel;
    private static boolean scanning = false;
    // Palette prefilter: avoids iterating 4096 voxels for most empty/irrelevant sections.
    private static final Predicate<BlockState> INTERESTING = state ->
            colorFor(state.getBlock()) != 0 || isSpawner(state.getBlock());

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
    public static void nextMode() { setMode((mode + 1) % 5); }
    public static int mode() { return mode; }
    public static void setMode(int value) { mode = Math.max(0, Math.min(value, 4)); clear(); }
    public static boolean filterEnabled(Filter filter) { return filterEnabled[filter.ordinal()]; }
    public static void setFilter(Filter filter, boolean enabled) {
        filterEnabled[filter.ordinal()] = enabled;
        clear();
    }
    public static void toggleFilter(Filter filter) { setFilter(filter, !filterEnabled(filter)); }
    public static String modeLabel() {
        return switch (mode) {
            case 0 -> "Ores";
            case 1 -> "Storage";
            case 2 -> "Ores + storage";
            case 3 -> "Storage + redstone blocks";
            default -> "All targets";
        };
    }

    public static void clear() {
        matches = List.of();
        spawnerMatches = List.of();
        building.clear();
        spawnerBuilding.clear();
        knownSpawners.clear();
        scanChunks = List.of();
        scanning = false;
        chunkIndex = 0;
        sectionIndex = 0;
        ticksSinceScan = SCAN_REFRESH_TICKS;
    }

    public static void tick(Minecraft mc, boolean blockEnabled, boolean spawnerEnabled) {
        if ((!blockEnabled && !spawnerEnabled) || mc.level == null || mc.player == null) return;
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
            if (++ticksSinceScan < SCAN_REFRESH_TICKS) return;
            begin(level, center, mc.player.blockPosition());
        }
        // Process at most a few chunk sections per tick; do not scan the entire map in one frame.
        int processed = 0;
        long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NS;
        while (scanning && processed < SECTIONS_PER_TICK && System.nanoTime() < deadline) {
            if (chunkIndex >= scanChunks.size() ||
                    (!spawnerEnabled && building.size() >= MAX_VISIBLE_TARGETS)) {
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
            // Spawners are block entities. Query them as soon as a chunk is visited,
            // without waiting for all chunk sections/ores to finish scanning.
            if (sectionIndex == 0 && spawnerEnabled) {
                scanSpawnerBlockEntities(chunk);
            }
            // Once the normal ESP drawing budget is full, still continue finding
            // spawners rather than allowing diamonds/chests to hide them.
            if (!blockEnabled || building.size() >= MAX_VISIBLE_TARGETS) {
                chunkIndex++;
                sectionIndex = 0;
                processed++;
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
                        Block block = section.getBlockState(lx, ly, lz).getBlock();
                        if (isSpawner(block)) {
                            if (spawnerEnabled) addSpawner(new BlockPos(x, y, z), block);
                        } else {
                            int color = colorFor(block);
                            if (color != 0) {
                                building.add(new Target(new BlockPos(x, y, z), color));
                                if (building.size() >= MAX_VISIBLE_TARGETS) break;
                            }
                        }
                    }
                    if (building.size() >= MAX_VISIBLE_TARGETS) break;
                }
                if (building.size() >= MAX_VISIBLE_TARGETS) break;
            }
        }
        // Show partial results during a long scan instead of forcing the player to wait.
        if (scanning && ++ticksSincePublish >= 2) {
            matches = List.copyOf(building);
            spawnerMatches = List.copyOf(spawnerBuilding);
            ticksSincePublish = 0;
        }
    }

    private static void begin(ClientLevel level, BlockPos center, BlockPos playerPos) {
        scanLevel = level;
        scanCenter = center.immutable();
        ticksSinceScan = 0;
        ticksSincePublish = 0;
        building.clear();
        spawnerBuilding.clear();
        knownSpawners.clear();
        // Keep previously found highlights visible until refreshed results arrive.
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
        spawnerMatches = List.copyOf(spawnerBuilding);
        scanning = false;
        ticksSinceScan = 0;
    }

    private static boolean isSpawner(Block block) {
        return block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER;
    }

    private static void addSpawner(BlockPos pos, Block block) {
        if (spawnerBuilding.size() >= MAX_SPAWNERS || !knownSpawners.add(pos.immutable())) return;
        int color = block == Blocks.TRIAL_SPAWNER ? 0xFFFFBD43 : 0xFFFF4A56;
        spawnerBuilding.add(new Target(pos.immutable(), color));
    }

    private static void scanSpawnerBlockEntities(LevelChunk chunk) {
        for (BlockPos pos : chunk.getBlockEntities().keySet()) {
            if (spawnerBuilding.size() >= MAX_SPAWNERS) break;
            if (radius != UNLIMITED && (
                    Math.abs(pos.getX() - scanCenter.getX()) > radius ||
                    Math.abs(pos.getY() - scanCenter.getY()) > radius ||
                    Math.abs(pos.getZ() - scanCenter.getZ()) > radius)) continue;
            Block block = chunk.getBlockState(pos).getBlock();
            if (isSpawner(block)) addSpawner(pos, block);
        }
    }

    /** Box and tracer use exactly the same ARGB colour for each target type. */
    private static int colorFor(Block block) {
        // Ore-only, ore+storage and All.
        boolean ores = mode == 0 || mode == 2 || mode == 4;
        // Storage, ore+storage, storage+redstone and All.
        boolean storage = mode == 1 || mode == 2 || mode == 3 || mode == 4;
        // Redstone is deliberately available in its own storage combination and All.
        boolean redstone = mode == 3 || mode == 4;

        if (ores) {
            if (filterEnabled(Filter.DEBRIS) && block == Blocks.ANCIENT_DEBRIS) return 0xFFFF943E;
            if (filterEnabled(Filter.DIAMOND) && (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)) return 0xFF35DEFF;
            if (filterEnabled(Filter.EMERALD) && (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)) return 0xFF4FFFA0;
            if (filterEnabled(Filter.GOLD) && (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE)) return 0xFFFFD46B;
        }
        if (redstone && filterEnabled(Filter.REDSTONE) && block == Blocks.REDSTONE_BLOCK) return 0xFFFF485F;
        if (storage) {
            if (filterEnabled(Filter.CHESTS) && block == Blocks.CHEST) return 0xFFE8CBA5; // Beige
            if (filterEnabled(Filter.CHESTS) && block == Blocks.TRAPPED_CHEST) return 0xFFE5A985; // Apricot
            if (filterEnabled(Filter.BARRELS) && block == Blocks.BARREL) return 0xFFCB9B6C; // Wood
            if (filterEnabled(Filter.ENDER) && block == Blocks.ENDER_CHEST) return 0xFFA996FF; // Violet
            if (filterEnabled(Filter.SHULKERS) && block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) return 0xFFFF8CCF; // Pink
            if (filterEnabled(Filter.HOPPERS) && block == Blocks.HOPPER) return 0xFF9AA4B2; // Grey
            if (filterEnabled(Filter.DISPENSERS) && (block == Blocks.DISPENSER || block == Blocks.DROPPER)) return 0xFF86A8C0;
            if (filterEnabled(Filter.FURNACES) && (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER)) return 0xFF80A5A2;
        }
        return 0;
    }
    static List<Target> targets() { return matches; }
    static List<Target> spawnerTargets() { return spawnerMatches; }
}
