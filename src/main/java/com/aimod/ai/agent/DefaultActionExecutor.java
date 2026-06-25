package com.aimod.ai.agent;

import com.aimod.ai.TaskPlanner;
import com.aimod.ai.action.Action;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

/**
 * Default ActionExecutor that bridges to the existing Action system.
 *
 * <p>Uses TaskPlanner to parse JSON into Action objects, and executes
 * them using the existing canExecute/execute/isComplete lifecycle.</p>
 */
public class DefaultActionExecutor implements AgentLoop.ActionExecutor {

    private final TaskPlanner planner;

    public DefaultActionExecutor(TaskPlanner planner) {
        this.planner = planner;
    }

    @Override
    public Action parseAction(String actionJson) {
        try {
            return planner.parseSingleAction(actionJson);
        } catch (Exception e) {
            DevLog.warn("AGENT_PARSE_FAILED", "json={}, err={}", actionJson, e.getMessage());
            return null;
        }
    }

    @Override
    public AgentLoop.ActionResult execute(Action action, FakePlayer bot) {
        try {
            // Check preconditions
            if (!action.canExecute(bot)) {
                String reason = action.getFailReason() != null ? action.getFailReason() : "Cannot execute";
                return AgentLoop.ActionResult.failure(reason);
            }

            // Execute the action (tick until complete or failed)
            action.execute(bot);

            // Check result
            if (action.getStatus() == Action.ActionStatus.COMPLETED) {
                return AgentLoop.ActionResult.success();
            } else if (action.getStatus() == Action.ActionStatus.FAILED) {
                String reason = action.getFailReason() != null ? action.getFailReason() : "Action failed";
                return AgentLoop.ActionResult.failure(reason);
            }

            // Action is IN_PROGRESS — needs multiple ticks
            // For simplicity, we execute ticks until complete (with a timeout)
            int maxTicks = 400; // 20 seconds at 20 TPS
            for (int tick = 0; tick < maxTicks; tick++) {
                action.execute(bot);
                if (action.isComplete(bot)) {
                    if (action.getStatus() == Action.ActionStatus.COMPLETED) {
                        return AgentLoop.ActionResult.success();
                    } else {
                        String reason = action.getFailReason() != null ? action.getFailReason() : "Action failed";
                        return AgentLoop.ActionResult.failure(reason);
                    }
                }
            }

            return AgentLoop.ActionResult.failure("Action timed out after " + maxTicks + " ticks");
        } catch (Exception e) {
            DevLog.error("AGENT_EXECUTE_ERROR", "action=" + action.getDescription(), e);
            return AgentLoop.ActionResult.failure("Exception: " + e.getMessage());
        }
    }
}
