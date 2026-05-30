package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * PvP defense chain. Retreats or shields when attacked by other players.
 * Priority 65 — below DefenseChain (70) but above Food/Unstuck.
 *
 * <p>Only activates when enablePvpDefense config is true.
 * Detects nearby hostile players (non-owner) within 16 blocks.</p>
 */
public class PlayerDefenseChain extends BehaviorChain {

    private static final double SCAN_RADIUS = 16.0;
    private static final int MAX_ACTIVE_TICKS = 100; // 5 seconds max
    private static final int POST_DEFENSE_COOLDOWN = 60; // 3s cooldown
    private static final double RETREAT_DISTANCE = 24.0;

    private boolean active;
    private int activeTicks;
    private int cooldownTicks;
    private Player hostilePlayer;
    private FakePlayer lastBot;

    @Override
    public int priority() {
        return 65;
    }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        if (!com.aimod.config.ModConfig.getEnablePvpDefense()) return false;
        if (cooldownTicks > 0) { cooldownTicks--; return false; }

        // Find nearby hostile players (non-owner)
        AABB box = bot.getBoundingBox().inflate(SCAN_RADIUS);
        List<Player> nearbyPlayers = bot.level().getEntitiesOfClass(Player.class, box,
                p -> p.isAlive() && !p.isDeadOrDying() && !p.equals(bot) && !isOwner(bot, p));

        if (nearbyPlayers.isEmpty()) return false;

        // Check if any player is attacking (holding weapon, looking at bot)
        for (Player p : nearbyPlayers) {
            if (isHostileIntent(bot, p)) {
                hostilePlayer = p;
                active = true;
                activeTicks = 0;
                return true;
            }
        }

        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        lastBot = bot;
        activeTicks++;

        if (activeTicks > MAX_ACTIVE_TICKS) {
            active = false;
            cooldownTicks = POST_DEFENSE_COOLDOWN;
            return;
        }

        if (hostilePlayer == null || !hostilePlayer.isAlive()) {
            active = false;
            cooldownTicks = POST_DEFENSE_COOLDOWN;
            return;
        }

        double dist = bot.distanceToSqr(hostilePlayer);

        // If too close, try to retreat
        if (dist < 16.0) { // 4 blocks
            retreat(bot, hostilePlayer);
        }

        // Try to raise shield if available
        if (dist < 9.0) { // 3 blocks
            tryShield(bot);
        }
    }

    @Override
    public boolean isActive() { return active; }

    @Override
    public void stop() {
        active = false;
        if (lastBot != null) {
            restoreShield(lastBot);
        }
    }

    @Override
    public String name() { return "PlayerDefense"; }

    // ========== Internal ==========

    private boolean isOwner(FakePlayer bot, Player player) {
        String ownerName = bot.getAiManager().getFeedback().getOwnerName();
        return ownerName != null && ownerName.equals(player.getName().getString());
    }

    private boolean isHostileIntent(FakePlayer bot, Player player) {
        // Simple heuristic: player is holding a weapon and looking toward bot
        ItemStack mainHand = player.getMainHandItem();
        boolean holdingWeapon = isWeapon(mainHand);

        // Check if player is close enough to be a threat
        double dist = bot.distanceToSqr(player);
        if (dist > 36.0) return false; // 6 blocks

        // If holding weapon and within 6 blocks, consider hostile
        if (holdingWeapon) return true;

        // If within 3 blocks, consider hostile regardless
        return dist < 9.0;
    }

    private boolean isWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        // Use instanceof checks for reliability (class names can be obfuscated)
        return stack.getItem() instanceof net.minecraft.world.item.SwordItem
            || stack.getItem() instanceof net.minecraft.world.item.AxeItem
            || stack.getItem() instanceof net.minecraft.world.item.TridentItem
            || stack.getItem() instanceof net.minecraft.world.item.BowItem
            || stack.getItem() instanceof net.minecraft.world.item.CrossbowItem;
    }

    private void retreat(FakePlayer bot, Player threat) {
        // Move away from the threat
        double dx = bot.getX() - threat.getX();
        double dz = bot.getZ() - threat.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.1) {
            double speed = 0.25; // sprint speed
            double nx = dx / dist;
            double nz = dz / dist;
            bot.setDeltaMovement(nx * speed, bot.getDeltaMovement().y, nz * speed);
        }
    }

    private void tryShield(FakePlayer bot) {
        // Check if bot has a shield in offhand
        ItemStack offhand = bot.getOffhandItem();
        if (offhand.getItem() instanceof ShieldItem) {
            // Activate shield (start using item)
            bot.startUsingItem(InteractionHand.OFF_HAND);
        }
    }

    private void restoreShield(FakePlayer bot) {
        bot.stopUsingItem();
    }
}
