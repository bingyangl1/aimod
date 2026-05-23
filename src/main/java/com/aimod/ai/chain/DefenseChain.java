package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Self-defense chain. Attacks hostile mobs within 8 blocks.
 * Priority 70 — below danger but above everything else.
 */
public class DefenseChain extends BehaviorChain {

    private static final double SCAN_RADIUS = 8.0;
    private static final double RETREAT_HEALTH = 6.0;

    private boolean active;
    private LivingEntity target;
    private int attackCooldown;

    @Override public int priority() { return 70; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        AABB box = bot.getBoundingBox().inflate(SCAN_RADIUS);
        List<LivingEntity> hostiles = bot.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e instanceof Monster && e.isAlive() && !e.isDeadOrDying());

        if (hostiles.isEmpty()) return false;

        // Find closest hostile
        double best = SCAN_RADIUS * SCAN_RADIUS;
        for (LivingEntity e : hostiles) {
            double d = bot.distanceToSqr(e);
            if (d < best) { best = d; target = e; }
        }

        active = true;
        attackCooldown = 0;
        return true;
    }

    @Override
    public void tick(FakePlayer bot) {
        if (target == null || !target.isAlive()) {
            active = false;
            return;
        }

        // Retreat if low health
        if (bot.getHealth() <= RETREAT_HEALTH) {
            Vec3 away = bot.position().subtract(target.position()).normalize().scale(0.25);
            bot.setDeltaMovement(away.x, bot.getDeltaMovement().y, away.z);
            bot.move(MoverType.SELF, new Vec3(away.x, bot.getDeltaMovement().y, away.z));
            if (bot.distanceToSqr(target) > 64) { active = false; }
            return;
        }

        // Face target
        bot.lookAt(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());

        // Equip best weapon
        equipBestWeapon(bot);

        // Move toward target if far
        double dist = bot.distanceToSqr(target);
        if (dist > 6.25) {
            Vec3 toward = target.position().subtract(bot.position()).normalize().scale(0.2);
            bot.setDeltaMovement(toward.x, bot.getDeltaMovement().y, toward.z);
            bot.move(MoverType.SELF, new Vec3(toward.x, bot.getDeltaMovement().y, toward.z));
        }

        // Attack when close enough
        if (dist < 9.0 && attackCooldown <= 0) {
            bot.attack(target);
            bot.swing(InteractionHand.MAIN_HAND);
            attackCooldown = 10; // 0.5 second cooldown
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    private void equipBestWeapon(FakePlayer bot) {
        // Find best melee weapon in hotbar (slots 0-8)
        int bestSlot = -1;
        float bestDamage = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && (stack.getItem() instanceof SwordItem)) {
                float dmg = 4.0f; // base sword damage
                if (stack.getItem() == net.minecraft.world.item.Items.NETHERITE_SWORD) dmg = 5.0f;
                else if (stack.getItem() == net.minecraft.world.item.Items.DIAMOND_SWORD) dmg = 4.0f;
                else if (stack.getItem() == net.minecraft.world.item.Items.IRON_SWORD) dmg = 3.0f;
                else if (stack.getItem() == net.minecraft.world.item.Items.STONE_SWORD) dmg = 2.0f;
                if (dmg > bestDamage) { bestDamage = dmg; bestSlot = i; }
            } else if (!stack.isEmpty() && (stack.getItem() instanceof AxeItem)) {
                float dmg = 3.0f;
                if (dmg > bestDamage) { bestDamage = dmg; bestSlot = i; }
            }
        }
        if (bestSlot >= 0 && bestSlot < 9) {
            bot.getInventory().selected = bestSlot;
        } else if (bestSlot >= 9) {
            // Move from inventory to hotbar slot 0
            var inv = bot.getInventory();
            ItemStack tmp = inv.getItem(0);
            inv.setItem(0, inv.getItem(bestSlot));
            inv.setItem(bestSlot, tmp);
            inv.selected = 0;
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() { active = false; target = null; }
    @Override public String name() { return "Defense"; }
}
