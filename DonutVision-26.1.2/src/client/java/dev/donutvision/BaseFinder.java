package dev.donutvision;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Heuristic Base Finder. Analyzes only FULL chunks already supplied to the client.
 * This is not a server-side scanner: scores are clues, not proof that a base exists.
 * Scans are time-budgeted and snapshots remain visible while a new pass runs.
 */
public final class BaseFinder {
    private record ChunkKey(int x, int z) { }
    private static final int STORAGE = 0, UTILITY = 1, BED = 2, MECHANISM = 3, SPECIAL = 4;
    private static final int CATEGORY_COUNT = 5;
    private static final int CHUNKS_RADIUS_CAP = 32;
    private static final int MAX_SECTIONS_PER_TICK = 48;
    private static final long BUDGET_NS = 1_700_000L;
    private static final int REFRESH_TICKS = 120;
    private static final int MAX_SITES = 36;
    private static final int[] RANGES = {64, 96, 128, 192, 256, 384, 512, BlockEsp.UNLIMITED};

    public record Site(BlockPos pos, int score, int storages, int utilities, int devices,
                       String label, int color) { }

    private static final class Snapshot {
        final int[] counts = new int[CATEGORY_COUNT];
        long sx, sy, sz;
        int samples;
        void add(int x, int y, int z, int type) {
            counts[type]++;
            sx += x; sy += y; sz += z;
            samples++;
        }
        void merge(Snapshot other) {
            for (int i = 0; i < CATEGORY_COUNT; i++) counts[i] += other.counts[i];
            sx += other.sx; sy += other.sy; sz += other.sz;
            samples += other.samples;
        }
        int count(int type) { return counts[type]; }
        int score() {
            int storage = Math.min(60, count(STORAGE) * 6);
            int utility = Math.min(20, count(UTILITY) * 3);
            int beds = Math.min(16, count(BED) * 4);
            int mechanism = Math.min(24, count(MECHANISM) * 4);
            int special = Math.min(40, count(SPECIAL) * 12);
            int combined = count(STORAGE) >= 2 && (count(UTILITY) > 0 || count(MECHANISM) > 0) ? 8 : 0;
            return storage + utility + beds + mechanism + special + combined;
        }
    }

    private static boolean enabled;
    private static boolean tracers = true;
    private static boolean alerts = true;
    private static int radius = 192;
    private static int minScore = 24;
    private static ClientLevel currentLevel;
    private static final Map<ChunkKey, Snapshot> snapshots = new HashMap<>();
    private static final Set<ChunkKey> alertedChunks = new HashSet<>();
    private static List<Site> sites = List.of();
    private static List<ChunkKey> scanChunks = List.of();
    private static int chunkIndex, sectionIndex, sinceRefresh = REFRESH_TICKS;
    private static int[] order = new int[0];
    private static boolean scanning;
    private static Snapshot partial = new Snapshot();
    private static BlockPos scanCenter = BlockPos.ZERO;
    private static long lastAlertMillis;

    private BaseFinder() { }

    public static boolean enabled() { return enabled; }
    public static void setEnabled(boolean value) {
        if (enabled != value) {
            enabled = value;
            if (enabled) restart();
            else sites = List.of();
        }
    }
    public static void toggle() { setEnabled(!enabled); ClientSettings.save(); }
    public static boolean tracers() { return tracers; }
    public static void setTracers(boolean value) { tracers = value; }
    public static void toggleTracers() { tracers = !tracers; ClientSettings.save(); }
    public static boolean alerts() { return alerts; }
    public static void setAlerts(boolean value) { alerts = value; }
    public static void toggleAlerts() { alerts = !alerts; ClientSettings.save(); }
    public static int radius() { return radius; }
    public static int radiusStepIndex() {
        for (int i = 0; i < RANGES.length; i++) if (RANGES[i] == radius) return i;
        return 3;
    }
    public static int radiusSteps() { return RANGES.length; }
    public static void setRadiusStep(int step) { setRadius(RANGES[Math.max(0, Math.min(RANGES.length - 1, step))]); }
    public static void setRadius(int range) {
        radius = range == BlockEsp.UNLIMITED ? range : Math.max(64, Math.min(512, range));
        restart();
    }
    public static String radiusLabel() { return radius == BlockEsp.UNLIMITED ? "infinity" : radius + " blocks"; }
    public static int minScore() { return minScore; }
    public static void setMinScore(int value) {
        minScore = Math.max(12, Math.min(72, value));
        if (enabled && currentLevel != null) publish(currentLevel, scanCenter);
    }
    public static List<Site> sites() { return sites; }
    public static boolean scanning() { return scanning; }
    public static int scannedChunks() { return snapshots.size(); }

