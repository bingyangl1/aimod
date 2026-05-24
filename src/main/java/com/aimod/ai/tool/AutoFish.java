package com.aimod.ai.tool;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/**
 * Auto-fishing: reels in when a fish bites.
 * Detects vanilla FishingHook bobber state.
 *
 * <p>Inspired by SiliconeDolls' FakePlayerAutoFish.
 */
public final class AutoFish {
    private AutoFish() {}

    private static final int CAST_COOLDOWN = 20;
    private static final int REEL_IN_DELAY = 15; // ticks after bite
    private int castCooldown;
    private int biteTimer = -1;
    private boolean casting;

    private static final java.util.Map<java.util.UUID, AutoFish> instances = new java.util.HashMap<>();

    public static AutoFish of(FakePlayer bot) {
        return instances.computeIfAbsent(bot.getUUID(), k -> new AutoFish());
    }

    /**
     * Tick auto-fishing logic. Call from FakePlayer.tick().
     */
    public void tick(FakePlayer bot) {
        if (castCooldown > 0) {
            castCooldown--;
            return;
        }

        var held = bot.getMainHandItem();
        if (held.getItem() != Items.FISHING_ROD) {
            casting = false;
            biteTimer = -1;
            return;
        }

        // Check if we have a bobber in the water
        var fishingHook = bot.fishing;
        if (fishingHook == null || fishingHook.isRemoved()) {
            if (!casting) {
                // Cast
                bot.swing(InteractionHand.MAIN_HAND);
                bot.startUsingItem(InteractionHand.MAIN_HAND);
                bot.stopUsingItem(); // release to cast
                casting = true;
                castCooldown = CAST_COOLDOWN;
            }
            return;
        }

        // We have a bobber — check if fish is biting
        if (fishingHook.getHookedIn() != null && biteTimer < 0) {
            biteTimer = REEL_IN_DELAY;
        }

        if (biteTimer > 0) {
            biteTimer--;
            return;
        }

        if (biteTimer == 0) {
            // Reel in!
            bot.swing(InteractionHand.MAIN_HAND);
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            bot.stopUsingItem();
            biteTimer = -1;
            casting = false;
            castCooldown = CAST_COOLDOWN;
        }
    }

    /** Clean up when bot is removed. */
    public static void remove(FakePlayer bot) {
        instances.remove(bot.getUUID());
    }
}
