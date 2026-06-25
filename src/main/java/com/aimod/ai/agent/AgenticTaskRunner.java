package com.aimod.ai.agent;

import com.aimod.ai.TaskPlanner;
import com.aimod.ai.llm.LLMService;
import com.aimod.ai.session.SessionLog;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

/**
 * Runs an agentic task on a FakePlayer using the AgentLoop.
 *
 * <p>This is the integration layer between the new Agentic architecture
 * and the existing FakePlayer system. It replaces the old TaskPlanner→Task→TaskExecutor
 * pipeline with Goal→AgentLoop→ActionExecutor.</p>
 *
 * <p>Usage:
 * <pre>
 * AgenticTaskRunner runner = new AgenticTaskRunner(bot, planner, llmService, modelName);
 * runner.start("制作一套钻石装备", ownerName);
 * // Then call runner.tick() from FakePlayer.tick() each server tick
 * </pre>
 */
public class AgenticTaskRunner {

    private final FakePlayer bot;
    private final TaskPlanner planner;
    private final LLMService llmService;
    private final String modelName;

    private AgentLoop agentLoop;
    private Goal goal;
    private SessionLog sessionLog;
    private volatile boolean running = false;
    private volatile boolean completed = false;
    private Thread agentThread;

    public AgenticTaskRunner(FakePlayer bot, TaskPlanner planner, LLMService llmService, String modelName) {
        this.bot = bot;
        this.planner = planner;
        this.llmService = llmService;
        this.modelName = modelName;
    }

    /**
     * Start an agentic task in a background thread.
     * Equivalent to FakePlayer.assignTask() but uses AgentLoop.
     */
    public void start(String command, String ownerName) {
        if (running) {
            DevLog.warn("AGENTIC_ALREADY_RUNNING", "bot={}", bot.getStringUUID());
            return;
        }

        // Create goal from command
        this.goal = new Goal(command);
        this.sessionLog = new SessionLog(command);

        // Create action executor
        DefaultActionExecutor executor = new DefaultActionExecutor(planner);

        // Create agent loop
        this.agentLoop = new AgentLoop(goal, llmService, modelName, executor, sessionLog);

        // Run in background thread (same pattern as assignTask)
        this.running = true;
        this.completed = false;

        agentThread = new Thread(() -> {
            try {
                DevLog.info("AGENTIC_START", "bot={}, command={}", bot.getStringUUID(), DevLog.compact(command));

                Goal.GoalStatus status = agentLoop.run(bot);

                DevLog.info("AGENTIC_END", "bot={}, status={}, steps={}",
                        bot.getStringUUID(), status, sessionLog.getSteps().size());

                completed = true;
            } catch (Exception e) {
                DevLog.error("AGENTIC_ERROR", "bot=" + bot.getStringUUID(), e);
                goal.setStatus(Goal.GoalStatus.FAILED);
                goal.setFailReason("Exception: " + e.getMessage());
                completed = true;
            } finally {
                running = false;
            }
        }, "AIMod-Agent-" + bot.getName().getString());
        agentThread.setDaemon(true);
        agentThread.start();
    }

    /**
     * Check if the agent loop is still running.
     */
    public boolean isRunning() { return running; }

    /**
     * Check if the agent loop has completed.
     */
    public boolean isCompleted() { return completed; }

    /**
     * Get the current goal.
     */
    public Goal getGoal() { return goal; }

    /**
     * Get the session log (for replay).
     */
    public SessionLog getSessionLog() { return sessionLog; }

    /**
     * Cancel the running agent loop.
     */
    public void cancel() {
        if (agentThread != null) {
            agentThread.interrupt();
        }
        running = false;
        if (goal != null) {
            goal.setStatus(Goal.GoalStatus.FAILED);
            goal.setFailReason("Cancelled by user");
        }
        if (sessionLog != null) {
            sessionLog.finish(false, "Cancelled by user");
        }
        DevLog.info("AGENTIC_CANCELLED", "bot={}", bot.getStringUUID());
    }
}
