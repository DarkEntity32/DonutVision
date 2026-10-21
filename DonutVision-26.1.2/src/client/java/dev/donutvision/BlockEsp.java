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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Client-chunk-only ESP scanner.
 * v1.7: a persistent section cache keeps highlights stable while moving; a small,
 * rolling near-player sampler detects freshly placed/removed blocks within a few ticks.
 * All cache and world access is on the Minecraft client thread.
 */
public final class BlockEsp {
    record Target(BlockPos pos, int color) { }

    public static final int UNLIMITED = -1;
    public static final int[] RANGE_STEPS = {
            10, 12, 16, 20, 24, 32, 48, 64, 96, 128, 192, 256, 384, 512, UNLIMITED
    };
    private static final int MAX_CLIENT_CHUNK_RADIUS = 32;
    private static final int MAX_VISIBLE_TARGETS = 2048;
    private static final int MAX_CACHED_TARGETS = 12000;
    private static final int MAX_SPAWNERS = 512;
    private static final int MAX_CRYSTALS = 1024;
    /** Pale violet for fully grown amethyst clusters. */
    public static final int CRYSTAL_COLOR = 0xFFCB85FF;
    private static final int SECTIONS_PER_TICK = 48;
    private static final long SCAN_TIME_BUDGET_NS = 2_700_000L;
    private static final int SCAN_REFRESH_TICKS = 180;
    private static final int NEAR_RADIUS = 5;
    private static final int NEAR_SIDE = NEAR_RADIUS * 2 + 1;
    private static final int NEAR_CELLS = NEAR_SIDE * NEAR_SIDE * NEAR_SIDE;
    private static final int NEAR_SAMPLES_PER_TICK = 750;
    private static final long NEAR_TIME_BUDGET_NS = 1_100_000L;

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

    private record ChunkPoint(int x, int z) { }
    private record SectionPoint(int x, int y, int z) { }
    private static final boolean[] filterEnabled = new boolean[Filter.values().length];
    static { java.util.Arrays.fill(filterEnabled, true); }

    private static int radius = 24;
    private static int mode = 0; // 0 ores, 1 storage, 2 both, 3 storage+redstone, 4 all
    private static List<Target> matches = List.of();
    private static List<Target> spawnerMatches = List.of();
    private static List<Target> crystalMatches = List.of();
    private static final Map<BlockPos, Target> cachedBlocks = new HashMap<>();
    private static final Map<BlockPos, Target> cachedSpawners = new HashMap<>();
    private static final Map<BlockPos, Target> cachedCrystals = new HashMap<>();
    private static final Map<SectionPoint, Set<BlockPos>> blockSectionIndex = new HashMap<>();
    private static final Map<SectionPoint, Set<BlockPos>> spawnerSectionIndex = new HashMap<>();
    private static final Map<SectionPoint, Set<BlockPos>> crystalSectionIndex = new HashMap<>();
    private static List<ChunkPoint> scanChunks = List.of();
    private static int[] sectionOrder = new int[0];
    private static int chunkIndex;
    private static int sectionIndex;
    private static int ticksSinceScan = SCAN_REFRESH_TICKS;
    private static int ticksSincePublish;
    private static int nearIndex;
    private static int tickCounter;
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static ClientLevel scanLevel;
    private static boolean scanning;

