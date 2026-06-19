package com.aimod.ai;

import com.aimod.ai.action.Action;
import com.aimod.ai.action.GiveItemAction;
import com.aimod.ai.llm.LLMResponse;
import com.aimod.ai.llm.LLMService;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles task replanning — both incremental replan after action failure
 * and deficit-based replan after GiveItemAction shortfall.
 * Extracted from BotAIManager for single-responsibility.
 */
public class TaskReplanner {

    private final FakePlayer bot;
    private final TaskPlanner planner;
    private final TaskFeedback feedback;
    private final com.aimod.ai.llm.BotAIStateMachine stateMachine;
    private final BotMetrics metrics;

    private final java.util.concurrent.atomic.AtomicBoolean replanning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean cancelled = false; // Set by cancelTask() to abort background replan
    private final java.util.concurrent.atomic.AtomicInteger incrReplanCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int MAX_INCR_REPLAN = 5;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveUnknown = new java.util.concurrent.atomic.AtomicInteger(0);
    private final List<String> recentReplanAttempts = java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile String lastOwnerName = null;

    public TaskReplanner(FakePlayer bot, TaskPlanner planner, TaskFeedback feedback,
                         com.aimod.ai.llm.BotAIStateMachine stateMachine, BotMetrics metrics) {
        this.bot = bot;
        this.planner = planner;
        this.feedback = feedback;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
    }

    public boolean isReplanning() { return replanning.get(); }
    public boolean hasReplanned() { return incrReplanCount.get() > 0; }

    /**
     * Cancel any running replan. Called by FakePlayer.cancelTask().
     * Sets a flag that the background thread checks before applying results.
     */
    public void cancel() {
        cancelled = true;
        replanning.set(false);
    }

    /**
     * Incremental replan: ask LLM for next action after a failure.
     */
    public void incrementalReplan(Task task, String failedActionDesc, String ownerName) {
        this.lastOwnerName = ownerName;
        this.cancelled = false; // Reset cancelled flag for new replan
        if (!replanning.compareAndSet(false, true)) return;
        if (incrReplanCount.get() >= MAX_INCR_REPLAN) {
            task.setStatus(Task.TaskStatus.FAILED);
            metrics.recordTaskFailed();
            feedback.reportTaskFailed(task.getDescription(), "Exceeded retry limit after " + incrReplanCount.get() + " failures");
            planner.getPlanCache().markFailed(planner.getLastCommand());
            incrReplanCount.set(0);
            consecutiveUnknown.set(0);
            stateMachine.reset();
            return;
        }
        incrReplanCount.incrementAndGet();
        metrics.recordReplanTriggered();
        bot.getMovementController().getUnstuckDetector().setPaused(true);

        // Track this attempt for context
        recentReplanAttempts.add(failedActionDesc);
        synchronized (recentReplanAttempts) {
            while (recentReplanAttempts.size() > 10) recentReplanAttempts.remove(0);
        }

        // Assemble context with replan history
        int replanTokens = com.aimod.config.ModConfig.getCompactTriggerTokens();
        String ctx = com.aimod.ai.memory.ContextAssembler.assemble(
                bot.getMemoryStore(), task, replanTokens, recentReplanAttempts)
                + "\nFailed action: " + failedActionDesc
                + "\nCRITICAL: Do NOT repeat any action from 'Recent Failed Attempts'. Try a DIFFERENT approach."
                + "\nIf all approaches exhausted, use: {\"type\":\"say\",\"message\":\"无法完成任务\"}"
                + "\nTip: logs -> 4 planks in 2x2 grid. Use exact log type."
                + "\nRespond with ONE JSON action using these type names: "
                + "move_to, break_block, place_block, mine, gather, craft, give_item, interact, equip, attack, follow, say, wait."
                + "\nFor break_block/move_to/place_block: x, y, z are REQUIRED fields.";

        Thread t = new Thread(() -> {
            try {
                // Use cheap model for incremental replan (simpler task, less intelligence needed)
                String cheapModel = com.aimod.config.ModConfig.getCheapModelName();
                long llmStart = System.currentTimeMillis();
                LLMResponse resp = planner.getLlmService().sendPromptWithModel(ctx, cheapModel);
                long llmElapsed = System.currentTimeMillis() - llmStart;
                metrics.recordLlmCall(resp.isSuccess(), llmElapsed);

                // Check if task was cancelled while LLM was processing
                if (cancelled) {
                    replanning.set(false);
                    bot.getMovementController().getUnstuckDetector().setPaused(false);
                    return;
                }

                // Post all Task mutations to main thread to avoid concurrent modification
                var server = bot.level().getServer();
                if (server == null) {
                    replanning.set(false);
                    return;
                }

                server.execute(() -> {
                    // Double-check cancellation after posting to main thread
                    if (cancelled) {
                        replanning.set(false);
                        bot.getMovementController().getUnstuckDetector().setPaused(false);
                        return;
                    }
                    try {
                        if (resp.isSuccess()) {
                            var acts = planner.convertResponseToActions(resp, lastOwnerName);
                            // 过滤掉已失败的 action
                            synchronized (recentReplanAttempts) {
                                acts.removeIf(a -> recentReplanAttempts.stream()
                                        .anyMatch(attempt -> a.getDescription().equals(attempt)
                                                || a.getDescription().contains(attempt)));
                            }
                            if (!acts.isEmpty()) {
                                var next = acts.get(0);
                                consecutiveUnknown.set(0);
                                if (next.getDescription().equals(failedActionDesc)) {
                                    task.advanceToNextAction();
                                    incrReplanCount.set(0);
                                    metrics.recordReplanSucceeded();
                                    stateMachine.startExecuting();
                                } else {
                                    task.injectAction(next);
                                    task.advanceToNextAction();
                                    incrReplanCount.set(0);
                                    metrics.recordReplanSucceeded();
                                    DevLog.info("REPLAN_INCR", "injected={}", next.getDescription());
                                    stateMachine.startExecuting();
                                }
                            } else {
                                consecutiveUnknown.incrementAndGet();
                                String rawContent = resp.getRawResponse();
                                if (rawContent != null && !rawContent.isBlank()) {
                                    String truncated = rawContent.length() > 100 ? rawContent.substring(0, 100) + "..." : rawContent;
                                    synchronized (recentReplanAttempts) {
                                        recentReplanAttempts.add("BAD FORMAT: " + truncated);
                                        while (recentReplanAttempts.size() > 10) recentReplanAttempts.remove(0);
                                    }
                                }
                                DevLog.warn("REPLAN_UNKNOWN_CONSEQ", "count={}, failedAction={}",
                                        consecutiveUnknown.get(), failedActionDesc);
                                if (consecutiveUnknown.get() >= 3) {
                                    task.setStatus(Task.TaskStatus.FAILED);
                                    metrics.recordTaskFailed();
                                    feedback.reportTaskFailed(task.getDescription(),
                                            "LLM repeatedly generated unrecognized action types");
                                    planner.getPlanCache().markFailed(planner.getLastCommand());
                                    incrReplanCount.set(0);
                                    consecutiveUnknown.set(0);
                                } else if (consecutiveUnknown.get() >= 2) {
                                    task.advanceToNextAction();
                                    incrReplanCount.set(0);
                                    consecutiveUnknown.set(0);
                                    stateMachine.startExecuting();
                                    DevLog.info("REPLAN_SKIP_UNKNOWN", "advanced past stuck action");
                                }
                            }
                        }
                    } catch (Exception e) {
                        task.setStatus(Task.TaskStatus.FAILED);
                        metrics.recordTaskFailed();
                        feedback.reportTaskFailed(task.getDescription(), "Replan failed: " + e.getMessage());
                        stateMachine.reset();
                    } finally {
                        replanning.set(false);
                        bot.getMovementController().getUnstuckDetector().setPaused(false);
                    }
                });
            } catch (Exception e) {
                // LLM call failed — post failure to main thread
                var server2 = bot.level().getServer();
                if (server2 != null) {
                    server2.execute(() -> {
                        task.setStatus(Task.TaskStatus.FAILED);
                        metrics.recordTaskFailed();
                        feedback.reportTaskFailed(task.getDescription(), "Replan failed: " + e.getMessage());
                        replanning.set(false);
                        bot.getMovementController().getUnstuckDetector().setPaused(false);
                    });
                } else {
                    replanning.set(false);
                }
            }
        }, "AIMod-Incr-" + bot.getStringUUID().substring(0, 8));
        t.setDaemon(true);
        t.start();
    }

