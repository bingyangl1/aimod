package com.aimod.ai.llm;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;

/**
 * Persists LLM-planned task templates locally so that similar future
 * commands can skip the LLM API call and reuse cached action plans.
 *
 * <p>Cache is stored as JSON at {@code config/aimod/plan_cache.json}.
 * Each entry records the original command, the parsed actions, and
 * metadata (success, timestamp, use count).</p>
 */
public class PlanCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<CachedPlan>>() {}.getType();
    private static final int MAX_ENTRIES = 50;
    private static final double SIMILARITY_THRESHOLD = 0.6;

    public static class CachedPlan {
        public String command;        // original natural language command
        public List<String> actions;  // JSON action strings
        public boolean success;       // task completed successfully?
        public long timestamp;        // when cached
        public int useCount;          // number of times used
    }

    private final Path cacheFile;
    private final List<CachedPlan> plans;

    public PlanCache(Path serverDir) {
        this.cacheFile = serverDir.resolve("config/aimod/plan_cache.json");
        List<CachedPlan> loaded = load();
        this.plans = loaded != null ? loaded : new ArrayList<CachedPlan>();
    }

    /** Try to find a matching cached plan for the given command. */
    public Optional<List<String>> find(String command) {
        if (command == null || command.isBlank()) return Optional.empty();
        String normalized = command.toLowerCase(Locale.ROOT).replaceAll("\\d+", "N");
        CachedPlan best = null;
        double bestScore = 0;
        for (var plan : plans) {
            double score = similarity(normalized, plan.command.toLowerCase(Locale.ROOT).replaceAll("\\d+", "N"));
            if (score > bestScore && score >= SIMILARITY_THRESHOLD) {
                bestScore = score;
                best = plan;
            }
        }
        if (best != null) {
            best.useCount++;
            DevLog.info("PLAN_CACHE_HIT", "command={}, score={}, uses={}", command, String.format("%.2f", bestScore), best.useCount);
            return Optional.of(best.actions);
        }
        DevLog.info("PLAN_CACHE_MISS", "command={}", command);
        return Optional.empty();
    }

    /** Save a successful plan for future reuse. */
    public void store(String command, List<String> actions, boolean success) {
        if (!success || actions == null || actions.isEmpty()) return;
        // Don't cache plans that are too short or too long
        if (actions.size() < 2 || actions.size() > 50) return;

        // Replace existing entry for same command
        String normCmd = command.toLowerCase(Locale.ROOT);
        plans.removeIf(p -> similarity(normCmd, p.command.toLowerCase(Locale.ROOT)) > 0.9);
        var plan = new CachedPlan();
        plan.command = command;
        plan.actions = new ArrayList<>(actions);
        plan.success = success;
        plan.timestamp = System.currentTimeMillis();
        plan.useCount = 1;
        plans.add(0, plan);

        // Trim to max size
        while (plans.size() > MAX_ENTRIES) plans.remove(plans.size() - 1);
        save();
    }

    /** Jaccard similarity between two tokenized strings. */
    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        Set<String> setA = new HashSet<>(Arrays.asList(a.split("\\s+")));
        Set<String> setB = new HashSet<>(Arrays.asList(b.split("\\s+")));
        Set<String> union = new HashSet<>(setA); union.addAll(setB);
        Set<String> inter = new HashSet<>(setA); inter.retainAll(setB);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    public int size() { return plans.size(); }

    private List<CachedPlan> load() {
        try {
            Files.createDirectories(cacheFile.getParent());
            if (Files.exists(cacheFile)) {
                String json = Files.readString(cacheFile);
                List<CachedPlan> loaded = GSON.fromJson(json, LIST_TYPE);
                if (loaded != null) return loaded;
            }
        } catch (Exception e) { DevLog.warn("PLAN_CACHE_LOAD_FAIL", e.getMessage()); }
        return new ArrayList<CachedPlan>();
    }

    private void save() {
        try {
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, GSON.toJson(plans), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) { DevLog.warn("PLAN_CACHE_SAVE_FAIL", e.getMessage()); }
    }
}
