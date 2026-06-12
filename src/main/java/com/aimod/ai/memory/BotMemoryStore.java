package com.aimod.ai.memory;

import com.aimod.ai.Task;
import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Three-tier memory store for bot context management.
 *
 * <p>Tier 1 — Working Memory: recent WorldObservation snapshots (in-memory Deque).
 * Tier 2 — Short-term Summaries: compacted action result summaries (JSON persisted).
 * Tier 3 — Long-term Knowledge: resource locations, explored chunks (JSON persisted).</p>
 *
 * <p>Inspired by OpenClaw's ContextEngine — separates "what the bot sees now"
 * from "what the bot remembers" and "what the bot knows permanently."</p>
 */
public final class BotMemoryStore {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX_WORKING = 100;
    private static final int DEFAULT_MAX_SUMMARIES = 200;
    private static final int DEFAULT_MAX_RESOURCES = 500;

    private final Deque<WorldObservation> workingMemory = new ArrayDeque<>();
    private final Deque<String> shortTermSummaries = new ArrayDeque<>(); // O(1) add/removeFirst
    private final List<ResourceLocation> resourceLocations = new ArrayList<>();
    private final Set<String> exploredChunks = new HashSet<>();
    private final Object lock = new Object(); // Thread safety for all collections

    private final Path dataDir;
    private final int maxWorking;
    private int ingestCount;

    // JSON file paths
    private final Path summariesFile;
    private final Path resourcesFile;
    private final Path chunksFile;

    public BotMemoryStore(Path worldDir) {
        this(worldDir, DEFAULT_MAX_WORKING);
    }

    public BotMemoryStore(Path worldDir, int maxWorking) {
        this.dataDir = worldDir.resolve("config/aimod/memory");
        this.maxWorking = Math.max(20, Math.min(500, maxWorking));
        this.summariesFile = dataDir.resolve("short_term_summaries.json");
        this.resourcesFile = dataDir.resolve("resource_locations.json");
        this.chunksFile = dataDir.resolve("explored_chunks.json");
        this.ingestCount = 0;
    }

    // --------------- Tier 1: Working Memory ---------------

    /** Ingest a new observation. Auto-trims if over capacity. */
    public void ingest(WorldObservation obs) {
        synchronized (lock) {
            // Skip if identical to previous observation (bot standing still)
            WorldObservation prev = workingMemory.peekLast();
            if (prev != null && obs.isDuplicateOf(prev)) {
                return; // skip duplicate
            }
            workingMemory.addLast(obs);
            while (workingMemory.size() > maxWorking) {
                workingMemory.removeFirst();
            }
            ingestCount++;
        }
    }

    /** Return the most recent N observations (newest first). */
    public List<WorldObservation> getRecent(int n) {
        synchronized (lock) {
            List<WorldObservation> result = new ArrayList<>();
            var it = workingMemory.descendingIterator();
            while (it.hasNext() && result.size() < n) {
                result.add(it.next());
            }
            return result;
        }
    }

    public int workingMemorySize() {
        synchronized (lock) {
            return workingMemory.size();
        }
    }

    // --------------- Tier 2: Short-term Summaries ---------------

    /**
     * Compact the oldest M working-memory entries into a single summary line.
     * Removes the compacted entries from working memory.
     * @param count how many oldest entries to compact
     * @return estimated tokens freed
     */
    public int compact(int count) {
        synchronized (lock) {
            if (workingMemory.size() < count) count = workingMemory.size();
            if (count < 3) return 0;

            int tokensFreed = 0;
            StringBuilder summary = new StringBuilder("Summary of " + count + " ticks: ");
            for (int i = 0; i < count; i++) {
                WorldObservation obs = workingMemory.removeFirst();
                tokensFreed += obs.estimateTokens();
                if (i == 0) {
                    summary.append(obs.toCompactString());
                } else if (i == count - 1) {
                    summary.append(" → ").append(obs.toCompactString());
                }
            }
            shortTermSummaries.addLast(summary.toString());
            while (shortTermSummaries.size() > DEFAULT_MAX_SUMMARIES) {
                shortTermSummaries.removeFirst();
            }
            saveSummaries();
            DevLog.info("MEMORY_COMPACT", "count={}, freed={}, totalSummaries={}",
                    count, tokensFreed, shortTermSummaries.size());
            return tokensFreed;
        }
    }

