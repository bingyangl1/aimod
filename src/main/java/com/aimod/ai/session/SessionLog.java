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
import java.util.UUID;

/**
 * Session log for "packet capture and replay" debugging.
 *
 * <p>Records all information during a task execution:
 * <ul>
 *   <li>Goal and initial world state</li>
 *   <li>Each LLM request and response</li>
 *   <li>Each action execution and result</li>
 *   <li>World state changes</li>
 * </ul>
 *
 * <p>Logs are saved to {@code config/aimod/sessions/<session-id>/} and can be
 * replayed offline without running the game (like network packet replay).</p>
 */
public class SessionLog {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path SESSIONS_DIR = Path.of("config", "aimod", "sessions");

    private final String sessionId;
    private final Path sessionDir;
    private final List<StepRecord> steps = new ArrayList<>();
    private final List<LLMRecord> llmRecords = new ArrayList<>();
    private String goalDescription;
    private long startTime;
    private long endTime;

    public SessionLog(String goalDescription) {
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        this.goalDescription = goalDescription;
        this.startTime = System.currentTimeMillis();
        this.sessionDir = SESSIONS_DIR.resolve(sessionId);
    }

    /** Get the session ID. */
    public String getSessionId() { return sessionId; }

    /** Record a completed step. */
    public void recordStep(StepRecord step) {
        steps.add(step);
        saveStep(step);
    }

    /** Record an LLM request/response pair. */
    public void recordLLM(String request, String response, String model, long durationMs) {
        LLMRecord record = new LLMRecord(steps.size() + 1, request, response, model, durationMs);
        llmRecords.add(record);
        saveLLMRecord(record);
    }

    /** Finalize and save the session summary. */
    public void finish(boolean goalAchieved, String failReason) {
        this.endTime = System.currentTimeMillis();
        saveSummary(goalAchieved, failReason);
        DevLog.info("SESSION_LOG_SAVED", "id={}, steps={}, achieved={}, dir={}",
                sessionId, steps.size(), goalAchieved, sessionDir);
    }

    /** Get all recorded steps. */
    public List<StepRecord> getSteps() { return List.copyOf(steps); }

    /** Get the goal description. */
    public String getGoalDescription() { return goalDescription; }

    // ---- Persistence ----

    private void saveStep(StepRecord step) {
        try {
            Files.createDirectories(sessionDir.resolve("steps"));
            Path file = sessionDir.resolve("steps").resolve(
                    String.format("%03d.json", step.getStepNumber()));
            JsonObject json = new JsonObject();
            json.addProperty("step", step.getStepNumber());
            json.addProperty("timestamp", step.getTimestamp());
            json.add("worldStateBefore", step.getWorldStateBefore());
            json.addProperty("actionDescription", step.getActionDescription());
            json.addProperty("resultStatus", step.getResultStatus().name());
            if (step.getFailReason() != null) {
                json.addProperty("failReason", step.getFailReason());
            }
            json.addProperty("executionDurationMs", step.getExecutionDurationMs());
            json.add("worldStateAfter", step.getWorldStateAfter());

            JsonObject decision = new JsonObject();
            decision.addProperty("reasoning", step.getDecision().reasoning());
            decision.addProperty("actionJson", step.getDecision().actionJson());
            decision.addProperty("actionType", step.getDecision().actionType());
            json.add("decision", decision);

            Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DevLog.warn("SESSION_STEP_SAVE_FAIL", "step={}, err={}", step.getStepNumber(), e.getMessage());
        }
    }

    private void saveLLMRecord(LLMRecord record) {
        try {
            Files.createDirectories(sessionDir.resolve("llm"));
            Path reqFile = sessionDir.resolve("llm").resolve(
                    String.format("%03d-request.json", record.step()));
            Path resFile = sessionDir.resolve("llm").resolve(
                    String.format("%03d-response.json", record.step()));

            JsonObject reqJson = new JsonObject();
            reqJson.addProperty("step", record.step());
            reqJson.addProperty("model", record.model());
            reqJson.addProperty("request", record.request());
            reqJson.addProperty("durationMs", record.durationMs());
            Files.writeString(reqFile, GSON.toJson(reqJson), StandardCharsets.UTF_8);

            JsonObject resJson = new JsonObject();
            resJson.addProperty("step", record.step());
            resJson.addProperty("response", record.response());
            Files.writeString(resFile, GSON.toJson(resJson), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DevLog.warn("SESSION_LLM_SAVE_FAIL", "step={}, err={}", record.step(), e.getMessage());
        }
    }

    private void saveSummary(boolean goalAchieved, String failReason) {
        try {
            Files.createDirectories(sessionDir);
            JsonObject summary = new JsonObject();
            summary.addProperty("sessionId", sessionId);
            summary.addProperty("goal", goalDescription);
            summary.addProperty("startTime", startTime);
            summary.addProperty("endTime", endTime);
            summary.addProperty("durationMs", endTime - startTime);
            summary.addProperty("totalSteps", steps.size());
            summary.addProperty("totalLLMCalls", llmRecords.size());
            summary.addProperty("goalAchieved", goalAchieved);
            if (failReason != null) {
                summary.addProperty("failReason", failReason);
            }

            long successCount = steps.stream().filter(StepRecord::isSuccess).count();
            long failCount = steps.stream().filter(StepRecord::isFailed).count();
            summary.addProperty("successSteps", successCount);
            summary.addProperty("failedSteps", failCount);

            Path file = sessionDir.resolve("summary.json");
            Files.writeString(file, GSON.toJson(summary), StandardCharsets.UTF_8);

            // Save goal
            JsonObject goalJson = new JsonObject();
            goalJson.addProperty("description", goalDescription);
            Files.writeString(sessionDir.resolve("goal.json"), GSON.toJson(goalJson), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DevLog.warn("SESSION_SUMMARY_SAVE_FAIL", "err={}", e.getMessage());
        }
    }

    /** LLM request/response record. */
    private record LLMRecord(int step, String request, String response, String model, long durationMs) {}
}
