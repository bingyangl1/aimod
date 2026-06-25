package com.aimod.ai;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Incremental ore index — inspired by XRay's chunk-based caching strategy.
 *
 * <p>Instead of scanning the entire world every time, this index stores
 * discovered ore positions per chunk. When the bot moves to a new area,
 * only new chunks are scanned; existing results are reused.</p>
 *
 * <p>Key pattern from XRay: "scan once, cache, incremental update".</p>
 *
 * <p>Thread-safe: uses ConcurrentHashMap for concurrent access from
 * scanner threads and the server tick thread.</p>
 */
public class OreIndex {

    /** Ore positions indexed by chunk position. */
    private final ConcurrentHashMap<ChunkPos, List<BlockPos>> index = new ConcurrentHashMap<>();

    /** Chunks that have been fully scanned. */
    private final Set<ChunkPos> scannedChunks = ConcurrentHashMap.newKeySet();

    /** Maximum number of chunks to keep in the index. */
    private static final int MAX_INDEXED_CHUNKS = 512;

    /**
     * Get all known ore positions within the given radius of the bot.
     * Returns only positions from already-scanned chunks (no new scanning).
     */
    public List<BlockPos> query(BlockPos center, int radius) {
        List<BlockPos> results = new ArrayList<>();
        int radiusSq = radius * radius;

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                ChunkPos cp = new ChunkPos(cx, cz);
                List<BlockPos> chunkOres = index.get(cp);
                if (chunkOres == null) continue;

                for (BlockPos ore : chunkOres) {
                    int dx = ore.getX() - center.getX();
                    int dy = ore.getY() - center.getY();
                    int dz = ore.getZ() - center.getZ();
                    if (dx * dx + dy * dy + dz * dz <= radiusSq) {
                        results.add(ore);
                    }
                }
            }
        }

        // Sort by distance
        results.sort(Comparator.comparingDouble(pos -> center.distSqr(pos)));
        return results;
    }

    /**
     * Get the set of chunks that still need scanning within the given area.
     * Returns chunk positions that are in range but not yet in the index.
     */
    public Set<ChunkPos> getUnscannedChunks(BlockPos center, int radius) {
        Set<ChunkPos> unscanned = new HashSet<>();

        int minChunkX = (center.getX() - radius) >> 4;
        int maxChunkX = (center.getX() + radius) >> 4;
        int minChunkZ = (center.getZ() - radius) >> 4;
        int maxChunkZ = (center.getZ() + radius) >> 4;

        int radiusSq = radius * radius;
        int cx0 = center.getX() >> 4;
        int cz0 = center.getZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                // Check if chunk center is within radius
                int chunkCenterX = (cx << 4) + 8;
                int chunkCenterZ = (cz << 4) + 8;
                int dx = chunkCenterX - center.getX();
                int dz = chunkCenterZ - center.getZ();
                if (dx * dx + dz * dz > radiusSq + 256) continue; // +256 for chunk size margin

                ChunkPos cp = new ChunkPos(cx, cz);
                if (!scannedChunks.contains(cp)) {
                    unscanned.add(cp);
                }
            }
        }

        return unscanned;
    }

    /**
     * Store ore positions for a chunk (called after scanning).
     */
    public void putChunkOres(ChunkPos chunkPos, List<BlockPos> ores) {
        index.put(chunkPos, ores);
        scannedChunks.add(chunkPos);

        // Evict old chunks if index is too large
        if (scannedChunks.size() > MAX_INDEXED_CHUNKS) {
            evictDistantChunks(ores.isEmpty() ? null : ores.get(0));
        }
    }

    /**
     * Add a single ore position (called by block change tracker).
     */
    public void addOre(ChunkPos chunkPos, BlockPos pos) {
        index.computeIfAbsent(chunkPos, k -> new ArrayList<>()).add(pos);
    }

    /**
     * Remove an ore position (called when block is broken).
     */
    public void removeOre(ChunkPos chunkPos, BlockPos pos) {
        List<BlockPos> ores = index.get(chunkPos);
        if (ores != null) {
            ores.remove(pos);
        }
    }

    /**
     * Check if a chunk has been scanned.
     */
    public boolean isChunkScanned(ChunkPos chunkPos) {
        return scannedChunks.contains(chunkPos);
    }

    /**
     * Mark a chunk as needing rescan (e.g., after block changes).
     */
    public void invalidateChunk(ChunkPos chunkPos) {
        scannedChunks.remove(chunkPos);
        index.remove(chunkPos);
    }

    /**
     * Get total number of indexed ores.
     */
    public int getTotalOreCount() {
        return index.values().stream().mapToInt(List::size).sum();
    }

    /**
     * Get number of scanned chunks.
     */
    public int getScannedChunkCount() {
        return scannedChunks.size();
    }

    /**
     * Evict chunks that are far from the given position.
     */
    private void evictDistantChunks(BlockPos reference) {
        if (reference == null || scannedChunks.size() <= MAX_INDEXED_CHUNKS / 2) return;

        // Sort chunks by distance from reference
        List<ChunkPos> sorted = new ArrayList<>(scannedChunks);
        sorted.sort(Comparator.comparingDouble(cp -> {
            int dx = (cp.x << 4) + 8 - reference.getX();
            int dz = (cp.z << 4) + 8 - reference.getZ();
            return (double)(dx * dx + dz * dz);
        }));

        // Remove the farthest half
        int toRemove = sorted.size() - MAX_INDEXED_CHUNKS / 2;
        for (int i = sorted.size() - 1; i >= sorted.size() - toRemove; i--) {
            ChunkPos cp = sorted.get(i);
            scannedChunks.remove(cp);
            index.remove(cp);
        }

        DevLog.info("ORE_INDEX_EVICT", "removed={}, remaining={}", toRemove, scannedChunks.size());
    }

    /**
     * Clear the entire index.
     */
    public void clear() {
        index.clear();
        scannedChunks.clear();
    }
}
