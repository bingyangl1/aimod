package com.aimod.ai.craft;

import com.aimod.ai.RecipeIndex;
import net.minecraft.world.item.Item;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calculates the total raw material cost from a recipe tree.
 * Handles recipe output multipliers and catalyst exclusion.
 *
 * <p>Inspired by EMI's TreeCost.
 */
public final class TreeCost {
    private TreeCost() {}

    /**
     * Calculate raw material requirements for crafting a target.
     *
     * @param targetItem the desired output item
     * @param count      how many to craft
     * @param inventory  current inventory (already-available items)
     * @param maxDepth   max recipe recursion depth
     * @return map of raw item → count needed
     */
    public static Map<Item, Integer> calculate(
            Item targetItem, int count,
            RecipeIndex.InventoryState inventory, int maxDepth) {

        MaterialTree tree = new MaterialTree(targetItem, count);
        tree.build(inventory, maxDepth);
        return tree.getRequiredRawMaterials();
    }

    /**
     * Format raw materials as a human-readable shopping list.
     */
    public static String formatShoppingList(Map<Item, Integer> rawMaterials) {
        if (rawMaterials.isEmpty()) return "nothing needed";
        StringBuilder sb = new StringBuilder();
        for (var entry : rawMaterials.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getValue()).append("x ")
              .append(entry.getKey().getDescriptionId());
        }
        return sb.toString();
    }
}
