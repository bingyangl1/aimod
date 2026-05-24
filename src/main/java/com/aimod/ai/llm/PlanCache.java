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
    private static final int MAX_AGE_DAYS = 7;
    private static final long MAX_AGE_MS = MAX_AGE_DAYS * 24L * 60 * 60 * 1000;
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
        // Remove expired entries on load
        long now = System.currentTimeMillis();
        boolean changed = plans.removeIf(p -> (now - p.timestamp) > MAX_AGE_MS);
        if (changed) save();
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

    /**
     * TF-IDF weighted similarity between two tokenized strings.
     * Uses term frequency overlap weighted by inverse document frequency
     * (rare words contribute more). Falls back to character-level bigram
     * Jaccard for short strings.
     */
    static double similarity(String rawA, String rawB) {
        if (rawA.equals(rawB)) return 1.0;
        if (rawA.isEmpty() || rawB.isEmpty()) return 0.0;

        String a = rawA.replaceAll("\\d+", "N").toLowerCase(Locale.ROOT);
        String b = rawB.replaceAll("\\d+", "N").toLowerCase(Locale.ROOT);

        // For short strings, use character bigram Jaccard
        if (a.length() <= 6 || b.length() <= 6) {
            return charBigramJaccard(a, b);
        }

        // Tokenize preserving Chinese characters as single-character tokens
        List<String> tokensA = tokenize(a);
        List<String> tokensB = tokenize(b);

        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        // Build term frequency maps
        Map<String, Integer> tfA = new HashMap<>();
        for (String t : tokensA) tfA.merge(t, 1, Integer::sum);
        Map<String, Integer> tfB = new HashMap<>();
        for (String t : tokensB) tfB.merge(t, 1, Integer::sum);

        // Compute weighted intersection over union using TF-IDF-like scoring
        // Each token's weight = 1 / (1 + log(df)) — rare tokens carry more weight
        // Since df is per-command-pair, use TF instead.
        double weightedInter = 0;
        double weightedUnion = 0;

        Set<String> allTokens = new HashSet<>(tokensA);
        allTokens.addAll(tokensB);

        for (String token : allTokens) {
            int freqA = tfA.getOrDefault(token, 0);
            int freqB = tfB.getOrDefault(token, 0);

            // Boost weight for longer/more-specific tokens (they carry more meaning)
            double weight = 1.0 + Math.log1p(token.length());
            // If a token appears many times, slightly reduce weight per occurrence
            // (common words like "the", "a", 挖矿, 把 get de-emphasized)
            if (freqA > 2 || freqB > 2) weight *= 0.5;

            double unionContrib = Math.max(freqA, freqB) * weight;
            double interContrib = Math.min(freqA, freqB) * weight;

            if (unionContrib > 0) {
                double score = interContrib / unionContrib;
                // Bonus for exact token match
                if (freqA > 0 && freqB > 0) score *= (1.0 + 0.2 * weight);
                weightedInter += score * unionContrib;
                weightedUnion += unionContrib;
            }
        }

        if (weightedUnion <= 0) return 0;

        double score = weightedInter / weightedUnion;
        // Bonus for shared bigrams/trigrams
        double ngramBonus = ngramOverlap(tokensA, tokensB, 2) * 0.15
                          + ngramOverlap(tokensA, tokensB, 3) * 0.1;
        return Math.min(1.0, score + ngramBonus);
    }

    /** Tokenize: split on whitespace + preserve CJK characters as individual tokens. */
    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        // Split by whitespace, then further split CJK characters
        for (String word : s.split("\\s+")) {
            if (word.isEmpty()) continue;
            StringBuilder lat = new StringBuilder();
            for (int i = 0; i < word.length(); i++) {
                char c = word.charAt(i);
                if (Character.isIdeographic(c)) {
                    if (lat.length() > 0) { tokens.add(lat.toString()); lat.setLength(0); }
                    tokens.add(String.valueOf(c));
                } else {
                    lat.append(c);
                }
            }
            if (lat.length() > 0) tokens.add(lat.toString());
        }
        return tokens;
    }

    /** Character bigram Jaccard for short strings. */
    private static double charBigramJaccard(String a, String b) {
        if (a.equals(b)) return 1.0;
        Set<String> bigramsA = new HashSet<>();
        Set<String> bigramsB = new HashSet<>();
        for (int i = 0; i < a.length() - 1; i++) bigramsA.add(a.substring(i, i + 2));
        for (int i = 0; i < b.length() - 1; i++) bigramsB.add(b.substring(i, i + 2));
        Set<String> union = new HashSet<>(bigramsA); union.addAll(bigramsB);
        Set<String> inter = new HashSet<>(bigramsA); inter.retainAll(bigramsB);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    /** N-gram overlap ratio between two token lists. */
    private static double ngramOverlap(List<String> a, List<String> b, int n) {
        if (a.size() < n || b.size() < n) return 0;
        Set<List<String>> ngramsA = new HashSet<>();
        Set<List<String>> ngramsB = new HashSet<>();
        for (int i = 0; i <= a.size() - n; i++) ngramsA.add(a.subList(i, i + n));
        for (int i = 0; i <= b.size() - n; i++) ngramsB.add(b.subList(i, i + n));
        Set<List<String>> union = new HashSet<>(ngramsA); union.addAll(ngramsB);
        Set<List<String>> inter = new HashSet<>(ngramsA); inter.retainAll(ngramsB);
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
