package com.aimod.ai;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight metrics collector for bot operations.
 * Thread-safe counters for LLM calls, tasks, actions, and replans.
 * Supports JSON persistence for cross-restart survival.
 */
public class BotMetrics {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // LLM call statistics
    private final AtomicInteger llmCalls = new AtomicInteger(0);
    private final AtomicInteger llmSuccesses = new AtomicInteger(0);
    private final AtomicInteger llmFailures = new AtomicInteger(0);
    private final AtomicLong llmTotalMs = new AtomicLong(0);
    private final AtomicLong llmMaxMs = new AtomicLong(0);

    // Task statistics
    private final AtomicInteger tasksStarted = new AtomicInteger(0);
    private final AtomicInteger tasksCompleted = new AtomicInteger(0);
    private final AtomicInteger tasksFailed = new AtomicInteger(0);

    // Action statistics
    private final AtomicInteger actionsExecuted = new AtomicInteger(0);
    private final AtomicInteger actionsSucceeded = new AtomicInteger(0);
    private final AtomicInteger actionsFailed = new AtomicInteger(0);

    // Replan statistics
    private final AtomicInteger replansTriggered = new AtomicInteger(0);
    private final AtomicInteger replansSucceeded = new AtomicInteger(0);

    // === Record methods ===

    public void recordLlmCall(boolean success, long elapsedMs) {
        llmCalls.incrementAndGet();
        if (success) llmSuccesses.incrementAndGet();
        else llmFailures.incrementAndGet();
        llmTotalMs.addAndGet(elapsedMs);
        updateMax(llmMaxMs, elapsedMs);
    }

    public void recordTaskStarted() {
        tasksStarted.incrementAndGet();
    }

    public void recordTaskCompleted() {
        tasksCompleted.incrementAndGet();
    }

    public void recordTaskFailed() {
        tasksFailed.incrementAndGet();
    }

    public void recordActionExecuted() {
        actionsExecuted.incrementAndGet();
    }

    public void recordActionSucceeded() {
        actionsSucceeded.incrementAndGet();
    }

    public void recordActionFailed() {
        actionsFailed.incrementAndGet();
    }

    public void recordReplanTriggered() {
        replansTriggered.incrementAndGet();
    }

    public void recordReplanSucceeded() {
        replansSucceeded.incrementAndGet();
    }

    // === Query methods ===

    public int getLlmCalls() { return llmCalls.get(); }
    public int getLlmSuccesses() { return llmSuccesses.get(); }
    public int getLlmFailures() { return llmFailures.get(); }
    public long getLlmTotalMs() { return llmTotalMs.get(); }
    public long getLlmMaxMs() { return llmMaxMs.get(); }

    public int getTasksStarted() { return tasksStarted.get(); }
    public int getTasksCompleted() { return tasksCompleted.get(); }
    public int getTasksFailed() { return tasksFailed.get(); }

    public int getActionsExecuted() { return actionsExecuted.get(); }
    public int getActionsSucceeded() { return actionsSucceeded.get(); }
    public int getActionsFailed() { return actionsFailed.get(); }

    public int getReplansTriggered() { return replansTriggered.get(); }
    public int getReplansSucceeded() { return replansSucceeded.get(); }

    public long getLlmAvgMs() {
        int calls = llmCalls.get();
        return calls > 0 ? llmTotalMs.get() / calls : 0;
    }

    public double getTaskSuccessRate() {
        int total = tasksStarted.get();
        return total > 0 ? (double) tasksCompleted.get() / total : 0.0;
    }

    public double getActionSuccessRate() {
        int total = actionsExecuted.get();
        return total > 0 ? (double) actionsSucceeded.get() / total : 0.0;
    }

    // === Format ===

