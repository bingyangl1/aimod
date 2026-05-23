package com.aimod.ai.pathing;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Calculates the best tool for breaking blocks and accurate break speeds.
 * Adapted from Baritone's ToolSet (LGPL-3.0).
 * 
 * Differences from Baritone:
 * - Works with FakePlayer (not LocalPlayer)
 * - Checks full inventory, not just hotbar
 * - Caches break strength per block type
 */
public class ToolSet {

    private final FakePlayer bot;
    private final Map<Block, Double> breakStrengthCache;
    private final Function<Block, Double> backendCalculation;

    public ToolSet(FakePlayer bot) {
        this.bot = bot;
        this.breakStrengthCache = new HashMap<>();
        this.backendCalculation = this::getBestDestructionTime;
    }

    /**
     * Get the break speed (1/ticks) for a block using the best available tool.
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    /**
     * Find the best inventory slot for mining a specific block.
     * Returns the slot index, or -1 if no tool found.
     */
    public int getBestSlot(Block block) {
        int best = -1;
        double highestSpeed = 0;
        BlockState blockState = block.defaultBlockState();

        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            if (stack.getItem() instanceof SwordItem) continue; // Don't use swords

            double speed = calculateSpeedVsBlock(stack, blockState);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    /**
     * Calculate the best destruction time across all inventory items.
     */
    private double getBestDestructionTime(Block block) {
        int bestSlot = getBestSlot(block);
        if (bestSlot < 0) return calculateSpeedVsBlock(ItemStack.EMPTY, block.defaultBlockState());
        ItemStack stack = bot.getInventory().getItem(bestSlot);
        return calculateSpeedVsBlock(stack, block.defaultBlockState()) * potionAmplifier();
    }

    /**
     * Calculate how fast a specific item can break a specific block.
     * Returns speed (1/ticks), or -1 if unbreakable.
     * 
     * Adapted from Baritone's ToolSet.calculateSpeedVsBlock.
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            // Check for efficiency enchantment
            var itemEnchantments = item.getEnchantments();
            OUTER:
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect e : effects) {
                    if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().get())) {
                        speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /**
     * Calculate potion effect amplifier (haste, mining fatigue).
     */
    private double potionAmplifier() {
        double speed = 1;
        if (bot.hasEffect(MobEffects.DIG_SPEED)) {
            speed *= 1 + (bot.getEffect(MobEffects.DIG_SPEED).getAmplifier() + 1) * 0.2;
        }
        if (bot.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            switch (bot.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0: speed *= 0.3; break;
                case 1: speed *= 0.09; break;
                case 2: speed *= 0.0027; break;
                default: speed *= 0.00081; break;
            }
        }
        return speed;
    }

    /**
     * Get the number of ticks to break a block with the best tool.
     * Returns -1 if unbreakable. Includes environmental modifiers.
     */
    public double getBreakTicks(BlockState state) {
        double speed = getStrVsBlock(state);
        if (speed <= 0) return -1;
        // Environmental modifiers
        speed *= potionAmplifier();
        speed *= waterAmplifier();
        speed *= onGroundAmplifier();
        return 1.0 / speed;
    }

    /** Penalty when mining underwater (SUBMERGED_MINING_SPEED attribute). */
    private double waterAmplifier() {
        if (bot.isUnderWater()) {
            var attr = bot.getAttribute(Attributes.SUBMERGED_MINING_SPEED);
            if (attr != null) return attr.getValue();
            return 0.2; // default 5x slower
        }
        return 1.0;
    }

    /** Penalty when mining while not on ground (5x slower). */
    private double onGroundAmplifier() {
        return bot.onGround() ? 1.0 : 0.2;
    }

    /**
     * Check if the bot has a silk touch tool for a block.
     */
    public boolean hasSilkTouch(Block block) {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            var silkTouchHolder = bot.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).get(Enchantments.SILK_TOUCH); if (silkTouchHolder.isPresent() && EnchantmentHelper.getItemEnchantmentLevel(silkTouchHolder.get(), stack) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Clear the cached break strengths (e.g., when inventory changes).
     */
    public void clearCache() {
        breakStrengthCache.clear();
    }
}