package com.aimod.ai.agent;

import com.aimod.ai.session.StepRecord;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Assembles the context sent to the LLM for each decision step.
 *
 * <p>Equivalent to OpenCode's {@code entriesForRunner()} — combines the goal,
 * current world state, and execution history into a prompt that allows the LLM
 * to make an informed decision about the next action.</p>
 *
 * <p>The key difference from the current architecture: the LLM sees the
 * <b>latest</b> world state at every step, not just the state at planning time.</p>
 */
public class AgentContext {

    private final Goal goal;
    private final JsonObject worldState;
    private final List<StepRecord> history;
    private final int maxHistorySteps;

    public AgentContext(Goal goal, JsonObject worldState, List<StepRecord> history, int maxHistorySteps) {
        this.goal = goal;
        this.worldState = worldState;
        this.history = history;
        this.maxHistorySteps = maxHistorySteps;
    }

    /**
     * Build the full prompt for the LLM.
     */
    public String toPrompt() {
        StringBuilder sb = new StringBuilder();

        // Goal section
        sb.append("## Goal\n");
        sb.append(goal.getOriginalCommand()).append("\n\n");

        // Current state section
        sb.append("## Current State\n");
        sb.append(formatWorldState(worldState)).append("\n");

        // History section (recent steps)
        if (!history.isEmpty()) {
            sb.append("## Recent Steps\n");
            int start = Math.max(0, history.size() - maxHistorySteps);
            for (int i = start; i < history.size(); i++) {
                StepRecord step = history.get(i);
                sb.append(String.format("Step %d: %s → %s",
                        step.getStepNumber(),
                        step.getActionDescription(),
                        step.isSuccess() ? "SUCCESS" : "FAILED: " + step.getFailReason()));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Instruction
        sb.append("## Instruction\n");
        sb.append("Based on the current state and history, decide the NEXT single action to progress toward the goal.\n");
        sb.append("Respond with ONE JSON action. Do NOT repeat previously failed actions.\n");
        sb.append("Available types: move_to, break_block, place_block, mine, gather, craft, give_item, interact, equip, attack, follow, say, wait\n");
        sb.append("For break_block/move_to/place_block: x, y, z are REQUIRED.\n");
        sb.append("For mine/gather: radius max 128.\n");

        return sb.toString();
    }

    private String formatWorldState(JsonObject state) {
        StringBuilder sb = new StringBuilder();

        // Position
        if (state.has("position")) {
            JsonObject pos = state.getAsJsonObject("position");
            sb.append(String.format("Position: %s, %s, %s\n",
                    pos.get("x"), pos.get("y"), pos.get("z")));
        }

        // Health & food
        if (state.has("health")) {
            sb.append(String.format("Health: %s/%s\n", state.get("health"), 20));
        }
        if (state.has("food")) {
            sb.append(String.format("Food: %s\n", state.get("food")));
        }

        // Time & biome
        if (state.has("isDay")) {
            sb.append(String.format("Time: %s\n", state.get("isDay").getAsBoolean() ? "day" : "night"));
        }
        if (state.has("biome")) {
            sb.append(String.format("Biome: %s\n", state.get("biome").getAsString()));
        }

        // Inventory
        if (state.has("inventory")) {
            sb.append("Inventory: ");
            JsonObject inv = state.getAsJsonObject("inventory");
            boolean first = true;
            for (var entry : inv.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getValue()).append("x ").append(entry.getKey());
                first = false;
            }
            sb.append("\n");
        }

        // Nearby blocks
        if (state.has("nearbyBlocks")) {
            sb.append("Nearby blocks: ");
            JsonObject blocks = state.getAsJsonObject("nearbyBlocks");
            boolean first = true;
            for (var entry : blocks.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getValue()).append("x ").append(entry.getKey());
                first = false;
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