    /** Compact until estimated working-memory tokens drop below target. */
    public int compactToTokenTarget(int targetTokens) {
        int currentTokens = estimateWorkingTokens();
        if (currentTokens <= targetTokens) return 0;
        int totalFreed = 0;
        for (int round = 0; round < 10; round++) {
            int batch = Math.max(3, workingMemory.size() / 5);
            int freed = compact(batch);
            totalFreed += freed;
            if (estimateWorkingTokens() <= targetTokens) break;
            if (freed == 0) break;
        }
        return totalFreed;
    }

    /** Get all short-term summaries. */
    public List<String> getShortTermSummaries() {
        synchronized (lock) {
            return new ArrayList<>(shortTermSummaries);
        }
    }

    // --------------- Tier 3: Long-term Knowledge ---------------

    /** Remember a resource location for future reference. */
    public void rememberLocation(String blockType, BlockPos pos, String dimension) {
        synchronized (lock) {
            // Update existing if same type+position
            for (var rl : resourceLocations) {
                if (rl.blockType.equals(blockType) && rl.x == pos.getX()
                        && rl.y == pos.getY() && rl.z == pos.getZ()
                        && rl.dimension.equals(dimension)) {
                    rl.lastSeenAt = System.currentTimeMillis();
                    return;
                }
            }
            resourceLocations.add(new ResourceLocation(blockType, pos.getX(), pos.getY(), pos.getZ(),
                    dimension, System.currentTimeMillis()));
            while (resourceLocations.size() > DEFAULT_MAX_RESOURCES) {
                resourceLocations.remove(0);
            }
            saveResources();
        }
    }

    /** Query known resources near a position, ordered by distance. */
    public List<ResourceLocation> queryNearbyResources(BlockPos center, int radius) {
        synchronized (lock) {
            List<ResourceLocation> result = new ArrayList<>();
            for (var rl : resourceLocations) {
                if (rl.mined) continue;
                int dx = rl.x - center.getX();
                int dy = rl.y - center.getY();
                int dz = rl.z - center.getZ();
                if (Math.abs(dx) <= radius && Math.abs(dy) <= radius && Math.abs(dz) <= radius) {
                    rl._cachedDist = dx * dx + dy * dy + dz * dz;
                    result.add(rl);
                }
            }
            result.sort(Comparator.comparingInt(r -> r._cachedDist));
            return result;
        }
    }

    /** Mark a resource as mined. */
    public void markResourceMined(BlockPos pos) {
        synchronized (lock) {
            for (var rl : resourceLocations) {
                if (rl.x == pos.getX() && rl.y == pos.getY() && rl.z == pos.getZ()) {
                    rl.mined = true;
                    return;
                }
            }
        }
    }

    public int resourceLocationCount() {
        synchronized (lock) { return resourceLocations.size(); }
    }

    /** Mark a chunk as explored. */
    public void markChunkExplored(int cx, int cz, String dimension) {
        synchronized (lock) {
            String key = cx + "," + cz + ":" + dimension;
            if (exploredChunks.add(key)) {
                saveChunks();
            }
        }
    }

    /** Check if a chunk was explored. */
    public boolean isChunkExplored(int cx, int cz, String dimension) {
        synchronized (lock) {
            return exploredChunks.contains(cx + "," + cz + ":" + dimension);
        }
    }

    public int exploredChunkCount() {
        synchronized (lock) { return exploredChunks.size(); }
    }

    // --------------- Bootstrap / Persistence ---------------

    /** Load persistent knowledge from disk. Called on server start. */
    public void bootstrap() {
        try {
            if (!Files.exists(dataDir)) Files.createDirectories(dataDir);
        } catch (IOException e) {
            DevLog.warn("MEMORY_DIR_FAIL", "path={}", dataDir);
            return;
        }
        loadSummaries();
        loadResources();
        loadChunks();
        DevLog.info("MEMORY_BOOTSTRAP", "summaries={}, resources={}, chunks={}",
                shortTermSummaries.size(), resourceLocations.size(), exploredChunks.size());
    }

