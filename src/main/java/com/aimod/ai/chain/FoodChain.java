package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Auto-eating chain. Eats the best available food when hungry.
 * Priority 55 — just above user tasks but below survival/defense.
 */
public class FoodChain extends BehaviorChain {

    private static final int HUNGER_THRESHOLD = 14;
    private boolean active;
    private int eatTicks;
    private int originalSlot = -1;

    @Override public int priority() { return 55; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        if (bot.getFoodData().getFoodLevel() > HUNGER_THRESHOLD) return false;
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
            // Find best food in inventory
            int bestSlot = -1;
            int bestNutrition = 0;
            float bestSaturation = 0;
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                FoodProperties food = stack.getItem().getFoodProperties(stack, bot);
                if (food == null) continue;
                if (food.nutrition() > bestNutrition ||
                    (food.nutrition() == bestNutrition && food.saturation() > bestSaturation)) {
                    bestNutrition = food.nutrition();
                    bestSaturation = food.saturation();
                    bestSlot = i;
                }
            }

            if (bestSlot < 0) {
                active = false;
                return;
            }

            // Move food to hand
            originalSlot = bot.getInventory().selected;
            if (bestSlot < 9) {
                bot.getInventory().selected = bestSlot;
            }
        }

        // Hold right-click to eat
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        eatTicks++;

        // Eating typically takes 32 ticks (1.6s)
        if (eatTicks > 40) {
            bot.stopUsingItem();
            if (originalSlot >= 0) bot.getInventory().selected = originalSlot;
            active = false;
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() {
        active = false;
        eatTicks = 0;
    }
    @Override public String name() { return "Food"; }
}
