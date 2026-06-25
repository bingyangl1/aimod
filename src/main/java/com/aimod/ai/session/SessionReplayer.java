package com.aimod.ai.session;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replay engine for session logs — "packet capture replay" for debugging.
 *
 * <p>Reads a saved session log and replays it step by step, printing the
 * decision process and results. This allows debugging without running the game.</p>
 *
 * <p>Usage:
 * <pre>
 * // Replay a session from command line
 * /ai_bot replay &lt;session-id&gt;
 *
 * // Or programmatically
 * SessionReplayer.replay(Path.of("config/aimod/sessions/abc123"));
 * </pre>
 */
public class SessionReplayer {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Replay a session from its directory.
     */
    public static void replay(Path sessionDir) {
        if (!Files.isDirectory(sessionDir)) {
            System.out.println("Session directory not found: " + sessionDir);
            return;
        }

        // Load summary
        JsonObject summary = loadJson(sessionDir.resolve("summary.json"));
        if (summary == null) {
            System.out.println("No summary.json found");
            return;
        }

        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║           SESSION REPLAY                        ║");
        System.out.println("╠══════════════════════════════════════════════════╣");
        System.out.println("║ Session ID: " + padRight(summary.get("sessionId").getAsString(), 36) + "║");
        System.out.println("║ Goal:       " + padRight(summary.get("goal").getAsString(), 36) + "║");
        System.out.println("║ Steps:      " + padRight(summary.get("totalSteps").getAsInt() + "", 36) + "║");
        System.out.println("║ LLM Calls:  " + padRight(summary.get("totalLLMCalls").getAsInt() + "", 36) + "║");
        System.out.println("║ Duration:   " + padRight(summary.get("durationMs").getAsInt() + "ms", 36) + "║");
        System.out.println("║ Achieved:   " + padRight(summary.get("goalAchieved").getAsBoolean() + "", 36) + "║");
        if (summary.has("failReason")) {
            System.out.println("║ Fail Reason:" + padRight(summary.get("failReason").getAsString().substring(0,
                    Math.min(36, summary.get("failReason").getAsString().length())), 36) + "║");
        }
        System.out.println("╚══════════════════════════════════════════════════╝");

        // Load and replay steps
        Path stepsDir = sessionDir.resolve("steps");
        if (!Files.isDirectory(stepsDir)) {
            System.out.println("No steps directory found");
            return;
        }

        List<Path> stepFiles = new ArrayList<>();
        try {
            Files.list(stepsDir)
                    .filter(p -> p.toString().endsWith(".json"))
                    .sorted()
                    .forEach(stepFiles::add);
        } catch (IOException e) {
            System.out.println("Failed to list steps: " + e.getMessage());
            return;
        }

        for (Path stepFile : stepFiles) {
            JsonObject step = loadJson(stepFile);
            if (step == null) continue;

            System.out.println("\n┌─── Step " + step.get("step").getAsInt() + " ───────────────────────────");

            // World state before
            if (step.has("worldStateBefore")) {
                JsonObject state = step.getAsJsonObject("worldStateBefore");
                if (state.has("position")) {
                    JsonObject pos = state.getAsJsonObject("position");
                    System.out.printf("│ Position: %s, %s, %s%n", pos.get("x"), pos.get("y"), pos.get("z"));
                }
                if (state.has("health")) {
                    System.out.printf("│ Health: %s  Food: %s%n", state.get("health"), state.get("food"));
                }
            }

            // Decision
            if (step.has("decision")) {
                JsonObject decision = step.getAsJsonObject("decision");
                System.out.println("│ LLM Reasoning: " + truncate(decision.get("reasoning").getAsString(), 60));
                System.out.println("│ Action: " + step.get("actionDescription").getAsString());
            }

            // Result
            String status = step.get("resultStatus").getAsString();
            long duration = step.get("executionDurationMs").getAsLong();
            if ("COMPLETED".equals(status)) {
                System.out.println("│ Result: ✅ SUCCESS (" + duration + "ms)");
            } else {
                System.out.println("│ Result: ❌ FAILED (" + duration + "ms)");
                if (step.has("failReason")) {
                    System.out.println("│ Reason: " + step.get("failReason").getAsString());
                }
            }

            System.out.println("└────────────────────────────────────────────");
        }

        // Load and show LLM calls summary
        Path llmDir = sessionDir.resolve("llm");
        if (Files.isDirectory(llmDir)) {
            System.out.println("\n╔══════════════════════════════════════════════════╗");
            System.out.println("║           LLM CALLS SUMMARY                     ║");
            System.out.println("╠══════════════════════════════════════════════════╣");

            try {
                Files.list(llmDir)
                        .filter(p -> p.toString().endsWith("-request.json"))
                        .sorted()
                        .forEach(reqFile -> {
                            JsonObject req = loadJson(reqFile);
                            if (req != null) {
                                String model = req.has("model") ? req.get("model").getAsString() : "unknown";
                                long duration = req.has("durationMs") ? req.get("durationMs").getAsLong() : 0;
                                System.out.printf("║ Step %3d: model=%s, duration=%dms%n",
                                        req.get("step").getAsInt(), model, duration);
                            }
                        });
            } catch (IOException e) {
                System.out.println("║ Error reading LLM logs: " + e.getMessage());
            }

            System.out.println("╚══════════════════════════════════════════════════╝");
        }
    }

    private static JsonObject loadJson(Path file) {
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            return GSON.fromJson(content, JsonObject.class);
        } catch (Exception e) {
            DevLog.warn("SESSION_REPLAY_LOAD_FAIL", "file={}, err={}", file, e.getMessage());
            return null;
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        String clean = s.replace("\n", " ").replace("\r", "");
        return clean.length() > maxLen ? clean.substring(0, maxLen) + "..." : clean;
    }
}