    /** Get human-readable stats. */
    public String getStats() {
        synchronized (lock) {
            return String.format("Working: %d/%d | Summaries: %d | Resources: %d | Chunks: %d | Total ingested: %d",
                    workingMemory.size(), maxWorking, shortTermSummaries.size(),
                    resourceLocations.size(), exploredChunks.size(), ingestCount);
        }
    }

    /** Clear all memory (including persisted). */
    public void clear() {
        synchronized (lock) {
            workingMemory.clear();
            shortTermSummaries.clear();
            resourceLocations.clear();
            exploredChunks.clear();
            ingestCount = 0;
            try {
                Files.deleteIfExists(summariesFile);
                Files.deleteIfExists(resourcesFile);
                Files.deleteIfExists(chunksFile);
            } catch (IOException ignored) {}
            DevLog.info("MEMORY_CLEAR", "all memory cleared");
        }
    }

    // --------------- Internal ---------------

    private int estimateWorkingTokens() {
        int total = 0;
        for (var obs : workingMemory) total += obs.estimateTokens();
        return total;
    }

    private void saveSummaries() {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(summariesFile, GSON.toJson(shortTermSummaries),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DevLog.warn("MEMORY_SAVE_FAIL", "file=summaries");
        }
    }

    private void loadSummaries() {
        try {
            if (Files.exists(summariesFile)) {
                String json = Files.readString(summariesFile);
                Type listType = new TypeToken<List<String>>(){}.getType();
                List<String> loaded = GSON.fromJson(json, listType);
                if (loaded != null) {
                    shortTermSummaries.addAll(loaded);
                }
            }
        } catch (IOException e) {
            DevLog.warn("MEMORY_LOAD_FAIL", "file=summaries");
        }
    }

    private void saveResources() {
        try {
            Files.createDirectories(dataDir);
            Files.writeString(resourcesFile, GSON.toJson(resourceLocations),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DevLog.warn("MEMORY_SAVE_FAIL", "file=resources");
        }
    }

    private void loadResources() {
        try {
            if (Files.exists(resourcesFile)) {
                String json = Files.readString(resourcesFile);
                Type listType = new TypeToken<List<ResourceLocation>>(){}.getType();
                List<ResourceLocation> loaded = GSON.fromJson(json, listType);
                if (loaded != null) {
                    resourceLocations.addAll(loaded);
                    // Clean up transient cached-dist field
                    for (var rl : resourceLocations) rl._cachedDist = 0;
                }
            }
        } catch (IOException e) {
            DevLog.warn("MEMORY_LOAD_FAIL", "file=resources");
        }
    }

    private void saveChunks() {
        try {
            Files.createDirectories(dataDir);
            List<String> list = new ArrayList<>(exploredChunks);
            Files.writeString(chunksFile, GSON.toJson(list),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DevLog.warn("MEMORY_SAVE_FAIL", "file=chunks");
        }
    }

    private void loadChunks() {
        try {
            if (Files.exists(chunksFile)) {
                String json = Files.readString(chunksFile);
                Type listType = new TypeToken<List<String>>(){}.getType();
                List<String> loaded = GSON.fromJson(json, listType);
                if (loaded != null) {
                    exploredChunks.addAll(loaded);
                }
            }
        } catch (IOException e) {
            DevLog.warn("MEMORY_LOAD_FAIL", "file=chunks");
        }
    }

    // --------------- Data Types ---------------

    /** Serializable record of a known resource location. */
    public static class ResourceLocation {
        public String blockType;
        public int x, y, z;
        public String dimension;
        public long discoveredAt;
        public long lastSeenAt;
        public boolean mined;
        transient int _cachedDist; // not serialized

        ResourceLocation() {} // for Gson

        ResourceLocation(String bt, int x, int y, int z, String dim, long ts) {
            this.blockType = bt;
            this.x = x; this.y = y; this.z = z;
            this.dimension = dim;
            this.discoveredAt = ts;
            this.lastSeenAt = ts;
            this.mined = false;
        }

        public String shortName() {
            return blockType.contains(":") ? blockType.substring(blockType.lastIndexOf(':') + 1) : blockType;
        }

        @Override
        public String toString() {
            return shortName() + "@(" + x + "," + y + "," + z + ")" + (mined ? " [mined]" : "");
        }
    }
}
