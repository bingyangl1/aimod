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

    private volatile boolean replanning = false;
    private int incrReplanCount = 0;
    private static final int MAX_INCR_REPLAN = 5;
    private int consecutiveUnknown = 0;
    private final List<String> recentReplanAttempts = new ArrayList<>();
    private String lastOwnerName = null;

    public TaskReplanner(FakePlayer bot, TaskPlanner planner, TaskFeedback feedback,
                         com.aimod.ai.llm.BotAIStateMachine stateMachine, BotMetrics metrics) {
        this.bot = bot;
        this.planner = planner;
        this.feedback = feedback;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
    }

    public boolean isReplanning() { return replanning; }
    public boolean hasReplanned() { return incrReplanCount > 0; }

    /**
     * Incremental replan: ask LLM for next action after a failure.
     */
    public void incrementalReplan(Task task, String failedActionDesc, String ownerName) {
        this.lastOwnerName = ownerName;
        if (replanning) return;
        if (incrReplanCount >= MAX_INCR_REPLAN) {
            task.setStatus(Task.TaskStatus.FAILED);
            feedback.reportTaskFailed(task.getDescription(), "Exceeded retry limit after " + incrReplanCount + " failures");
            planner.getPlanCache().markFailed(planner.getLastCommand());
            incrReplanCount = 0;
            consecutiveUnknown = 0;
            return;
        }
        incrReplanCount++;
        replanning = true;
        metrics.recordReplanTriggered();
        bot.getMovementController().getUnstuckDetector().setPaused(true);

        // Track this attempt for context
        recentReplanAttempts.add(failedActionDesc);
        while (recentReplanAttempts.size() > 10) recentReplanAttempts.remove(0);

        // Assemble context with replan history
        int replanTokens = com.aimod.config.ModConfig.getCompactTriggerTokens();
        String ctx = com.aimod.ai.memory.ContextAssembler.assemble(
                bot.getMemoryStore(), task, replanTokens, recentReplanAttempts)
                + "\nFailed action: " + failedActionDesc
                + "\nTip: logs -> 4 planks in 2x2 grid. Use exact log type."
                + "\nRespond with ONE JSON action using these type names: "
                + "move_to, break_block, place_block, mine, gather, craft, give_item, interact, equip, attack, follow, say, wait.";

        Thread t = new Thread(() -> {
            try {
                // Use cheap model for incremental replan (simpler task, less intelligence needed)
                String cheapModel = com.aimod.config.ModConfig.getCheapModelName();
                long llmStart = System.currentTimeMillis();
                LLMResponse resp = planner.getLlmService().sendPromptWithModel(ctx, cheapModel);
                long llmElapsed = System.currentTimeMillis() - llmStart;
                metrics.recordLlmCall(resp.isSuccess(), llmElapsed);
                if (resp.isSuccess()) {
                    var acts = planner.convertResponseToActions(resp, lastOwnerName);
                    if (!acts.isEmpty()) {
                        var next = acts.get(0);
                        consecutiveUnknown = 0;
                        if (next.getDescription().equals(failedActionDesc)) {
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            metrics.recordReplanSucceeded();
                            stateMachine.startExecuting();
                        } else {
                            task.injectAction(next);
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                            metrics.recordReplanSucceeded();
                            DevLog.info("REPLAN_INCR", "injected={}", next.getDescription());
                            stateMachine.startExecuting();
                        }
                    } else {
                        consecutiveUnknown++;
                        String rawContent = resp.getRawResponse();
                        if (rawContent != null && !rawContent.isBlank()) {
                            String truncated = rawContent.length() > 100 ? rawContent.substring(0, 100) + "..." : rawContent;
                            recentReplanAttempts.add("BAD FORMAT: " + truncated);
                            while (recentReplanAttempts.size() > 10) recentReplanAttempts.remove(0);
                        }
                        DevLog.warn("REPLAN_UNKNOWN_CONSEQ", "count={}, failedAction={}",
                                consecutiveUnknown, failedActionDesc);
                        if (consecutiveUnknown >= 3) {
                            task.setStatus(Task.TaskStatus.FAILED);
                            feedback.reportTaskFailed(task.getDescription(),
                                    "LLM repeatedly generated unrecognized action types");
                            planner.getPlanCache().markFailed(planner.getLastCommand());
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                        } else if (consecutiveUnknown >= 2) {
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                            stateMachine.startExecuting();
                            DevLog.info("REPLAN_SKIP_UNKNOWN", "advanced past stuck action");
                        }
                    }
                }
            } catch (Exception e) {
                task.setStatus(Task.TaskStatus.FAILED);
                feedback.reportTaskFailed(task.getDescription(), "Replan failed: " + e.getMessage());
            } finally {
                replanning = false;
                bot.getMovementController().getUnstuckDetector().setPaused(false);
            }
        }, "AIMod-Incr-" + bot.getStringUUID().substring(0, 8));
        t.setDaemon(true);
        t.start();
    }

    /**
     * Check task for GiveItemAction deficits and trigger replan if needed.
     */
    public void checkDeficitsAndReplan(Task task) {
        if (replanning) return;

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

        replanning = true;
        Thread replanThread = new Thread(() -> {
            try {
                Task newTask = planner.parseCommand(replanCommand, null, stateMachine);
                if (newTask != null && newTask.getActionCount() > 0) {
                    if (bot.level().getServer() != null) {
                        bot.level().getServer().execute(() -> {
                            replanning = false;
                            DevLog.info("REPLAN_TASK_ASSIGNED", "actionCount={}", newTask.getActionCount());
                            feedback.reportTaskStart(replanCommand);
                            bot.setCurrentTask(newTask);
                            // Note: executeTask is called by the caller (BotAIManager)
                        });
                    } else {
                        replanning = false;
                    }
                } else {
                    replanning = false;
                    DevLog.warn("REPLAN_NO_ACTIONS", "LLM returned no actions for replanning");
                }
            } catch (Exception e) {
                replanning = false;
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
        incrReplanCount = 0;
        consecutiveUnknown = 0;
        recentReplanAttempts.clear();
    }
}