    private static final Predicate<BlockState> INTERESTING = state ->
            colorFor(state.getBlock()) != 0 || isSpawner(state.getBlock()) || isCrystal(state.getBlock());

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
        radius = blocks == UNLIMITED ? UNLIMITED : Math.max(10, Math.min(512, blocks));
        restartScan(); // Keep cached results while adapting the search window.
    }
    public static void nextMode() { setMode((mode + 1) % 5); }
    public static int mode() { return mode; }
    public static void setMode(int value) {
        mode = Math.max(0, Math.min(value, 4));
        clear(); // Previous results no longer match a changed filter.
    }
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

    private static void restartScan() {
        scanning = false;
        scanChunks = List.of();
        chunkIndex = 0;
        sectionIndex = 0;
        ticksSinceScan = SCAN_REFRESH_TICKS;
    }

    public static void clear() {
        restartScan();
        cachedBlocks.clear();
        cachedSpawners.clear();
        cachedCrystals.clear();
        blockSectionIndex.clear();
        spawnerSectionIndex.clear();
        crystalSectionIndex.clear();
        matches = List.of();
        spawnerMatches = List.of();
        crystalMatches = List.of();
        scanLevel = null;
        nearIndex = 0;
    }

    public static void tick(Minecraft mc, boolean blockEnabled, boolean spawnerEnabled,
                            boolean crystalEnabled) {
        if (mc.level == null || mc.player == null) {
            if (scanLevel != null) clear();
            return;
        }
        ClientLevel level = mc.level;
        if (scanLevel != null && scanLevel != level) clear();
        if (!blockEnabled && !spawnerEnabled && !crystalEnabled) return;
        scanLevel = level;
        Vec3 viewPosition = Freecam.active() ? Freecam.position() : mc.player.position();
        BlockPos center = BlockPos.containing(viewPosition);
        boolean moved = Math.abs(center.getX() - scanCenter.getX()) > 12
                || Math.abs(center.getZ() - scanCenter.getZ()) > 12
                || (radius != UNLIMITED && Math.abs(center.getY() - scanCenter.getY()) > 12);
        if (moved) restartScan(); // DO NOT clear cached highlights when walking.

        // Check nearby changes each tick, independently from the distant section scan.
        // This also detects blocks placed into a section that was scanned seconds earlier.
        scanNear(level, mc.player.blockPosition(), blockEnabled, spawnerEnabled, crystalEnabled);
        if (Freecam.active() && center.distSqr(mc.player.blockPosition()) > 36.0) {
            scanNear(level, center, blockEnabled, spawnerEnabled, crystalEnabled);
        }

        if (!scanning && ++ticksSinceScan >= SCAN_REFRESH_TICKS) {
            begin(level, center, mc.player.blockPosition());
        }
        int processed = 0;
        long deadline = System.nanoTime() + SCAN_TIME_BUDGET_NS;
        while (scanning && processed < SECTIONS_PER_TICK && System.nanoTime() < deadline) {
            if (chunkIndex >= scanChunks.size()) {
                finish();
                break;
            }
            ChunkPoint point = scanChunks.get(chunkIndex);
            LevelChunk chunk = level.getChunkSource().getChunk(point.x(), point.z(), ChunkStatus.FULL, false);
            if (chunk == null) {
                evictChunk(point.x(), point.z());
                chunkIndex++;
                sectionIndex = 0;
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= sectionOrder.length) {
                chunkIndex++;
                sectionIndex = 0;
                continue;
            }
            int index = sectionOrder[sectionIndex++];
            if (index >= sections.length) continue;
            processed++;
            LevelChunkSection section = sections[index];
            int sectionY = level.getSectionYFromSectionIndex(index);
            SectionPoint key = new SectionPoint(point.x(), sectionY, point.z());
            int baseY = sectionY << 4;
            int baseX = point.x() << 4;
            int baseZ = point.z() << 4;
            List<Target> foundBlocks = new ArrayList<>();
            List<Target> foundSpawners = new ArrayList<>();
            List<Target> foundCrystals = new ArrayList<>();

            if (!section.hasOnlyAir() && section.maybeHas(INTERESTING)) {
                for (int ly = 0; ly < 16; ly++) {
                    int y = baseY + ly;
                    if (radius != UNLIMITED && Math.abs(y - center.getY()) > radius) continue;
                    for (int lx = 0; lx < 16; lx++) {
                        int x = baseX + lx;
                        if (radius != UNLIMITED && Math.abs(x - center.getX()) > radius) continue;
                        for (int lz = 0; lz < 16; lz++) {
                            int z = baseZ + lz;
                            if (radius != UNLIMITED && Math.abs(z - center.getZ()) > radius) continue;
                            Block block = section.getBlockState(lx, ly, lz).getBlock();
                            if (isSpawner(block)) {
                                if (spawnerEnabled) foundSpawners.add(new Target(new BlockPos(x, y, z), spawnerColor(block)));
                            } else if (isCrystal(block)) {
                                if (crystalEnabled) foundCrystals.add(new Target(new BlockPos(x, y, z), CRYSTAL_COLOR));
                            } else if (blockEnabled) {
                                int color = colorFor(block);
                                if (color != 0) foundBlocks.add(new Target(new BlockPos(x, y, z), color));
                            }
                        }
                    }
                }
            }
            // Atomic per-section replacement: never hide old results while a scan is in progress.
            if (blockEnabled) replaceSection(key, foundBlocks, cachedBlocks, blockSectionIndex, MAX_CACHED_TARGETS);
            if (spawnerEnabled) replaceSection(key, foundSpawners, cachedSpawners, spawnerSectionIndex, MAX_SPAWNERS * 3);
            if (crystalEnabled) replaceSection(key, foundCrystals, cachedCrystals, crystalSectionIndex, MAX_CRYSTALS * 3);
        }
        if (++ticksSincePublish >= 2) {
            tickCounter++;
            if (tickCounter % 40 == 0) pruneUnloaded(level);
            publish(center, blockEnabled, spawnerEnabled, crystalEnabled);
            ticksSincePublish = 0;
        }
    }

    private static void scanNear(ClientLevel level, BlockPos center, boolean blocks, boolean spawners,
                                 boolean crystals) {
        long deadline = System.nanoTime() + NEAR_TIME_BUDGET_NS;
        int processed = 0;
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        while (processed < NEAR_SAMPLES_PER_TICK && System.nanoTime() < deadline) {
            int index = nearIndex++ % NEAR_CELLS;
            processed++;
            int dx = (index % NEAR_SIDE) - NEAR_RADIUS;
            int dy = ((index / NEAR_SIDE) % NEAR_SIDE) - NEAR_RADIUS;
            int dz = (index / (NEAR_SIDE * NEAR_SIDE)) - NEAR_RADIUS;
            BlockPos pos = center.offset(dx, dy, dz);
            int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
            if (cx != lastX || cz != lastZ) {
                chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
                lastX = cx;
                lastZ = cz;
            }
            if (chunk == null || pos.getY() < level.getMinY() || pos.getY() >= level.getMaxY()) continue;
            Block block = chunk.getBlockState(pos).getBlock();
            if (blocks) updateSingle(pos, colorFor(block), cachedBlocks, blockSectionIndex, MAX_CACHED_TARGETS);
            if (spawners) updateSingle(pos, isSpawner(block) ? spawnerColor(block) : 0,
                    cachedSpawners, spawnerSectionIndex, MAX_SPAWNERS * 3);
            if (crystals) updateSingle(pos, isCrystal(block) ? CRYSTAL_COLOR : 0,
                    cachedCrystals, crystalSectionIndex, MAX_CRYSTALS * 3);
        }
    }

    private static void updateSingle(BlockPos pos, int color,
                                     Map<BlockPos, Target> cache,
                                     Map<SectionPoint, Set<BlockPos>> index,
                                     int maximum) {
        Target old = cache.get(pos);
        if (old != null && old.color() == color) return;
        SectionPoint key = new SectionPoint(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
        if (color == 0) {
            if (old != null) {
                cache.remove(pos);
                Set<BlockPos> positions = index.get(key);
                if (positions != null) {
                    positions.remove(pos);
                    if (positions.isEmpty()) index.remove(key);
                }
            }
            return;
        }
        if (old == null && cache.size() >= maximum) return;
        BlockPos fixed = pos.immutable();
        cache.put(fixed, new Target(fixed, color));
        index.computeIfAbsent(key, unused -> new HashSet<>()).add(fixed);
    }

    private static void replaceSection(SectionPoint key, List<Target> found,
                                       Map<BlockPos, Target> cache,
                                       Map<SectionPoint, Set<BlockPos>> index,
                                       int maximum) {
        Set<BlockPos> previous = index.remove(key);
        if (previous != null) for (BlockPos pos : previous) cache.remove(pos);
        if (found.isEmpty()) return;
        Set<BlockPos> current = new HashSet<>();
        for (Target target : found) {
            if (cache.size() >= maximum) break;
            BlockPos pos = target.pos();
            cache.put(pos, target);
            current.add(pos);
        }
        if (!current.isEmpty()) index.put(key, current);
    }

    private static void publish(BlockPos center, boolean blocks, boolean spawners, boolean crystals) {
        if (blocks) matches = visibleTargets(cachedBlocks, center, MAX_VISIBLE_TARGETS);
        else matches = List.of();
        if (spawners) spawnerMatches = visibleTargets(cachedSpawners, center, MAX_SPAWNERS);
        else spawnerMatches = List.of();
        if (crystals) crystalMatches = visibleTargets(cachedCrystals, center, MAX_CRYSTALS);
        else crystalMatches = List.of();
    }

    private static List<Target> visibleTargets(Map<BlockPos, Target> source, BlockPos center, int limit) {
        ArrayList<Target> visible = new ArrayList<>();
        for (Target target : source.values()) {
            BlockPos pos = target.pos();
            if (radius != UNLIMITED && (Math.abs(pos.getX() - center.getX()) > radius
                    || Math.abs(pos.getY() - center.getY()) > radius
                    || Math.abs(pos.getZ() - center.getZ()) > radius)) continue;
            visible.add(target);
        }
        if (visible.size() > limit) {
            visible.sort(Comparator.comparingDouble(t -> t.pos().distSqr(center)));
            visible.subList(limit, visible.size()).clear();
        }
        return List.copyOf(visible);
    }

    private static void begin(ClientLevel level, BlockPos center, BlockPos playerPos) {
        scanLevel = level;
        scanCenter = center.immutable();
        ticksSinceScan = 0;
        chunkIndex = sectionIndex = 0;
        int chunkRadius = radius == UNLIMITED ? MAX_CLIENT_CHUNK_RADIUS : Math.min(
                MAX_CLIENT_CHUNK_RADIUS, (radius + 15) / 16 + 1);
        int pivotX = (radius == UNLIMITED ? playerPos.getX() : center.getX()) >> 4;
        int pivotZ = (radius == UNLIMITED ? playerPos.getZ() : center.getZ()) >> 4;
        ArrayList<ChunkPoint> loaded = new ArrayList<>();
        for (int cx = pivotX - chunkRadius; cx <= pivotX + chunkRadius; cx++) {
            for (int cz = pivotZ - chunkRadius; cz <= pivotZ + chunkRadius; cz++) {
                if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) != null)
                    loaded.add(new ChunkPoint(cx, cz));
            }
        }
        loaded.sort(Comparator.comparingLong(p ->
                (long)(p.x() - pivotX) * (p.x() - pivotX) +
                (long)(p.z() - pivotZ) * (p.z() - pivotZ)));
        scanChunks = loaded;
        int count = level.getSectionsCount();
        Integer[] indices = new Integer[count];
        for (int i = 0; i < count; i++) indices[i] = i;
        java.util.Arrays.sort(indices, Comparator.comparingInt(i ->
                Math.abs(level.getSectionYFromSectionIndex(i) * 16 + 8 - center.getY())));
        sectionOrder = new int[count];
        for (int i = 0; i < count; i++) sectionOrder[i] = indices[i];
        scanning = !loaded.isEmpty();
    }

    private static void finish() {
        scanning = false;
        ticksSinceScan = 0;
    }

    private static void pruneUnloaded(ClientLevel level) {
        ArrayList<SectionPoint> stale = new ArrayList<>();
        for (SectionPoint key : blockSectionIndex.keySet()) {
            if (level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) == null)
                stale.add(key);
        }
        for (SectionPoint key : spawnerSectionIndex.keySet()) {
            if (level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) == null
                    && !stale.contains(key)) stale.add(key);
        }
        for (SectionPoint key : crystalSectionIndex.keySet()) {
            if (level.getChunkSource().getChunk(key.x(), key.z(), ChunkStatus.FULL, false) == null
                    && !stale.contains(key)) stale.add(key);
        }
        for (SectionPoint key : stale) {
            dropSection(key, cachedBlocks, blockSectionIndex);
            dropSection(key, cachedSpawners, spawnerSectionIndex);
            dropSection(key, cachedCrystals, crystalSectionIndex);
        }
    }

    private static void evictChunk(int x, int z) {
        ArrayList<SectionPoint> stale = new ArrayList<>();
        for (SectionPoint key : blockSectionIndex.keySet())
            if (key.x() == x && key.z() == z) stale.add(key);
        for (SectionPoint key : spawnerSectionIndex.keySet())
            if (key.x() == x && key.z() == z && !stale.contains(key)) stale.add(key);
        for (SectionPoint key : crystalSectionIndex.keySet())
            if (key.x() == x && key.z() == z && !stale.contains(key)) stale.add(key);
        for (SectionPoint key : stale) {
            dropSection(key, cachedBlocks, blockSectionIndex);
            dropSection(key, cachedSpawners, spawnerSectionIndex);
            dropSection(key, cachedCrystals, crystalSectionIndex);
        }
    }

    private static void dropSection(SectionPoint key, Map<BlockPos, Target> cache,
                                    Map<SectionPoint, Set<BlockPos>> index) {
        Set<BlockPos> removed = index.remove(key);
        if (removed != null) for (BlockPos pos : removed) cache.remove(pos);
    }

    private static boolean isCrystal(Block block) {
        // Full-grown amethyst clusters only; small/medium/large buds are excluded.
        return block == Blocks.AMETHYST_CLUSTER;
    }
    private static boolean isSpawner(Block block) {
        return block == Blocks.SPAWNER || block == Blocks.TRIAL_SPAWNER;
    }
    private static int spawnerColor(Block block) {
        return block == Blocks.TRIAL_SPAWNER ? 0xFFFFBD43 : 0xFFFF4A56;
    }

    /** Both box and tracer use exactly the same ARGB color for each target. */
    private static int colorFor(Block block) {
        boolean ores = mode == 0 || mode == 2 || mode == 4;
        boolean storage = mode == 1 || mode == 2 || mode == 3 || mode == 4;
        boolean redstone = mode == 3 || mode == 4;
        if (ores) {
            if (filterEnabled(Filter.DEBRIS) && block == Blocks.ANCIENT_DEBRIS) return 0xFFFF943E;
            if (filterEnabled(Filter.DIAMOND) && (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE)) return 0xFF35DEFF;
            if (filterEnabled(Filter.EMERALD) && (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE)) return 0xFF4FFFA0;
            if (filterEnabled(Filter.GOLD) && (block == Blocks.GOLD_ORE || block == Blocks.DEEPSLATE_GOLD_ORE || block == Blocks.NETHER_GOLD_ORE)) return 0xFFFFD46B;
        }
        if (redstone && filterEnabled(Filter.REDSTONE) && block == Blocks.REDSTONE_BLOCK) return 0xFFFF485F;
        if (storage) {
            if (filterEnabled(Filter.CHESTS) && block == Blocks.CHEST) return 0xFFE8CBA5;
            if (filterEnabled(Filter.CHESTS) && block == Blocks.TRAPPED_CHEST) return 0xFFE5A985;
            if (filterEnabled(Filter.BARRELS) && block == Blocks.BARREL) return 0xFFCB9B6C;
            if (filterEnabled(Filter.ENDER) && block == Blocks.ENDER_CHEST) return 0xFFA996FF;
            if (filterEnabled(Filter.SHULKERS) && block instanceof net.minecraft.world.level.block.ShulkerBoxBlock) return 0xFFFF8CCF;
            if (filterEnabled(Filter.HOPPERS) && block == Blocks.HOPPER) return 0xFF9AA4B2;
            if (filterEnabled(Filter.DISPENSERS) && (block == Blocks.DISPENSER || block == Blocks.DROPPER)) return 0xFF86A8C0;
            if (filterEnabled(Filter.FURNACES) && (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER)) return 0xFF80A5A2;
        }
        return 0;
    }

    static List<Target> targets() { return matches; }
    static List<Target> spawnerTargets() { return spawnerMatches; }
    static List<Target> crystalTargets() { return crystalMatches; }
}
