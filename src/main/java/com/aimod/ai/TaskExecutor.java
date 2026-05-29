package com.aimod.ai;

import com.aimod.ai.action.Action;
import com.aimod.ai.action.GiveItemAction;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

/**
 * Handles task execution — running actions, checking completion,
 * and managing state transitions.
 * Extracted from BotAIManager for single-responsibility.
 */
public class TaskExecutor {

    private final FakePlayer bot;
    private final TaskPlanner planner;
    private final TaskReplanner replanner;
    private final TaskFeedback feedback;
    private final com.aimod.ai.llm.BotAIStateMachine stateMachine;
    private final BotMetrics metrics;

    public TaskExecutor(FakePlayer bot, TaskPlanner planner, TaskReplanner replanner,
                        TaskFeedback feedback, com.aimod.ai.llm.BotAIStateMachine stateMachine,
                        BotMetrics metrics) {
        this.bot = bot;
        this.planner = planner;
        this.replanner = replanner;
        this.feedback = feedback;
        this.stateMachine = stateMachine;
        this.metrics = metrics;
    }

    /**
     * Execute the current action in a task.
     * Handles action lifecycle: PENDING → IN_PROGRESS → COMPLETED/FAILED.
     */
    public void executeTask(Task task) {
        if (task == null) return;

        // If task already completed, check deficits
        if (task.isCompleted()) {
            replanner.checkDeficitsAndReplan(task);
            return;
        }

        Action currentAction = task.getCurrentAction();
        if (currentAction != null) {
            stateMachine.setCurrentActionDesc(currentAction.getDescription());
        }
        if (currentAction == null) {
            task.setStatus(Task.TaskStatus.COMPLETED);
            stateMachine.complete();
            metrics.recordTaskCompleted();
            feedback.reportTaskComplete(task.getDescription());
            replanner.checkDeficitsAndReplan(task);
            return;
        }

        // Execute current action
        if (currentAction.getStatus() == Action.ActionStatus.PENDING) {
            if (currentAction.canExecute(bot)) {
                currentAction.execute(bot);
            } else {
                currentAction.setStatus(Action.ActionStatus.FAILED);
                String reason = TaskPlanner.getActionFailReason(currentAction, "Cannot execute");
                feedback.reportActionFailed(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription(),
                        reason);
            }
        } else if (currentAction.getStatus() == Action.ActionStatus.IN_PROGRESS) {
            currentAction.execute(bot);
        }

        // Check if action completed
        if (currentAction.isComplete(bot)) {
            if (currentAction.getStatus() == Action.ActionStatus.COMPLETED) {
                metrics.recordActionSucceeded();
                feedback.reportActionComplete(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription());
                stateMachine.actionCompleted();
                task.advanceToNextAction();
                // Check if task is now complete
                if (task.isCompleted()) {
                    stateMachine.complete();
                    // Cache validated plan (only if no replanning occurred)
                    if (planner.getLastCommand() != null && !planner.getLastCommand().isBlank()
                            && !replanner.hasReplanned() && planner.getLastCachedActions() != null) {
                        planner.getPlanCache().store(planner.getLastCommand(), planner.getLastCachedActions(), true);
                        DevLog.info("PLAN_CACHE_STORE_DEFERRED", "command={}, actions={}",
                                planner.getLastCommand(), planner.getLastCachedActions().size());
                    }
                    planner.clearLastCachedActions();
                    replanner.checkDeficitsAndReplan(task);
                }
            } else {
                // Action failed — check if replan already in progress
                metrics.recordActionFailed();
                if (!replanner.isReplanning()) {
                    boolean isGiveItemPartial = (currentAction instanceof GiveItemAction g && g.getGivenCount() > 0);
                    String reason = TaskPlanner.getActionFailReason(currentAction, "Action failed");
                    feedback.reportActionFailed(
                            task.getCurrentActionIndex() + 1,
                            task.getActionCount(),
                            currentAction.getDescription(),
                            reason);
                    if (isGiveItemPartial) {
                        DevLog.info("GIVE_ITEM_PARTIAL_SKIP", "continuing task after partial give");
                        task.advanceToNextAction();
                    } else {
                        stateMachine.requestReplan();
                        replanner.incrementalReplan(task, currentAction.getDescription(), planner.getLastOwnerName());
                    }
                }
            }
        }
    }

    /**
     * Update task — compact memory periodically, then execute.
     */
    public void updateTask(Task task) {
        if (task == null || task.isCompleted()) return;

        // Auto-compact memory when approaching token budget (every 100 ticks ≈ 5s)
        if (bot.getServer() != null && bot.getServer().getTickCount() % 100 == 0) {
            int estTokens = com.aimod.ai.memory.ContextAssembler.estimateTotalTokens(bot.getMemoryStore());
            int triggerTokens = com.aimod.config.ModConfig.getCompactTriggerTokens();
            if (estTokens > triggerTokens) {
                bot.getMemoryStore().compactToTokenTarget(triggerTokens);
            }
        }

        executeTask(task);
    }
}
