package com.aimod.ai.action;

import com.aimod.ai.InventoryUtils;
import com.aimod.ai.RecipeIndex;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.Map;

/**
 * CraftAction - refactored to use RecipeIndex for O(1) lookup,
 * Tag-aware ingredient matching, and catalyst/consumed input distinction.
 *
 * Key improvements (from EMI/JEI analysis):
 * - O(1) recipe lookup via RecipeIndex (was O(n) scan)
 * - Tag support: #minecraft:planks matches any planks
 * - Catalyst distinction: crafting table is not consumed
 * - Smart ingredient selection: picks best available Tag match
 */
public class CraftAction extends Action {
    private static final int CRAFT_TIME = 40;

    private final String itemId;
    private final int count;
    private int craftProgress;
    private RecipeIndex.IndexedRecipe resolvedRecipe;
    private boolean recipeIndexBuilt = false;

    public CraftAction(String itemId, int count) {
        super("Craft " + count + " " + itemId);
        this.itemId = itemId;
        this.count = Math.max(1, count);
        this.craftProgress = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        ensureRecipeIndex(bot);

        Item resultItem = resolveItem(itemId);
        if (resultItem == Items.AIR) {
            DevLog.warn("CRAFT_NO_ITEM", "item={}", itemId);
            return false;
        }

        // O(1) lookup via RecipeIndex
        resolvedRecipe = RecipeIndex.getInstance().findBestRecipe(
                resultItem, InventoryUtils.asInventoryState(bot)
        );

        if (resolvedRecipe == null) {
            DevLog.warn("CRAFT_NO_RECIPE", "item={}", itemId);
            return false;
        }

        // Check consumed inputs only (catalysts are not consumed)
        Map<Item, Integer> requiredItems = resolvedRecipe.getTotalRequiredItems();
        Map<Item, Integer> missing = InventoryUtils.missingItems(bot, requiredItems);
        if (!missing.isEmpty()) {
            DevLog.warn("CRAFT_MISSING_ITEMS", "item={}, count={}, missing={}",
                    itemId, count, InventoryUtils.describeItems(missing));
            return false;
        }

        DevLog.info("CRAFT_RECIPE_RESOLVED", "item={}, recipe={}, category={}, inputs={}, catalysts={}",
                itemId, resolvedRecipe.getId(), resolvedRecipe.getCategory(),
                resolvedRecipe.getConsumedInputs().size(), resolvedRecipe.getCatalysts().size());
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            if (resolvedRecipe == null) {
                ensureRecipeIndex(bot);
                Item resultItem = resolveItem(itemId);
                resolvedRecipe = RecipeIndex.getInstance().findBestRecipe(
                        resultItem, InventoryUtils.asInventoryState(bot)
                );
            }
            if (resolvedRecipe == null) {
                status = ActionStatus.FAILED;
                DevLog.warn("CRAFT_FAIL_NO_RECIPE", "item={}", itemId);
                return;
            }

            // Check consumed inputs (not catalysts)
            Map<Item, Integer> requiredItems = resolvedRecipe.getTotalRequiredItems();
            Map<Item, Integer> missing = InventoryUtils.missingItems(bot, requiredItems);
            if (!missing.isEmpty()) {
                status = ActionStatus.FAILED;
                DevLog.warn("CRAFT_FAIL_MISSING", "item={}, missing={}", itemId, InventoryUtils.describeItems(missing));
                return;
            }

            status = ActionStatus.IN_PROGRESS;
            craftProgress = 0;
            DevLog.info("CRAFT_START", "item={}, count={}, recipe={}, category={}",
                    itemId, count, resolvedRecipe.getId(), resolvedRecipe.getCategory());
        }

        if (status == ActionStatus.IN_PROGRESS) {
            craftProgress++;
            if (craftProgress >= CRAFT_TIME) {
                // Re-verify consumed inputs still available
                Map<Item, Integer> requiredItems = resolvedRecipe.getTotalRequiredItems();
                if (!InventoryUtils.hasItems(bot, requiredItems)) {
                    status = ActionStatus.FAILED;
                    DevLog.warn("CRAFT_FAIL_RESOURCES_CHANGED", "item={}", itemId);
                    return;
                }

                // Consume only inputs, NOT catalysts
                InventoryUtils.consumeItems(bot, requiredItems);

                // Create output
                ItemStack result = resolvedRecipe.getHolder().value()
                        .getResultItem(bot.level().registryAccess());
                ItemStack output = result.copy();
                output.setCount(output.getCount() * count);

                boolean added = InventoryUtils.addItem(bot, output);
                if (added) {
                    status = ActionStatus.COMPLETED;
                    DevLog.info("CRAFT_DONE", "item={}, count={}, category={}",
                            itemId, count, resolvedRecipe.getCategory());
                } else {
                    status = ActionStatus.FAILED;
                    DevLog.warn("CRAFT_FAIL_INVENTORY_FULL", "item={}", itemId);
                }
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    /**
     * Ensure the recipe index is built. Call once per server lifecycle.
     */
    private void ensureRecipeIndex(FakePlayer bot) {
        if (recipeIndexBuilt) return;
        RecipeIndex index = RecipeIndex.getInstance();
        if (!index.isBuilt()) {
            index.build(bot.serverLevel());
            DevLog.info("RECIPE_INDEX_INIT", "Built recipe index on first craft request");
        }
        recipeIndexBuilt = true;
    }

    /**
     * Resolve item ID string to Item.
     */
    private Item resolveItem(String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText.contains(":") ? idText : "minecraft:" + idText);
        if (id == null) return Items.AIR;
        return BuiltInRegistries.ITEM.get(id);
    }

    public String getItemId() { return itemId; }
    public int getCount() { return count; }

    /**
     * Get human-readable info about the resolved recipe.
     */
    public String getRecipeInfo() {
        if (resolvedRecipe == null) return "No recipe resolved";
        StringBuilder sb = new StringBuilder();
        sb.append(resolvedRecipe.getOutputItem()).append(" x").append(resolvedRecipe.getOutputCount());
        sb.append(" via ").append(resolvedRecipe.getCategory());
        sb.append(" (").append(resolvedRecipe.getConsumedInputs().size()).append(" inputs");
        if (!resolvedRecipe.getCatalysts().isEmpty()) {
            sb.append(", ").append(resolvedRecipe.getCatalysts().size()).append(" catalysts");
        }
        sb.append(")");
        return sb.toString();
    }
}