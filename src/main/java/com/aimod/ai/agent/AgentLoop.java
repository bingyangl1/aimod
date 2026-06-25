package com.aimod.ai.agent;

import com.aimod.ai.action.Action;
import com.aimod.ai.llm.LLMResponse;
import com.aimod.ai.llm.LLMResponseParser;
import com.aimod.ai.llm.LLMService;
import com.aimod.ai.session.SessionLog;
import com.aimod.ai.session.StepRecord;
import com.aimod.ai.session.WorldObserver;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Agentic execution loop — the core of the new architecture.
 *
 * <p>Replaces the current "one-shot planning + sequential execution" model
 * with an "observe → think → act → repeat" loop, inspired by OpenCode's
 * agent runner pattern.</p>
 *
 * <p>Key differences from current architecture:
 * <ul>
 *   <li>LLM is called at EVERY step, not just once at the beginning</li>
 *   <li>LLM sees the LATEST world state at each step</li>
 *   <li>LLM returns ONE action per step, not a full plan</li>
 *   <li>Goal is explicitly tracked and checked at every step</li>
 *   <li>All steps are logged for replay-based debugging</li>
 * </ul>
 *
 * <p>Equivalent to OpenCode's {@code SessionRunner.run()} + {@code runTurn()} pattern.</p>
 */
public class AgentLoop {

    private static final int MAX_STEPS = 50;
    private static final int MAX_HISTORY_IN_CONTEXT = 10;
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    /** Known action type strings — for detecting shorthand LLM output */
    private static final java.util.Set<String> KNOWN_TYPES = java.util.Set.of(
            "move_to", "break_block", "place_block", "attack", "craft", "follow",
            "give_item", "require_items", "say", "wait", "mine", "gather", "interact", "equip",
            "vein_mine", "use_item", "drop", "sneak", "look_at"
    );

    private final Goal goal;
    private final LLMService llmService;
    private final String modelName;
    private final ActionExecutor actionExecutor;
    private final SessionLog sessionLog;
    private final List<StepRecord> history = new ArrayList<>();
    private int consecutiveFailures = 0;

    /**
     * Create a new AgentLoop.
     *
     * @param goal           the high-level goal to achieve
     * @param llmService     LLM service for decision-making
     * @param modelName      model to use (can be cheap model for faster decisions)
     * @param actionExecutor executes actions and returns results
     * @param sessionLog     session log for recording (null to disable)
     */
    public AgentLoop(Goal goal, LLMService llmService, String modelName,
                     ActionExecutor actionExecutor, SessionLog sessionLog) {
        this.goal = goal;
        this.llmService = llmService;
        this.modelName = modelName;
        this.actionExecutor = actionExecutor;
        this.sessionLog = sessionLog;
    }

    /**
     * Run the agentic loop until the goal is achieved, failed, or max steps reached.
     *
     * <p>This is the main entry point, equivalent to OpenCode's {@code run()} method.</p>
     *
     * @param bot the bot to execute actions on
     * @return the final goal status
     */
    public Goal.GoalStatus run(FakePlayer bot) {
        DevLog.info("AGENT_LOOP_START", "goal={}, maxSteps={}", goal.getOriginalCommand(), MAX_STEPS);

        int step = 0;
        while (goal.isInProgress() && step < MAX_STEPS) {

            // 1. OBSERVE — capture current world state
            // (OpenCode: load context + history)
            JsonObject worldState = WorldObserver.observe(bot);

            // 2. ASSEMBLE CONTEXT — build prompt for LLM
            // (OpenCode: entriesForRunner)
            AgentContext ctx = new AgentContext(goal, worldState, history, MAX_HISTORY_IN_CONTEXT);
            String prompt = ctx.toPrompt();

            // 3. THINK — call LLM for next decision
            // (OpenCode: runTurn → stream LLM)
            long llmStart = System.currentTimeMillis();
            LLMResponse response = llmService.sendPromptWithModel(prompt, modelName);
            long llmDuration = System.currentTimeMillis() - llmStart;

            if (!response.isSuccess()) {
                DevLog.warn("AGENT_LLM_FAILED", "step={}, error={}", step, response.getError());
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    goal.setStatus(Goal.GoalStatus.FAILED);
                    goal.setFailReason("LLM failed " + MAX_CONSECUTIVE_FAILURES + " consecutive times");
                    break;
                }
                step++;
                continue;
            }

            // Parse LLM response into decision
            StepRecord.LLMDecision decision = parseDecision(response);
            DevLog.info("AGENT_PARSE_RESULT", "step={}, type={}, json={}, actionsInResponse={}",
                    step, decision.actionType(),
                    DevLog.compact(decision.actionJson()),
                    response.getActions().size());

            // Log LLM request/response
            if (sessionLog != null) {
                sessionLog.recordLLM(prompt, response.getRawResponse(), modelName, llmDuration);
            }

            DevLog.info("AGENT_DECISION", "step={}, type={}, reasoning={}",
                    step, decision.actionType(), compact(decision.reasoning()));

            // 4. ACT — execute the action
            // (OpenCode: toolMaterialization.settle)
            long actionStart = System.currentTimeMillis();
            Action action = actionExecutor.parseAction(decision.actionJson());
            if (action == null) {
                DevLog.warn("AGENT_ACTION_PARSE_FAILED", "step={}, json={}", step, decision.actionJson());
                consecutiveFailures++;
                step++;
                continue;
            }

            ActionResult result = actionExecutor.execute(action, bot);
            long actionDuration = System.currentTimeMillis() - actionStart;

            // 5. RECORD — save step to history and log
            // (OpenCode: publish event)
            StepRecord record = new StepRecord(
                    step + 1,
                    worldState,
                    decision,
                    action.getDescription(),
                    result.status(),
                    result.failReason(),
                    actionDuration,
                    WorldObserver.observe(bot) // capture state after execution
            );
            history.add(record);

            if (sessionLog != null) {
                sessionLog.recordStep(record);
            }

            DevLog.info("AGENT_STEP_DONE", "step={}, action={}, result={}, duration={}ms",
                    step + 1, action.getDescription(), result.status(), actionDuration);

            // 6. CHECK — update goal status
            if (result.isSuccess()) {
                consecutiveFailures = 0;
                if (goal.isAchieved(bot)) {
                    goal.setStatus(Goal.GoalStatus.ACHIEVED);
                    DevLog.info("AGENT_GOAL_ACHIEVED", "steps={}", step + 1);
                }
            } else {
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                    goal.setStatus(Goal.GoalStatus.FAILED);
                    goal.setFailReason("Too many consecutive failures (" + MAX_CONSECUTIVE_FAILURES + ")");
                    DevLog.warn("AGENT_GOAL_FAILED", "reason={}", goal.getFailReason());
                }
            }

