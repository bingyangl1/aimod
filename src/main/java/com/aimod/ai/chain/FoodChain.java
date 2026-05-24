package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto-eating chain. Eats the best available food when hungry.
 * Priority 55 — just above user tasks but below survival/defense.
 *
 * <p>Food scoring: saturation * 2 - hunger_wasted, with rotten flesh penalty (-100).</p>
 */
public class FoodChain extends BehaviorChain {

    private static final int HUNGER_THRESHOLD = 14;
    private boolean active;
    private int eatTicks;
    private int originalSlot = -1;

    @Override public int priority() { return 55; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        int threshold = com.aimod.config.ModConfig.getHungerThreshold();
        if (bot.getFoodData().getFoodLevel() > threshold) return false;
        if (bot.getFoodData().needsFood()) {
            active = true;
            eatTicks = 0;
            return true;
        }
        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        if (eatTicks == 0) {
            int bestSlot = findBestFood(bot);
            if (bestSlot < 0) {
                active = false;
                return;
            }
            // Move food to hotbar if needed
            originalSlot = bot.getInventory().selected;
            if (bestSlot < 9) {
                bot.getInventory().selected = bestSlot;
            } else {
                // Swap to hotbar slot 0
                var inv = bot.getInventory();
                ItemStack tmp = inv.getItem(0);
                inv.setItem(0, inv.getItem(bestSlot));
                inv.setItem(bestSlot, tmp);
                inv.selected = 0;
            }
        }
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        eatTicks++;
        if (eatTicks > 40) {
            bot.stopUsingItem();
            if (originalSlot >= 0) bot.getInventory().selected = originalSlot;
            active = false;
        }
    }

    /**
     * Score-based food selection (Player2NPC FoodChain algorithm).
     * Score = saturation * 2 - wasted_hunger, rotten flesh = -100.
     */
    static int findBestFood(FakePlayer bot) {
        int bestSlot = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        int maxHunger = 20 - bot.getFoodData().getFoodLevel();
        var inv = bot.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            FoodProperties food = stack.getItem().getFoodProperties(stack, bot);
            if (food == null) continue;

            double score;
            if (stack.getItem() == Items.ROTTEN_FLESH) {
                score = -100;
            } else {
                int wasted = Math.max(0, food.nutrition() - maxHunger);
                score = food.saturation() * 2.0 - wasted;
            }
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() {
        active = false;
        eatTicks = 0;
    }
    @Override public String name() { return "Food"; }
}
