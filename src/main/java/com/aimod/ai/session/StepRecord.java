package com.aimod.ai.session;

import com.aimod.ai.action.Action;
import com.google.gson.JsonObject;

/**
 * Records a single step in an agentic execution loop.
 * Captures the world state, LLM decision, action execution, and result.
 *
 * <p>Used for session logging ("packet capture") and replay-based testing.</p>
 */
public class StepRecord {

    private final int stepNumber;
    private final long timestamp;
    private final JsonObject worldStateBefore;
    private final LLMDecision decision;
    private final String actionDescription;
    private final Action.ActionStatus resultStatus;
    private final String failReason;
    private final long executionDurationMs;
    private final JsonObject worldStateAfter;

    public StepRecord(int stepNumber, JsonObject worldStateBefore,
                      LLMDecision decision, String actionDescription,
                      Action.ActionStatus resultStatus, String failReason,
                      long executionDurationMs, JsonObject worldStateAfter) {
        this.stepNumber = stepNumber;
        this.timestamp = System.currentTimeMillis();
        this.worldStateBefore = worldStateBefore;
        this.decision = decision;
        this.actionDescription = actionDescription;
        this.resultStatus = resultStatus;
        this.failReason = failReason;
        this.executionDurationMs = executionDurationMs;
        this.worldStateAfter = worldStateAfter;
    }

    public int getStepNumber() { return stepNumber; }
    public long getTimestamp() { return timestamp; }
    public JsonObject getWorldStateBefore() { return worldStateBefore; }
    public LLMDecision getDecision() { return decision; }
    public String getActionDescription() { return actionDescription; }
    public Action.ActionStatus getResultStatus() { return resultStatus; }
    public String getFailReason() { return failReason; }
    public long getExecutionDurationMs() { return executionDurationMs; }
    public JsonObject getWorldStateAfter() { return worldStateAfter; }

    public boolean isSuccess() { return resultStatus == Action.ActionStatus.COMPLETED; }
    public boolean isFailed() { return resultStatus == Action.ActionStatus.FAILED; }

    /** LLM decision record: reasoning + chosen action JSON. */
    public record LLMDecision(String reasoning, String actionJson, String actionType) {}
}
