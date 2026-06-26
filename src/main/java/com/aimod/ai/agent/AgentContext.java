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
     * Includes tool descriptions (MCP-style), world state, history, and instructions.
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

        // Tool descriptions (MCP-style)
        appendToolDescriptions(sb);

        // Decision instructions
        sb.append("## Decision Process\n");
        sb.append("1. Read 'Goal' and 'Goal Requirements' to understand what you need\n");
        sb.append("2. Read 'Current State' (especially 'Equipped' and 'Inventory') to see what you already have\n");
        sb.append("3. Read 'Recent Steps' to see what was already done — DO NOT repeat completed actions\n");
        sb.append("4. Choose the BEST tool from the list below to make progress\n");
        sb.append("5. If an action FAILED because the target was already broken/mined, move to the next step\n\n");

        sb.append("## Response Format\n");
        sb.append("Respond with EXACTLY ONE JSON object. Use \"type\" as the key.\n");
        sb.append("{\"type\": \"<tool_name>\", \"param1\": value, ...}\n");

        return sb.toString();
    }

    /**
     * MCP-style tool descriptions — tells the LLM what each action does,
     * when to use it, and what special features it has.
     */
    private void appendToolDescriptions(StringBuilder sb) {
        sb.append("## Available Tools\n\n");

        // --- Mining & Gathering ---
        sb.append("### mine\n");
        sb.append("Find and mine a specific block type. Automatically navigates to the nearest matching block.\n");
        sb.append("**Special feature**: If the ore is not found nearby and it's a known ore type (diamond, iron, gold, etc.), ");
        sb.append("the bot will AUTO-DIG down to the correct Y level where that ore spawns. You do NOT need to use break_block to dig down manually.\n");
        sb.append("Parameters: block_id (required), count (default 1), radius (default 32, max 128)\n");
        sb.append("Example: {\"type\": \"mine\", \"block_id\": \"minecraft:diamond_ore\", \"count\": 1, \"radius\": 128}\n\n");

        sb.append("### gather\n");
        sb.append("Gather a resource type (WOOD, STONE, DIRT, SAND, COBBLESTONE). Finds and breaks matching blocks.\n");
        sb.append("Parameters: resource_type (required), count (required), radius (default 32, max 128)\n");
        sb.append("Example: {\"type\": \"gather\", \"resource_type\": \"WOOD\", \"count\": 16, \"radius\": 32}\n\n");

        // --- Crafting ---
        sb.append("### craft\n");
        sb.append("Craft an item using a crafting table. The bot must be near a crafting table (use 'interact' first).\n");
        sb.append("Parameters: item_id (required), count (default 1)\n");
        sb.append("Example: {\"type\": \"craft\", \"item_id\": \"minecraft:diamond_pickaxe\", \"count\": 1}\n\n");

        sb.append("### interact\n");
        sb.append("Right-click a block (crafting table, furnace, chest, etc.). Use this BEFORE 'craft' to open the crafting menu.\n");
        sb.append("Parameters: interact_type (required: CRAFTING_TABLE, FURNACE, CHEST, ANVIL, etc.)\n");
        sb.append("Example: {\"type\": \"interact\", \"interact_type\": \"CRAFTING_TABLE\"}\n\n");

        // --- Movement ---
        sb.append("### move_to\n");
        sb.append("Walk/fly to a specific position. Use for long-distance travel.\n");
        sb.append("Parameters: x, y, z (all required)\n");
        sb.append("Example: {\"type\": \"move_to\", \"x\": -390, \"y\": 60, \"z\": -261}\n\n");

        sb.append("### break_block\n");
        sb.append("Break a SINGLE block at a specific position. Use for precise block removal.\n");
        sb.append("**Do NOT use for digging long tunnels** — use 'mine' instead (it handles navigation and auto-dig).\n");
        sb.append("Parameters: x, y, z (all required)\n");
        sb.append("Example: {\"type\": \"break_block\", \"x\": -390, \"y\": 100, \"z\": -261}\n\n");

        sb.append("### place_block\n");
        sb.append("Place a block from inventory at a position.\n");
        sb.append("Parameters: x, y, z (all required), block_id (default: first block in inventory)\n");
        sb.append("Example: {\"type\": \"place_block\", \"x\": -390, \"y\": 100, \"z\": -261, \"block_id\": \"minecraft:cobblestone\"}\n\n");

        // --- Inventory ---
        sb.append("### equip\n");
        sb.append("Hold/wear an item from inventory. Check 'Equipped' in Current State first — do NOT equip what's already held.\n");
        sb.append("Parameters: item_id (required), slot (MAINHAND/OFFHAND/HEAD/CHEST/LEGS/FEET)\n");
        sb.append("Example: {\"type\": \"equip\", \"item_id\": \"minecraft:diamond_pickaxe\", \"slot\": \"MAINHAND\"}\n\n");

        sb.append("### give_item\n");
        sb.append("Give items to a player.\n");
        sb.append("Parameters: item_id (required), count (required), player (required)\n");
        sb.append("Example: {\"type\": \"give_item\", \"item_id\": \"minecraft:diamond_helmet\", \"count\": 1, \"player\": \"nightfall\"}\n\n");

        // --- Combat ---
        sb.append("### attack\n");
        sb.append("Attack a nearby entity by name.\n");
        sb.append("Parameters: target (required)\n");
        sb.append("Example: {\"type\": \"attack\", \"target\": \"zombie\"}\n\n");

        sb.append("### follow\n");
        sb.append("Follow a player.\n");
        sb.append("Parameters: player (required)\n");
        sb.append("Example: {\"type\": \"follow\", \"player\": \"nightfall\"}\n\n");

        // --- Utility ---
        sb.append("### say\n");
        sb.append("Send a chat message.\n");
        sb.append("Parameters: message (required)\n");
        sb.append("Example: {\"type\": \"say\", \"message\": \"I found diamonds!\"}\n\n");

        sb.append("### wait\n");
        sb.append("Wait for a number of seconds.\n");
        sb.append("Parameters: seconds (required)\n");
        sb.append("Example: {\"type\": \"wait\", \"seconds\": 5}\n\n");

        // --- Key Rules ---
        sb.append("## Key Rules\n");
        sb.append("- Use 'mine' for ores — it auto-digs to the correct Y level. Do NOT use break_block to dig down manually.\n");
        sb.append("- Use 'gather' for wood/stone/dirt — it handles finding and breaking multiple blocks.\n");
        sb.append("- Use 'interact' BEFORE 'craft' — you need to open the crafting table first.\n");
        sb.append("- Check 'Equipped' before using 'equip' — do NOT equip what's already held.\n");
        sb.append("- Check 'Recent Steps' — do NOT repeat actions that already succeeded.\n");
        sb.append("- ALWAYS use the BEST available tool. Tool tier (best to worst): netherite > diamond > iron > stone > wood.\n");
        sb.append("  If you already have netherite_pickaxe equipped, do NOT switch to iron_pickaxe.\n");
        sb.append("  If you need to mine diamond_ore, use the best pickaxe available (netherite > diamond > iron).\n");
        sb.append("- If an action FAILED because the target was already broken, move to the next step.\n");
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

        // Equipped items
        if (state.has("equipped")) {
            sb.append("Equipped: ");
            JsonObject equipped = state.getAsJsonObject("equipped");
            boolean first = true;
            for (var entry : equipped.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue().getAsString());
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
