package com.aimod.ai.llm;

import com.aimod.util.DevLog;

/**
 * Explicit state machine for AI bot task lifecycle.
 * Tracks state transitions and provides pause/resume/replan hooks.
 */
public class BotAIStateMachine {

    public enum State {
        IDLE,          // No task, waiting for command
        PLANNING,      // LLM request in progress
        EXECUTING,     // Running action sequence
        WAITING,       // Waiting for resources (smelting, growth, etc.)
        PAUSED,        // Manually paused
        REPLAN,        // Re-planning after partial failure
        COMPLETED,     // Task finished successfully
        FAILED         // Task failed unrecoverably
    }

    private State current = State.IDLE;
    private State previous = State.IDLE;
    private String taskDescription;
    private int actionsDone;
    private int actionsTotal;
    private long stateEnteredAt;

    public synchronized void transition(State target) {
        if (target == current) return;
        previous = current;
        current = target;
        stateEnteredAt = System.currentTimeMillis();
        DevLog.info("AI_STATE", "{} -> {}", previous, current);
    }

    public synchronized void startPlanning(String task, int totalActions) {
        taskDescription = task;
        actionsTotal = totalActions;
        actionsDone = 0;
        transition(State.PLANNING);
    }

    public synchronized void startExecuting() { transition(State.EXECUTING); }
    public synchronized void actionCompleted() { actionsDone++; }
    public synchronized void pause() { if (current == State.EXECUTING) transition(State.PAUSED); }
    public synchronized void resume() { if (current == State.PAUSED) transition(previous == State.PAUSED ? State.EXECUTING : previous); }
    public synchronized void requestReplan() { transition(State.REPLAN); }
    public synchronized void complete() { transition(State.COMPLETED); }
    public synchronized void fail() { transition(State.FAILED); }
    public synchronized void reset() { transition(State.IDLE); actionsDone = 0; taskDescription = null; }

    // ---- queries ----
    public State getCurrent() { return current; }
    public State getPrevious() { return previous; }
    public boolean isActive() { return current != State.IDLE && current != State.COMPLETED && current != State.FAILED; }
    public boolean canAcceptTask() { return current == State.IDLE || current == State.COMPLETED || current == State.FAILED; }
    public int getActionsDone() { return actionsDone; }
    public int getActionsTotal() { return actionsTotal; }
    public String getTaskDescription() { return taskDescription; }
    public long getStateElapsedMs() { return System.currentTimeMillis() - stateEnteredAt; }
    public float getProgress() { return actionsTotal > 0 ? (float) actionsDone / actionsTotal : 0f; }
}
