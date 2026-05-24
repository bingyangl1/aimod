package com.aimod.ai.craft;

import com.aimod.ai.RecipeIndex;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A node in the recipe tree.
 * Either has a recipe (internal node with children) or is a raw material (leaf).
 *
 * <p>Inspired by EMI's MaterialNode.
 */
public class MaterialNode {

    private final ItemStack item;
    @Nullable
    private final RecipeIndex.IndexedRecipe recipe;
    private final List<MaterialNode> children;

    public MaterialNode(ItemStack item, @Nullable RecipeIndex.IndexedRecipe recipe, List<MaterialNode> children) {
        this.item = item;
        this.recipe = recipe;
        this.children = children;
    }

    public ItemStack item() { return item; }
    @Nullable
    public RecipeIndex.IndexedRecipe recipe() { return recipe; }
    public List<MaterialNode> children() { return children; }
    public boolean isRaw() { return recipe == null || children.isEmpty(); }

    @Override
    public String toString() {
        if (isRaw()) return item.getCount() + "x " + item.getItem();
        return item.getCount() + "x " + item.getItem() + " via " +
                (recipe != null ? recipe.getCategory() : "?") + " [" + children.size() + " children]";
    }
}