            step++;
        }

        if (step >= MAX_STEPS && goal.isInProgress()) {
            goal.setStatus(Goal.GoalStatus.FAILED);
            goal.setFailReason("Exceeded max steps (" + MAX_STEPS + ")");
        }

        // Finalize session log
        if (sessionLog != null) {
            sessionLog.finish(goal.isAchieved(), goal.getFailReason());
        }

        DevLog.info("AGENT_LOOP_END", "goal={}, status={}, steps={}",
                goal.getOriginalCommand(), goal.getStatus(), step);

        return goal.getStatus();
    }

    /**
     * Parse LLM response into a decision.
     * Multi-layer fallback: actions list → raw content extraction → direct JSON parse.
     */
    private StepRecord.LLMDecision parseDecision(LLMResponse response) {
        String rawContent = response.getRawResponse() != null ? response.getRawResponse() : "";
        String actionJson = "{}";

        // Layer 1: Use pre-parsed actions from LLMResponseParser
        if (!response.getActions().isEmpty()) {
            actionJson = response.getActions().get(0);
        }
        // Layer 2: Extract from raw OpenAI response content
        else if (!rawContent.isEmpty()) {
            actionJson = extractActionFromRawResponse(rawContent);
        }

        // Extract reasoning from raw response
        String reasoning = extractReasoning(rawContent);

        // Extract action type from JSON
        // Handles 3 formats: {"type":"..."}, {"action":"..."}, {"break_block":{...}}
        String actionType = "unknown";
        try {
            JsonObject json = JsonParser.parseString(actionJson).getAsJsonObject();
            if (json.has("type")) {
                actionType = json.get("type").getAsString();
            } else if (json.has("action")) {
                actionType = json.get("action").getAsString();
            } else {
                // Format 3: action type as key, e.g. {"break_block": {"x": ...}}
                for (String knownType : KNOWN_TYPES) {
                    if (json.has(knownType)) {
                        actionType = knownType;
                        break;
                    }
                }
            }
        } catch (Exception ignored) {}

        return new StepRecord.LLMDecision(reasoning, actionJson, actionType);
    }

    /**
     * Extract action JSON from raw OpenAI-format response.
     * Handles cases where LLMResponseParser fails to extract actions.
     */
    private String extractActionFromRawResponse(String rawResponse) {
        try {
            JsonObject json = JsonParser.parseString(rawResponse).getAsJsonObject();

            // Try OpenAI format: choices[0].message.content
            if (json.has("choices")) {
                JsonArray choices = json.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                            ? choice.getAsJsonObject("message") : null;
                    if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                        String content = message.get("content").getAsString();
                        // Try parseActionsFromContent first
                        List<String> actions = LLMResponseParser.parseActionsFromContent(content);
                        if (!actions.isEmpty()) return actions.get(0);
                        // Fallback: if content looks like a JSON object, use it directly
                        if (content.trim().startsWith("{")) {
                            return content.trim();
                        }
                    }
                }
            }

            // Try direct action JSON (some LLMs return action directly)
            if (json.has("type")) {
                return rawResponse.trim();
            }
        } catch (Exception e) {
            DevLog.warn("AGENT_RAW_PARSE_FAILED", "err={}", e.getMessage());
        }
        return "{}";
    }

    /** Extract reasoning_content from OpenAI-format response. */
    private String extractReasoning(String rawResponse) {
        try {
            JsonObject raw = JsonParser.parseString(rawResponse).getAsJsonObject();
            if (raw.has("choices")) {
                var choices = raw.getAsJsonArray("choices");
                if (choices.size() > 0) {
                    var message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null && message.has("reasoning_content")) {
                        return message.get("reasoning_content").getAsString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Compact string for logging. */
    private String compact(String s) {
        if (s == null) return "null";
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }

    /**
     * Interface for executing actions.
     * Allows dependency injection for testing with mock executors.
     */
    public interface ActionExecutor {
        /** Parse an action from JSON string. Returns null if parsing fails. */
        Action parseAction(String actionJson);

        /** Execute an action and return the result. */
        ActionResult execute(Action action, FakePlayer bot);
    }

    /**
     * Result of executing an action.
     */
    public record ActionResult(Action.ActionStatus status, String failReason) {
        public static ActionResult success() {
            return new ActionResult(Action.ActionStatus.COMPLETED, null);
        }
        public static ActionResult failure(String reason) {
            return new ActionResult(Action.ActionStatus.FAILED, reason);
        }
        public boolean isSuccess() { return status == Action.ActionStatus.COMPLETED; }
    }
}