    /**
     * Format metrics as human-readable summary.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();
        sb.append("§e=== Bot Metrics ===§r\n");

        sb.append("§6LLM:§r ").append(llmCalls.get()).append(" calls");
        if (llmCalls.get() > 0) {
            sb.append(" (").append(llmSuccesses.get()).append(" ok, ").append(llmFailures.get()).append(" fail)");
            sb.append(" avg=").append(getLlmAvgMs()).append("ms");
            sb.append(" max=").append(llmMaxMs.get()).append("ms");
        }
        sb.append("\n");

        sb.append("§6Tasks:§r ").append(tasksStarted.get()).append(" started");
        if (tasksStarted.get() > 0) {
            sb.append(" (").append(tasksCompleted.get()).append(" done, ").append(tasksFailed.get()).append(" fail)");
            sb.append(" rate=").append(String.format("%.0f%%", getTaskSuccessRate() * 100));
        }
        sb.append("\n");

        sb.append("§6Actions:§r ").append(actionsExecuted.get()).append(" executed");
        if (actionsExecuted.get() > 0) {
            sb.append(" (").append(actionsSucceeded.get()).append(" ok, ").append(actionsFailed.get()).append(" fail)");
            sb.append(" rate=").append(String.format("%.0f%%", getActionSuccessRate() * 100));
        }
        sb.append("\n");

        sb.append("§6Replans:§r ").append(replansTriggered.get()).append(" triggered");
        if (replansTriggered.get() > 0) {
            sb.append(" (").append(replansSucceeded.get()).append(" succeeded)");
        }

        return sb.toString();
    }

    // === Helper ===

    private void updateMax(AtomicLong atomic, long value) {
        long current;
        do {
            current = atomic.get();
            if (value <= current) break;
        } while (!atomic.compareAndSet(current, value));
    }

    // === Persistence ===

    /** Serializable snapshot for JSON persistence. */
    public static class MetricsSnapshot {
        public int llmCalls, llmSuccesses, llmFailures;
        public long llmTotalMs, llmMaxMs;
        public int tasksStarted, tasksCompleted, tasksFailed;
        public int actionsExecuted, actionsSucceeded, actionsFailed;
        public int replansTriggered, replansSucceeded;
        public long savedAt;
    }

    /**
     * Save metrics to a JSON file.
     */
    public void save(Path file) {
        try {
            MetricsSnapshot snapshot = toSnapshot();
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(snapshot),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            DevLog.info("METRICS_SAVE", "file={}", file.getFileName());
        } catch (IOException e) {
            DevLog.warn("METRICS_SAVE_FAIL", "err={}", e.getMessage());
        }
    }

    /**
     * Load metrics from a JSON file.
     */
    public void load(Path file) {
        if (!Files.exists(file)) return;
        try {
            String json = Files.readString(file);
            MetricsSnapshot snapshot = GSON.fromJson(json, MetricsSnapshot.class);
            if (snapshot != null) {
                fromSnapshot(snapshot);
                DevLog.info("METRICS_LOAD", "file={}, llmCalls={}", file.getFileName(), llmCalls.get());
            }
        } catch (Exception e) {
            DevLog.warn("METRICS_LOAD_FAIL", "err={}", e.getMessage());
        }
    }

    private MetricsSnapshot toSnapshot() {
        MetricsSnapshot s = new MetricsSnapshot();
        s.llmCalls = llmCalls.get();
        s.llmSuccesses = llmSuccesses.get();
        s.llmFailures = llmFailures.get();
        s.llmTotalMs = llmTotalMs.get();
        s.llmMaxMs = llmMaxMs.get();
        s.tasksStarted = tasksStarted.get();
        s.tasksCompleted = tasksCompleted.get();
        s.tasksFailed = tasksFailed.get();
        s.actionsExecuted = actionsExecuted.get();
        s.actionsSucceeded = actionsSucceeded.get();
        s.actionsFailed = actionsFailed.get();
        s.replansTriggered = replansTriggered.get();
        s.replansSucceeded = replansSucceeded.get();
        s.savedAt = System.currentTimeMillis();
        return s;
    }

    private void fromSnapshot(MetricsSnapshot s) {
        llmCalls.set(s.llmCalls);
        llmSuccesses.set(s.llmSuccesses);
        llmFailures.set(s.llmFailures);
        llmTotalMs.set(s.llmTotalMs);
        llmMaxMs.set(s.llmMaxMs);
        tasksStarted.set(s.tasksStarted);
        tasksCompleted.set(s.tasksCompleted);
        tasksFailed.set(s.tasksFailed);
        actionsExecuted.set(s.actionsExecuted);
        actionsSucceeded.set(s.actionsSucceeded);
        actionsFailed.set(s.actionsFailed);
        replansTriggered.set(s.replansTriggered);
        replansSucceeded.set(s.replansSucceeded);
    }
}
