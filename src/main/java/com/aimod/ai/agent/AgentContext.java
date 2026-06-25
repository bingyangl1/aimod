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

        // Goal requirements analysis
        appendGoalAnalysis(sb);

        // Current state section
        sb.append("## Current State\n");
        sb.append(formatWorldState(worldState)).append("\n");

        // Mining depth guide
        appendMiningGuide(sb);

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
        sb.append("Think step by step:\n");
        sb.append("1. What do I need to achieve the goal?\n");
        sb.append("2. What do I currently have?\n");
        sb.append("3. What is the most important next action?\n\n");
        sb.append("## Response Format\n");
        sb.append("You MUST respond with EXACTLY ONE JSON object. Use \"type\" as the key for action type.\n");
        sb.append("Do NOT use \"action\" as the key. Do NOT nest parameters in a sub-object.\n\n");
        sb.append("Correct format:\n");
        sb.append("{\"type\": \"break_block\", \"x\": -390, \"y\": 100, \"z\": -261}\n");
        sb.append("{\"type\": \"mine\", \"block_id\": \"minecraft:diamond_ore\", \"count\": 1, \"radius\": 128}\n");
        sb.append("{\"type\": \"craft\", \"item_id\": \"minecraft:stick\", \"count\": 4}\n");
        sb.append("{\"type\": \"move_to\", \"x\": -390, \"y\": 60, \"z\": -261}\n");
        sb.append("{\"type\": \"equip\", \"item_id\": \"minecraft:netherite_pickaxe\", \"slot\": \"MAINHAND\"}\n");
        sb.append("{\"type\": \"interact\", \"interact_type\": \"CRAFTING_TABLE\"}\n\n");
        sb.append("WRONG formats (do NOT use):\n");
        sb.append("{\"action\": \"break_block\", ...}  ← use \"type\", not \"action\"\n");
        sb.append("{\"break_block\": {\"x\": ...}}  ← do NOT nest, use flat keys\n\n");
        sb.append("Available types: move_to, break_block, place_block, mine, gather, craft, give_item, interact, equip, attack, follow, say, wait\n");
        sb.append("For break_block/move_to/place_block: x, y, z are REQUIRED flat keys.\n");
        sb.append("For mine/gather: radius max 128.\n");
        sb.append("Use 'mine' to find ores, 'craft' to craft items, 'interact' with crafting_table before crafting.\n");

        return sb.toString();
    }

    /**
     * Analyze what items are needed for the goal and what's missing.
     */
    private void appendGoalAnalysis(StringBuilder sb) {
        String goalText = goal.getOriginalCommand().toLowerCase();

        // Diamond armor set
        if (goalText.contains("钻石") || goalText.contains("diamond")) {
            sb.append("## What You Need\n");
            if (goalText.contains("装备") || goalText.contains("armor") || goalText.contains("套")) {
                sb.append("Diamond armor set requires:\n");
                sb.append("- diamond_helmet: 5 diamonds\n");
                sb.append("- diamond_chestplate: 8 diamonds\n");
                sb.append("- diamond_leggings: 7 diamonds\n");
                sb.append("- diamond_boots: 4 diamonds\n");
                sb.append("- Total: 24 diamonds\n");
                sb.append("- Crafting table required (interact with crafting_table before craft)\n\n");
            } else if (goalText.contains("剑") || goalText.contains("sword")) {
                sb.append("Diamond sword requires: 2 diamonds + 1 stick\n\n");
            } else if (goalText.contains("镐") || goalText.contains("pickaxe")) {
                sb.append("Diamond pickaxe requires: 3 diamonds + 2 sticks\n\n");
            } else {
                sb.append("Diamond items require diamonds mined from diamond_ore.\n\n");
            }

            // Check inventory for diamonds
            if (worldState.has("inventory")) {
                JsonObject inv = worldState.getAsJsonObject("inventory");
                int diamonds = 0;
                for (var entry : inv.entrySet()) {
                    if (entry.getKey().contains("diamond") && !entry.getKey().contains("pickaxe")
                            && !entry.getKey().contains("sword") && !entry.getKey().contains("armor")) {
                        diamonds += entry.getValue().getAsInt();
                    }
                }
                sb.append("Current diamonds in inventory: ").append(diamonds).append("\n\n");
            }
        }

        // Iron items
        if (goalText.contains("铁") || goalText.contains("iron")) {
            sb.append("## What You Need\n");
            sb.append("Iron items require iron_ingot (smelt raw_iron in furnace).\n");
            sb.append("Iron ore is found below Y=64.\n\n");
        }
    }

    /**
     * Add mining depth guide when the goal involves gathering resources.
     */
    private void appendMiningGuide(StringBuilder sb) {
        String goalText = goal.getOriginalCommand().toLowerCase();
        boolean needsMining = goalText.contains("钻石") || goalText.contains("diamond")
                || goalText.contains("铁") || goalText.contains("iron")
                || goalText.contains("金") || goalText.contains("gold")
                || goalText.contains("红石") || goalText.contains("redstone")
                || goalText.contains("青金石") || goalText.contains("lapis")
                || goalText.contains("绿宝石") || goalText.contains("emerald")
                || goalText.contains("挖") || goalText.contains("mine")
                || goalText.contains("采") || goalText.contains("gather");

        if (!needsMining) return;

        // Check if bot is at surface level
        if (worldState.has("position")) {
            int y = worldState.getAsJsonObject("position").get("y").getAsInt();
            if (y > 16) {
                sb.append("## Mining Guide\n");
                sb.append("You are at Y=").append(y).append(" (surface level).\n");
                sb.append("Ore spawn depths:\n");
                sb.append("- Diamond ore: Y < 16 (best at Y = -59)\n");
                sb.append("- Iron ore: Y < 64 (best at Y = 16)\n");
                sb.append("- Gold ore: Y < 32 (best at Y = -16)\n");
                sb.append("- Redstone: Y < 16\n");
                sb.append("- Lapis: Y < 32\n");
                sb.append("- Emerald: Y < 32 (mountains only)\n");
                sb.append("- Coal: Y < 96\n\n");
                sb.append("To mine diamonds, you need to dig down first.\n");
                sb.append("Use 'mine' action with block_id 'minecraft:diamond_ore' and radius 128.\n");
                sb.append("If no diamond ore found, use 'break_block' to dig down to Y < 16.\n\n");
            }
        }
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