    /**
     * Check task for GiveItemAction deficits and trigger replan if needed.
     */
    public void checkDeficitsAndReplan(Task task) {
        if (!replanning.compareAndSet(false, true)) return;

        Map<String, Integer> deficits = new LinkedHashMap<>();
        String targetPlayer = null;
        for (Action action : task.getActions()) {
            if (action instanceof GiveItemAction g) {
                if (g.getDeficit() > 0) {
                    deficits.merge(g.getItemId(), g.getDeficit(), Integer::sum);
                    targetPlayer = g.getTargetPlayerName();
                }
            }
        }

        if (deficits.isEmpty()) return;

        DevLog.info("REPLAN_DEFICITS", "deficits={}, targetPlayer={}", deficits, targetPlayer);

        StringBuilder cmd = new StringBuilder("You previously tried to give items but didn't have enough. ");
        cmd.append("You still need to collect: ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : deficits.entrySet()) {
            if (!first) cmd.append(", ");
            cmd.append(entry.getValue()).append(" more ").append(entry.getKey());
            first = false;
        }
        if (targetPlayer != null && !targetPlayer.isBlank()) {
            cmd.append(". Give them to ").append(targetPlayer);
        }
        cmd.append(". Go collect them now.");

        String replanCommand = cmd.toString();
        DevLog.info("REPLAN_SCHEDULED", "command={}", DevLog.compact(replanCommand));

        Thread replanThread = new Thread(() -> {
            try {
                Task newTask = planner.parseCommand(replanCommand, lastOwnerName, stateMachine);
                if (newTask != null && newTask.getActionCount() > 0) {
                    if (bot.level().getServer() != null) {
                        bot.level().getServer().execute(() -> {
                            replanning.set(false);
                            reset(); // Reset counters for new task
                            DevLog.info("REPLAN_TASK_ASSIGNED", "actionCount={}", newTask.getActionCount());
                            feedback.reportTaskStart(replanCommand);
                            bot.setCurrentTask(newTask);
                            stateMachine.setTaskInfo(newTask.getDescription(), newTask.getActionCount());
                            stateMachine.startExecuting();
                            // Note: executeTask is called by the caller (BotAIManager)
                        });
                    } else {
                        replanning.set(false);
                    }
                } else {
                    replanning.set(false);
                    stateMachine.reset();
                    DevLog.warn("REPLAN_NO_ACTIONS", "LLM returned no actions for replanning");
                }
            } catch (Exception e) {
                replanning.set(false);
                DevLog.error("REPLAN_FAILED", "replanning exception", e);
            }
        }, "AIMod-Replan-" + bot.getStringUUID().substring(0, 8));
        replanThread.setDaemon(true);
        replanThread.start();
    }

    /**
     * Reset replan counters (called when task completes or is cancelled).
     */
    public void reset() {
        cancelled = false;
        incrReplanCount.set(0);
        consecutiveUnknown.set(0);
        recentReplanAttempts.clear();
    }
}