    public static void clear() {
        snapshots.clear();
        alertedChunks.clear();
        sites = List.of();
        restart();
    }
    public static void onWorldGone() {
        clear();
        currentLevel = null;
    }
    private static void restart() {
        scanning = false;
        scanChunks = List.of();
        chunkIndex = sectionIndex = 0;
        partial = new Snapshot();
        sinceRefresh = REFRESH_TICKS;
    }

    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            if (currentLevel != null) onWorldGone();
            return;
        }
        if (currentLevel != mc.level) {
            onWorldGone();
            currentLevel = mc.level;
        }
        if (!enabled) return;
        ClientLevel level = mc.level;
        BlockPos center = mc.player.blockPosition();
        if (Math.abs(center.getX() - scanCenter.getX()) > 32
                || Math.abs(center.getZ() - scanCenter.getZ()) > 32) restart();
        if (!scanning && ++sinceRefresh >= REFRESH_TICKS) begin(level, center);
        long deadline = System.nanoTime() + BUDGET_NS;
        int processed = 0;
        boolean changed = false;
        while (scanning && processed < MAX_SECTIONS_PER_TICK && System.nanoTime() < deadline) {
            if (chunkIndex >= scanChunks.size()) {
                scanning = false;
                sinceRefresh = 0;
                break;
            }
            ChunkKey key = scanChunks.get(chunkIndex);
            LevelChunk chunk = level.getChunkSource().getChunk(key.x, key.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                snapshots.remove(key);
                advanceChunk();
                changed = true;
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= order.length) {
                snapshots.put(key, partial);
                changed = true;
                advanceChunk();
                continue;
            }
            int index = order[sectionIndex++];
            if (index < 0 || index >= sections.length) continue;
            processed++;
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(s -> category(s.getBlock()) >= 0)) continue;
            int originX = key.x << 4;
            int originY = level.getSectionYFromSectionIndex(index) << 4;
            int originZ = key.z << 4;
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int category = category(section.getBlockState(x, y, z).getBlock());
                if (category >= 0) partial.add(originX + x, originY + y, originZ + z, category);
            }
        }
        if (changed || mc.player.tickCount % 20 == 0) publish(level, center);
    }

    private static void advanceChunk() {
        chunkIndex++;
        sectionIndex = 0;
        partial = new Snapshot();
    }

    private static void begin(ClientLevel level, BlockPos center) {
        scanCenter = center.immutable();
        sinceRefresh = 0;
        chunkIndex = sectionIndex = 0;
        partial = new Snapshot();
        int distance = radius == BlockEsp.UNLIMITED ? CHUNKS_RADIUS_CAP
                : Math.min(CHUNKS_RADIUS_CAP, (radius + 15) / 16 + 1);
        int centerX = center.getX() >> 4, centerZ = center.getZ() >> 4;
        ArrayList<ChunkKey> available = new ArrayList<>();
        for (int x = centerX - distance; x <= centerX + distance; x++) {
            for (int z = centerZ - distance; z <= centerZ + distance; z++) {
                if (level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false) != null)
                    available.add(new ChunkKey(x, z));
            }
        }
        available.sort(Comparator.comparingLong(k ->
                (long)(k.x - centerX) * (k.x - centerX) + (long)(k.z - centerZ) * (k.z - centerZ)));
        scanChunks = List.copyOf(available);
        Integer[] sectionOrder = new Integer[level.getSectionsCount()];
        for (int i = 0; i < sectionOrder.length; i++) sectionOrder[i] = i;
        Arrays.sort(sectionOrder, Comparator.comparingInt(i ->
                Math.abs((level.getSectionYFromSectionIndex(i) << 4) + 8 - center.getY())));
        order = new int[sectionOrder.length];
        for (int i = 0; i < order.length; i++) order[i] = sectionOrder[i];
        scanning = !scanChunks.isEmpty();
    }

    private static void publish(ClientLevel level, BlockPos center) {
        // Drop old chunks only after they are really unloaded; moving doesn't clear results.
        snapshots.keySet().removeIf(key ->
                level.getChunkSource().getChunk(key.x, key.z, ChunkStatus.FULL, false) == null);
        ArrayList<Site> candidates = new ArrayList<>();
        for (Map.Entry<ChunkKey, Snapshot> entry : snapshots.entrySet()) {
            ChunkKey key = entry.getKey();
            Snapshot s = entry.getValue();
            if (s.samples == 0) continue;
            long dx = ((long)key.x << 4) + 8 - center.getX();
            long dz = ((long)key.z << 4) + 8 - center.getZ();
            if (radius != BlockEsp.UNLIMITED && (Math.abs(dx) > radius + 16 || Math.abs(dz) > radius + 16)) continue;
            int score = s.score();
            if (score < minScore) continue;
            int x = (int)(s.sx / s.samples), y = (int)(s.sy / s.samples), z = (int)(s.sz / s.samples);
            String label = s.count(STORAGE) >= 4 && s.count(UTILITY) == 0 ? "Storage stash"
                    : s.count(MECHANISM) >= 4 ? "Redstone build"
                    : s.count(SPECIAL) > 0 ? "Advanced base" : "Possible base";
            int color = label.equals("Storage stash") ? 0xFFD39AFF
                    : label.equals("Redstone build") ? 0xFF64DEFF : 0xFFFF9B32;
            candidates.add(new Site(new BlockPos(x,y,z), score, s.count(STORAGE),
                    s.count(UTILITY) + s.count(BED), s.count(MECHANISM), label, color));
        }
        candidates.sort(Comparator.comparingInt(Site::score).reversed());
        ArrayList<Site> result = new ArrayList<>();
        for (Site candidate : candidates) {
            boolean duplicate = false;
            for (Site existing : result) {
                long dx = (long)existing.pos.getX() - candidate.pos.getX();
                long dz = (long)existing.pos.getZ() - candidate.pos.getZ();
                if (dx * dx + dz * dz < 32L * 32L) { duplicate = true; break; }
            }
            if (!duplicate) result.add(candidate);
            if (result.size() >= MAX_SITES) break;
        }
        result.sort(Comparator.comparingDouble(s -> s.pos.distSqr(center)));
        sites = List.copyOf(result);
        if (alerts && !sites.isEmpty()) {
            for (Site site : sites) {
                ChunkKey key = new ChunkKey(site.pos.getX() >> 4, site.pos.getZ() >> 4);
                if (site.score < minScore + 12 || alertedChunks.contains(key)) continue;
                long now = System.currentTimeMillis();
                if (now - lastAlertMillis < 5000) break;
                alertedChunks.add(key);
                lastAlertMillis = now;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) mc.player.sendSystemMessage(Component.literal(
                        "[DonutVision] " + site.label + " at X " + site.pos.getX()
                                + ", Z " + site.pos.getZ() + " (score " + site.score + ")"));
                break;
            }
        }
    }

    /** Signals chosen to avoid treating natural ore veins as bases. */
    private static int category(Block block) {
        if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST || block == Blocks.BARREL
                || block == Blocks.ENDER_CHEST || block instanceof ShulkerBoxBlock) return STORAGE;
        if (block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE
                || block == Blocks.SMOKER || block == Blocks.BREWING_STAND
                || block == Blocks.STONECUTTER || block == Blocks.GRINDSTONE
                || block == Blocks.SMITHING_TABLE || block == Blocks.ANVIL
                || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL) return UTILITY;
        if (block instanceof BedBlock) return BED;
        if (block == Blocks.HOPPER || block == Blocks.PISTON || block == Blocks.STICKY_PISTON
                || block == Blocks.OBSERVER || block == Blocks.DISPENSER || block == Blocks.DROPPER
                || block == Blocks.REPEATER || block == Blocks.COMPARATOR || block == Blocks.REDSTONE_BLOCK) return MECHANISM;
        if (block == Blocks.BEACON || block == Blocks.ENCHANTING_TABLE
                || block == Blocks.NETHER_PORTAL || block == Blocks.LODESTONE) return SPECIAL;
        return -1;
    }
}
