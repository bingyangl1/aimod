package com.aimod.ai;

import com.aimod.ai.action.Action;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

import java.util.List;

/**
 * Main AI coordinator for a bot. Delegates to specialized components:
 * - TaskPlanner: LLM interaction, action parsing, plan cache
 * - TaskExecutor: action execution loop, state transitions
 * - TaskReplanner: incremental replan, deficit replan
 *
 * Maintains the original public API for backward compatibility.
 */
public class BotAIManager {
    private final FakePlayer bot;
    private final TaskFeedback feedback;
    private final WorldScanner worldScanner;
    private final com.aimod.ai.llm.BotAIStateMachine stateMachine;
    private final TaskPlanner planner;
    private final TaskExecutor executor;
    private final TaskReplanner replanner;
    private final BotMetrics metrics;

    public BotAIManager(FakePlayer bot) {
        this.bot = bot;
        this.feedback = new TaskFeedback(bot);
        this.worldScanner = new WorldScanner(bot);
        this.stateMachine = new com.aimod.ai.llm.BotAIStateMachine();
        this.metrics = new BotMetrics();
        this.planner = new TaskPlanner(bot, feedback, metrics);
        this.replanner = new TaskReplanner(bot, planner, feedback, stateMachine, metrics);
        this.executor = new TaskExecutor(bot, planner, replanner, feedback, stateMachine, metrics);
    }

    // === Public API (unchanged) ===

    public com.aimod.ai.llm.BotAIStateMachine getStateMachine() { return stateMachine; }
    public TaskFeedback getFeedback() { return feedback; }
    public WorldScanner getWorldScanner() { return worldScanner; }
    public BotMetrics getMetrics() { return metrics; }
    public String getMemoryStats() { return bot.getMemoryStore().getStats(); }

    /**
     * Parse a natural language command into a Task.
     */
    public Task parseCommand(String naturalLanguageCommand) {
        return parseCommand(naturalLanguageCommand, null);
    }

    /**
     * Parse a natural language command into a Task.
     */
    public Task parseCommand(String naturalLanguageCommand, String ownerName) {
        return planner.parseCommand(naturalLanguageCommand, ownerName, stateMachine);
    }

    /**
     * Execute the current action in a task.
     */
    public void executeTask(Task task) {
        executor.executeTask(task);
    }

    /**
     * Update task — compact memory periodically, then execute.
     */
    public void updateTask(Task task) {
        executor.updateTask(task);
    }

    /**
     * Convert cached action JSON strings to Action objects.
     */
    public List<Action> convertCachedToActions(List<String> actionJsons, String ownerName) {
        return planner.convertCachedToActions(actionJsons, ownerName);
    }

    /**
     * Cancel any running replan. Called by FakePlayer.cancelTask().
     */
    public void cancelReplan() {
        replanner.cancel();
    }
}
