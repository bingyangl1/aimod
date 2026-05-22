package com.example.aimod.ai.action;

import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.Level;

import java.util.*;

public class CraftAction extends Action {
    private static final int CRAFT_TIME = 40;

    private final String itemId;
    private final int count;
    private int craftProgress;
    private RecipeHolder<?> resolvedRecipe;

    public CraftAction(String itemId, int count) {
        super("Craft " + count + " " + itemId);
        this.itemId = itemId;
        this.count = Math.max(1, count);
        this.craftProgress = 0;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        // 尝试解析配方
        resolvedRecipe = findRecipe(bot);
        if (resolvedRecipe == null) {
            DevLog.warn("CRAFT_NO_RECIPE", "item={}", itemId);
            return false;
        }

        // 检查材料
        Map<net.minecraft.world.item.Item, Integer> requiredItems = getRequiredItems(resolvedRecipe);
        Map<net.minecraft.world.item.Item, Integer> missing = InventoryUtils.missingItems(bot, requiredItems);
        if (!missing.isEmpty()) {
            DevLog.warn("CRAFT_MISSING_ITEMS", "item={}, count={}, missing={}",
                    itemId, count, InventoryUtils.describeItems(missing));
            return false;
        }
        return true;
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            if (resolvedRecipe == null) {
                resolvedRecipe = findRecipe(bot);
            }
            if (resolvedRecipe == null) {
                status = ActionStatus.FAILED;
                DevLog.warn("CRAFT_FAIL_NO_RECIPE", "item={}", itemId);
                return;
            }

            Map<net.minecraft.world.item.Item, Integer> requiredItems = getRequiredItems(resolvedRecipe);
            Map<net.minecraft.world.item.Item, Integer> missing = InventoryUtils.missingItems(bot, requiredItems);
            if (!missing.isEmpty()) {
                status = ActionStatus.FAILED;
                DevLog.warn("CRAFT_FAIL_MISSING", "item={}, missing={}", itemId, InventoryUtils.describeItems(missing));
                return;
            }

            status = ActionStatus.IN_PROGRESS;
            craftProgress = 0;
            DevLog.info("CRAFT_START", "item={}, count={}, recipe={}", itemId, count, resolvedRecipe.id());
        }

        if (status == ActionStatus.IN_PROGRESS) {
            craftProgress++;
            if (craftProgress >= CRAFT_TIME) {
                // 消耗材料
                Map<net.minecraft.world.item.Item, Integer> requiredItems = getRequiredItems(resolvedRecipe);
                if (!InventoryUtils.hasItems(bot, requiredItems)) {
                    status = ActionStatus.FAILED;
                    DevLog.warn("CRAFT_FAIL_RESOURCES_CHANGED", "item={}", itemId);
                    return;
                }

                InventoryUtils.consumeItems(bot, requiredItems);

                // 创建输出物品
                ItemStack result = resolvedRecipe.value().getResultItem(bot.level().registryAccess());
                ItemStack output = result.copy();
                output.setCount(output.getCount() * count);

                // 添加到背包
                boolean added = InventoryUtils.addItem(bot, output);
                if (added) {
                    status = ActionStatus.COMPLETED;
                    DevLog.info("CRAFT_DONE", "item={}, count={}", itemId, count);
                } else {
                    status = ActionStatus.FAILED;
                    DevLog.warn("CRAFT_FAIL_INVENTORY_FULL", "item={}", itemId);
                }
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    /**
     * 查找配方
     */
    private RecipeHolder<?> findRecipe(AIBotEntity bot) {
        Level level = bot.level();
        net.minecraft.world.item.Item resultItem = resolveItem(itemId);
        if (resultItem == Items.AIR) {
            return null;
        }

        // 查找所有配方
        RecipeManager recipeManager = level.getRecipeManager();
        Collection<RecipeHolder<?>> recipes = recipeManager.getRecipes();

        for (RecipeHolder<?> recipe : recipes) {
            Recipe<?> recipeValue = recipe.value();

            // 检查是否是合成配方
            if (recipeValue instanceof CraftingRecipe) {
                ItemStack result = recipeValue.getResultItem(level.registryAccess());
                if (result.getItem() == resultItem) {
                    return recipe;
                }
            }
        }

        return null;
    }

    /**
     * 获取配方所需材料
     */
    private Map<net.minecraft.world.item.Item, Integer> getRequiredItems(RecipeHolder<?> recipe) {
        Map<net.minecraft.world.item.Item, Integer> required = new LinkedHashMap<>();

        Recipe<?> recipeValue = recipe.value();
        if (recipeValue instanceof CraftingRecipe) {
            // 获取配方的输入
            List<Ingredient> ingredients = recipeValue.getIngredients();
            for (Ingredient ingredient : ingredients) {
                if (!ingredient.isEmpty()) {
                    ItemStack[] items = ingredient.getItems();
                    if (items.length > 0) {
                        net.minecraft.world.item.Item item = items[0].getItem();
                        required.put(item, required.getOrDefault(item, 0) + 1);
                    }
                }
            }
        }

        return required;
    }

    /**
     * 解析物品 ID
     */
    private net.minecraft.world.item.Item resolveItem(String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText.contains(":") ? idText : "minecraft:" + idText);
        if (id == null) {
            return Items.AIR;
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    public String getItemId() {
        return itemId;
    }

    public int getCount() {
        return count;
    }
}
