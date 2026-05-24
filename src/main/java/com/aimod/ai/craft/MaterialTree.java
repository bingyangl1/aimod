package com.aimod.ai.craft;

import com.aimod.ai.RecipeIndex;
import com.aimod.ai.RecipeIndex.InventoryState;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Recursive recipe tree that decomposes a target item into raw materials.
 * Inspired by EMI's MaterialTree / BoM system.
 *
 * <p>Usage:
 * <pre>
 *   MaterialTree tree = MaterialTree.build(targetItem, count, inventory);
 *   Map<Item, Integer> raw = tree.getRequiredRawMaterials();
 *   // raw maps each atomic (uncraftable) item to the total needed
 * </pre>
 */
public class MaterialTree {

    private final Item targetItem;
    private final int targetCount;
    private MaterialNode root;
    private boolean built;

    public MaterialTree(Item targetItem, int count) {
        this.targetItem = targetItem;
        this.targetCount = count;
    }

    /**
     * Build the tree by recursively resolving recipes.
     * @param inventory current inventory (already-available items reduce requirements)
     * @param depth max recursion depth (prevent infinite loops)
     */
    public void build(InventoryState inventory, int depth) {
        root = resolve(new ItemStack(targetItem, targetCount), inventory, new HashSet<>(), depth);
        built = true;
    }

    private MaterialNode resolve(ItemStack need, InventoryState inventory,
                                  Set<Item> visited, int depth) {
        if (depth <= 0) return new MaterialNode(need, null, List.of());

        Item item = need.getItem();
        if (!visited.add(item)) return new MaterialNode(need, null, List.of()); // cycle

        // Check how much we already have
        int available = inventory.countItem(item);
        int stillNeed = Math.max(0, need.getCount() - available);

        if (stillNeed <= 0) {
            visited.remove(item);
            return new MaterialNode(need, null, List.of()); // already have enough
        }

        // Find a recipe
        RecipeIndex.IndexedRecipe recipe = RecipeIndex.getInstance()
                .findBestRecipe(item, inventory);
        if (recipe == null) {
            visited.remove(item);
            return new MaterialNode(new ItemStack(item, stillNeed), null, List.of()); // raw material
        }

        // Resolve sub-ingredients
        List<MaterialNode> children = new ArrayList<>();
        int craftsNeeded = (int) Math.ceil((double) stillNeed / recipe.getOutputCount());

        for (RecipeIndex.IngredientEntry entry : recipe.getConsumedInputs()) {
            Item[] items = entry.getMatchingItems();
            if (items.length == 0) continue;
            // Pick first matching item (prefer cheapest)
            Item inputItem = items[0];
            int inputCount = entry.getCount() * craftsNeeded;
            children.add(resolve(new ItemStack(inputItem, inputCount), inventory, visited, depth - 1));
        }

        visited.remove(item);
        return new MaterialNode(new ItemStack(item, stillNeed), recipe, children);
    }

    /**
     * Get all raw materials (leaf nodes without recipes) and their required counts.
     */
    public Map<Item, Integer> getRequiredRawMaterials() {
        Map<Item, Integer> result = new LinkedHashMap<>();
        if (root != null) collectRaw(root, result);
        return result;
    }

    private void collectRaw(MaterialNode node, Map<Item, Integer> result) {
        if (node.children() == null || node.children().isEmpty()) {
            result.merge(node.item().getItem(), node.item().getCount(), Integer::sum);
        } else {
            for (MaterialNode child : node.children()) {
                collectRaw(child, result);
            }
        }
    }

    public List<RecipeIndex.IndexedRecipe> getCraftingSteps() {
        List<RecipeIndex.IndexedRecipe> steps = new ArrayList<>();
        if (root != null) collectSteps(root, steps);
        return steps;
    }

    private void collectSteps(MaterialNode node, List<RecipeIndex.IndexedRecipe> steps) {
        if (node.children() == null || node.children().isEmpty()) return;
        for (MaterialNode child : node.children()) {
            collectSteps(child, steps);
        }
        if (node.recipe() != null) {
            steps.add(node.recipe());
        }
    }

    public boolean isBuilt() { return built; }
    public MaterialNode getRoot() { return root; }
}
