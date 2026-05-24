package com.aimod.ai.cache;

import com.aimod.util.DevLog;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Thread-safe chunk cache for fast block state lookups.
 * Packing is asynchronous via a background thread.
 *
 * <p>Uses flat int[] storage for block state IDs (~393KB/chunk)
 * plus a 2-bit pathing-type overlay (~24KB/chunk) inspired by
 * Baritone's CachedWorld.</p>
 *
 * <p>Supports GZIP-compressed disk persistence for warm-start
 * across server restarts. Files stored in
 * {@code config/aimod/cache/&lt;dimension&gt;/chunk_X_Z.bcr}.</p>
 */
public class ChunkCache {

    private static final int MAX_CHUNKS = 2048;
    private static final int PRUNE_TARGET = MAX_CHUNKS * 3 / 4; // 1536
    /** How many nearest chunks to save at shutdown. */
    private static final int SAVE_KEEP = 512;

    private final Long2ObjectMap<CachedChunkData> chunks = new Long2ObjectOpenHashMap<>();
    private final LinkedBlockingQueue<LevelChunk> packQueue = new LinkedBlockingQueue<>(256);
    private final Thread packerThread;
    private final ServerLevel level;
    private final Path cacheDir;
    private volatile boolean running = true;

    // Spatial-locality cache: last-hit chunk
    private CachedChunkData lastChunk;
    private long lastChunkKey = Long.MIN_VALUE;

    public ChunkCache(ServerLevel level) {
        this.level = level;
        // Derive cache directory from dimension
        ResourceLocation dimId = level.dimension().location();
        this.cacheDir = Path.of("config/aimod/cache", dimId.getNamespace(), dimId.getPath());
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            DevLog.warn("CACHE_DIR_FAIL", "dir={}, err={}", cacheDir, e.getMessage());
        }
        loadAll();
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
     * Fast pathing-type lookup (2-bit classification) without
     * resolving the full BlockState. Returns one of:
     * {@link CachedChunkData#TYPE_AIR TYPE_AIR} (0),
     * {@link CachedChunkData#TYPE_WATER TYPE_WATER} (1),
     * {@link CachedChunkData#TYPE_AVOID TYPE_AVOID} (2),
     * {@link CachedChunkData#TYPE_SOLID TYPE_SOLID} (3).
     *
     * <p>Falls back to real-time classification if chunk is not cached.</p>
     */
    public byte getPathingType(int x, int y, int z) {
        int cx = x >> 4, cz = z >> 4;
        long key = CachedChunkData.key(cx, cz);
        CachedChunkData cached;
        synchronized (chunks) {
            cached = chunks.get(key);
        }
        if (cached != null) {
            return cached.getPathingType(x & 15, y, z & 15);
        }
        // Fallback: real-time classification
        if (level.isLoaded(new BlockPos(x, y, z))) {
            return CachedChunkData.classifyBlock(level.getBlockState(new BlockPos(x, y, z)));
        }
        return CachedChunkData.TYPE_AIR;
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
        saveNearby();
    }

    // ---- Persistence ----

    /**
     * Load all cached chunk files from disk at startup.
     */
    private void loadAll() {
        if (!Files.isDirectory(cacheDir)) return;
        try (var files = Files.list(cacheDir)) {
            files.filter(f -> f.toString().endsWith(".bcr")).forEach(f -> {
                String name = f.getFileName().toString();
                try {
                    // Parse "chunk_X_Z.bcr"
                    String stem = name.substring(0, name.length() - 4); // remove .bcr
                    String[] parts = stem.split("_");
                    if (parts.length < 3) return;
                    int cx = Integer.parseInt(parts[1]);
                    int cz = Integer.parseInt(parts[2]);
                    CachedChunkData data = CachedChunkData.load(f, cx, cz);
                    if (data != null) {
                        synchronized (chunks) {
                            chunks.put(CachedChunkData.key(cx, cz), data);
                        }
                    }
                } catch (Exception e) {
                    DevLog.warn("CACHE_LOAD_SKIP", "file={}", name);
                }
            });
        } catch (IOException e) {
            DevLog.warn("CACHE_LOAD_DIR_FAIL", "dir={}", cacheDir);
        }
        DevLog.info("CACHE_LOADED", "chunks={}, dir={}", chunks.size(), cacheDir);
    }

    /**
     * Save the nearest SAVE_KEEP chunks to disk at shutdown.
     */
    private void saveNearby() {
        synchronized (chunks) {
            if (chunks.isEmpty()) return;
            // Sort by distance from origin (0,0)
            var entries = new ArrayList<>(chunks.long2ObjectEntrySet());
            entries.sort(Comparator.comparingDouble(e -> {
                int cx = CachedChunkData.chunkKeyToX(e.getLongKey());
                int cz = CachedChunkData.chunkKeyToZ(e.getLongKey());
                return Math.sqrt((double) cx * cx + (double) cz * cz);
            }));
            int saved = 0, failed = 0;
            int limit = Math.min(SAVE_KEEP, entries.size());
            for (int i = 0; i < limit; i++) {
                var entry = entries.get(i);
                int cx = CachedChunkData.chunkKeyToX(entry.getLongKey());
                int cz = CachedChunkData.chunkKeyToZ(entry.getLongKey());
                Path file = cacheDir.resolve(String.format("chunk_%d_%d.bcr", cx, cz));
                try {
                    entry.getValue().save(file);
                    saved++;
                } catch (Exception e) {
                    failed++;
                }
            }
            DevLog.info("CACHE_SAVED", "saved={}, failed={}, dir={}", saved, failed, cacheDir);
        }
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
