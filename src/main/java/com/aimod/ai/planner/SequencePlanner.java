package com.aimod.ai.planner;

import com.aimod.ai.RecipeIndex;
import com.aimod.ai.action.*;
import com.aimod.ai.craft.MaterialTree;
import com.aimod.ai.craft.MaterialNode;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.*;

/**
 * Generates action sequences from MaterialTree recipe decomposition.
 * Uses RecipeIndex to find recipes and MaterialTree to break down
 * complex crafts into gather+craft steps.
 */
public final class SequencePlanner {

    /**
     * Plan a crafting task: "craft X give to Y".
     */
    private static final String CRAFTING_TABLE_ID = "minecraft:crafting_table";

    public static List<Action> planCraftAndGive(
            FakePlayer bot, Item targetItem, int count, String playerName) {

        List<Action> actions = new ArrayList<>();
        // Check bot inventory + nearby chests before planning
        RecipeIndex.InventoryState inv = com.aimod.ai.InventoryUtils.asInventoryStateWithChests(bot, 8);
        MaterialTree tree = new MaterialTree(targetItem, count);
        tree.build(inv, 6);

        Map<Item, Integer> raw = tree.getRequiredRawMaterials();
        if (raw.isEmpty() && tree.getRoot() == null) return actions;

        // Phase 1: gather raw materials
        for (var entry : raw.entrySet()) {
            Item item = entry.getKey();
            int needed = entry.getValue();
            if (inv.countItem(item) >= needed) continue;
            int shortage = needed - inv.countItem(item);
            var resourceType = classifyResource(item);
            if (resourceType != null) {
                actions.add(new GatherResourceAction(resourceType, shortage));
            } else {
                String blockId = findBlockForItem(item);
                if (blockId != null) actions.add(new MineBlockAction(blockId, shortage));
            }
        }

        // Phase 2: ensure crafting table is available (place at feet if needed)
        boolean needsCraftingTable = !raw.isEmpty() || tree.getRoot() != null;
        BlockPos tablePos = null;
        if (needsCraftingTable) {
            // Use bot's position as the placement spot
            tablePos = bot.blockPosition().offset(1, 0, 0); // place 1 block to the side
            actions.add(new com.aimod.ai.action.PlaceBlockAction(tablePos,
                    (net.minecraft.world.item.BlockItem) net.minecraft.world.item.Items.CRAFTING_TABLE));
            actions.add(new InteractBlockAction(InteractBlockAction.InteractType.CRAFTING_TABLE, tablePos));
        }

        // Phase 3: craft intermediate + final items
        if (needsCraftingTable && tree.getRoot() != null) {
            for (var recipe : tree.getCraftingSteps()) {
                var outputItem = recipe.getOutputItem();
                actions.add(new CraftAction(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(outputItem).toString(), 1));
            }
            actions.add(new CraftAction(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetItem).toString(), count));
        } else if (needsCraftingTable) {
            // Simple craft (no intermediate steps)
            actions.add(new CraftAction(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetItem).toString(), count));
        }

        // Phase 4: clean up — break the placed crafting table to pick it up
        if (tablePos != null) {
            actions.add(new com.aimod.ai.action.BreakBlockAction(tablePos));
        }

        // Phase 5: give to player
        if (playerName != null && !playerName.isBlank() && !playerName.equals("me")) {
            actions.add(new GiveItemAction(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(targetItem).toString(),
                    count, playerName));
        }

        return actions;
    }

    /** Plan a simple mining task. */
    public static List<Action> planMine(Item targetItem, int count) {
        String blockId = findBlockForItem(targetItem);
        if (blockId == null) return List.of();
        return List.of(new MineBlockAction(blockId, count));
    }

    /** Plan a simple gather task. */
    public static List<Action> planGather(Item targetItem, int count) {
        var type = classifyResource(targetItem);
        if (type == null) return List.of();
        return List.of(new GatherResourceAction(type, count));
    }

    // ---- Helpers ----

    private static GatherResourceAction.ResourceType classifyResource(Item item) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        if (key.contains("log") || key.contains("wood") || key.contains("stem") || key.contains("hyphae"))
            return GatherResourceAction.ResourceType.WOOD;
        if (key.contains("stone") || key.contains("cobblestone") || key.contains("rock"))
            return GatherResourceAction.ResourceType.STONE;
        if (key.contains("dirt") || key.contains("grass"))
            return GatherResourceAction.ResourceType.DIRT;
        if (key.contains("sand"))
            return GatherResourceAction.ResourceType.SAND;
        return null;
    }

    private static String findBlockForItem(Item item) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        // Map items to their ore/block forms
        if (key.contains("diamond")) return "minecraft:diamond_ore";
        if (key.contains("iron_ingot") || key.contains("iron_nugget")) return "minecraft:iron_ore";
        if (key.contains("gold_ingot") || key.contains("gold_nugget")) return "minecraft:gold_ore";
        if (key.contains("coal")) return "minecraft:coal_ore";
        if (key.contains("copper_ingot")) return "minecraft:copper_ore";
        if (key.contains("lapis")) return "minecraft:lapis_ore";
        if (key.contains("redstone")) return "minecraft:redstone_ore";
        if (key.contains("emerald")) return "minecraft:emerald_ore";
        if (key.contains("netherite")) return "minecraft:ancient_debris";
        if (key.contains("quartz")) return "minecraft:nether_quartz_ore";
        // Wood → log
        if (key.contains("planks") || key.contains("stick") || key.contains("wood"))
            return "minecraft:oak_log";
        return null;
    }
}
