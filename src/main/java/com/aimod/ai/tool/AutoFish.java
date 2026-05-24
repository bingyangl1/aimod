package com.aimod.ai.tool;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-fishing: cycle-based cast → wait → reel.
 * Uses a simple timed cycle since FishingHook bite state
 * is not directly accessible without access transformers.
 *
 * <p>Cycle: cast → wait 15-30s (random) → reel → repeat.
 * Simple but effective for most fishing scenarios.</p>
 */
public final class AutoFish {
    private AutoFish() {}

    private static final int CAST_COOLDOWN = 20;
    private static final int MIN_WAIT_TICKS = 300;  // 15 seconds
    private static final int MAX_WAIT_TICKS = 600;  // 30 seconds
    private static final int REEL_DELAY = 5;

    private int castCooldown;
    private int waitTicks;
    private int reelTimer = -1;
    private boolean hasBobber;

    private static final Map<UUID, AutoFish> instances = new ConcurrentHashMap<>();

    public static AutoFish of(FakePlayer bot) {
        return instances.computeIfAbsent(bot.getUUID(), k -> new AutoFish());
    }

    public void tick(FakePlayer bot) {
        if (castCooldown > 0) { castCooldown--; return; }
        if (reelTimer > 0) { reelTimer--; return; }

        var held = bot.getMainHandItem();
        if (held.getItem() != Items.FISHING_ROD) {
            hasBobber = false; waitTicks = 0; reelTimer = -1; return;
        }

        var hook = bot.fishing;
        boolean bobberActive = hook != null && !hook.isRemoved();

        // Bobber disappeared — fish caught, start cooldown
        if (!bobberActive && hasBobber) {
            hasBobber = false;
            castCooldown = CAST_COOLDOWN;
            waitTicks = 0;
            return;
        }
        hasBobber = bobberActive;

        // No bobber — cast
        if (!bobberActive) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            reelTimer = 2; // release after 2 ticks (charge the cast)
            waitTicks = CAST_COOLDOWN + (int)(Math.random() * (MAX_WAIT_TICKS - MIN_WAIT_TICKS) + MIN_WAIT_TICKS);
            return;
        }

        // Reel timer triggered — reel in
        if (reelTimer == 0) {
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            reelTimer = -1;
            castCooldown = CAST_COOLDOWN;
            return;
        }

        // Waiting for bite — count down
        if (waitTicks > 0) {
            waitTicks--;
            if (waitTicks <= 0) {
                // Time to reel
                reelTimer = REEL_DELAY;
            }
        }
    }

    public static void remove(FakePlayer bot) {
        instances.remove(bot.getUUID());
    }
}
