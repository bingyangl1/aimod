package com.aimod.ai.cache;

import com.aimod.util.DevLog;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe chunk cache for fast block state lookups.
 * Packing is asynchronous via a background thread.
 *
 * <p>Simplified from Baritone's CachedWorld — stores full BlockState
 * without 2-bit encoding, omits overview/special-blocks maps.</p>
 */
public class ChunkCache {

    private static final int MAX_CHUNKS = 2048;
    private static final int PRUNE_TARGET = MAX_CHUNKS * 3 / 4; // 1536

    private final Long2ObjectMap<CachedChunkData> chunks = new Long2ObjectOpenHashMap<>();
    private final LinkedBlockingQueue<LevelChunk> packQueue = new LinkedBlockingQueue<>(256);
    private final Thread packerThread;
    private final ServerLevel level;
    private volatile boolean running = true;

    // Spatial-locality cache: last-hit chunk
    private CachedChunkData lastChunk;
    private long lastChunkKey = Long.MIN_VALUE;

    public ChunkCache(ServerLevel level) {
        this.level = level;
        this.packerThread = new Thread(this::packerLoop, "AIMod-ChunkPacker");
        this.packerThread.setDaemon(true);
        this.packerThread.setPriority(Thread.MIN_PRIORITY);
        this.packerThread.start();
    }

    // ---- Public API ----

    /**
     * Get block state at position. Checks real world first, then cache.
     */
    public BlockState getBlockState(BlockPos pos) {
        return getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    public BlockState getBlockState(int x, int y, int z) {
        // Spatial-locality fast path
        int cx = x >> 4, cz = z >> 4;
        long key = CachedChunkData.key(cx, cz);
        if (key == lastChunkKey && lastChunk != null) {
            return lastChunk.getBlock(x & 15, y, z & 15);
        }

        // Try cache
        CachedChunkData cached;
        synchronized (chunks) {
            cached = chunks.get(key);
        }
        if (cached != null) {
            lastChunk = cached;
            lastChunkKey = key;
            return cached.getBlock(x & 15, y, z & 15);
        }

        // Fallback: try real world (chunk might be loaded)
        if (level.isLoaded(new BlockPos(x, y, z))) {
            return level.getBlockState(new BlockPos(x, y, z));
        }

        return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    /**
     * Check if a position is in the cache.
     */
    public boolean isCached(BlockPos pos) {
        long key = CachedChunkData.key(pos.getX() >> 4, pos.getZ() >> 4);
        synchronized (chunks) {
            return chunks.containsKey(key);
        }
    }

    /**
     * Queue a chunk for async packing.
     */
    public void queueForPacking(LevelChunk chunk) {
        packQueue.offer(chunk);
    }

    /**
     * Check if a chunk has been packed (is in cache).
     */
    public boolean isChunkCached(int chunkX, int chunkZ) {
        long key = CachedChunkData.key(chunkX, chunkZ);
        synchronized (chunks) {
            return chunks.containsKey(key);
        }
    }

    /**
     * Get approximate bot position for pruning (use chunk with most blocks).
     */
    public void prune(Vec3 playerPos) {
        synchronized (chunks) {
            if (chunks.size() <= MAX_CHUNKS) return;

            int px = (int) playerPos.x;
            int pz = (int) playerPos.z;

            var entries = new ArrayList<>(chunks.long2ObjectEntrySet());
            entries.sort(Comparator.comparingDouble(e -> {
                int cx = CachedChunkData.chunkKeyToX(e.getLongKey()) * 16 + 8;
                int cz = CachedChunkData.chunkKeyToZ(e.getLongKey()) * 16 + 8;
                return ((cx - px) * (cx - px) + (cz - pz) * (cz - pz));
            }));

            // Remove furthest chunks to reach target size
            for (int i = PRUNE_TARGET; i < entries.size(); i++) {
                chunks.remove(entries.get(i).getLongKey());
            }
            DevLog.info("CHUNK_CACHE_PRUNE", "pruned={}, remaining={}",
                    entries.size() - PRUNE_TARGET, chunks.size());
        }
    }

    public int size() {
        synchronized (chunks) {
            return chunks.size();
        }
    }

    public void shutdown() {
        running = false;
        packerThread.interrupt();
    }

    // ---- Internal ----

    private void packerLoop() {
        while (running) {
            try {
                LevelChunk chunk = packQueue.take();
                packChunk(chunk);
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                DevLog.error("CHUNK_PACK_ERROR", "packing failed", e);
            }
        }
    }

    private void packChunk(LevelChunk chunk) {
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        int minY = chunk.getMinBuildHeight();

        CachedChunkData data = new CachedChunkData(cx, cz, minY);

        for (int sy = 0; sy < chunk.getSectionsCount(); sy++) {
            var section = chunk.getSection(sy);
            if (section == null || section.hasOnlyAir()) continue;
            int baseY = chunk.getSectionYFromSectionIndex(sy) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        data.setBlock(x, baseY + y, z, section.getBlockState(x, y, z));
                    }
                }
            }
        }

        long key = CachedChunkData.key(cx, cz);
        synchronized (chunks) {
            chunks.put(key, data);
        }
    }
}
