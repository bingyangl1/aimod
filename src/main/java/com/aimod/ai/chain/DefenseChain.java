package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.item.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Self-defense chain. Attacks hostile mobs within 8 blocks.
 * Priority 70 — below danger but above everything else.
 *
 * <p>Features: melee combat, retreat when overwhelmed, shield blocking,
 * ranged counter against skeletons.</p>
 */
public class DefenseChain extends BehaviorChain {

    private static final double SCAN_RADIUS = 8.0;
    private static final double RETREAT_HEALTH = 6.0;
    private static final int OVERWHELM_COUNT = 3;
    private static final double RANGED_RANGE = 10.0;

    private boolean active;
    private LivingEntity target;
    private int attackCooldown;
    private int hostileCount;
    private boolean retreating;
    private boolean shielding;

    @Override public int priority() { return 70; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        AABB box = bot.getBoundingBox().inflate(SCAN_RADIUS);
        List<LivingEntity> hostiles = bot.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e instanceof Monster && e.isAlive() && !e.isDeadOrDying());

        if (hostiles.isEmpty()) return false;
        hostileCount = hostiles.size();

        // Find closest hostile
        double best = SCAN_RADIUS * SCAN_RADIUS;
        for (LivingEntity e : hostiles) {
            double d = bot.distanceToSqr(e);
            if (d < best) { best = d; target = e; }
        }

        active = true;
        attackCooldown = 0;
        retreating = false;
        shielding = false;
        return true;
    }

    @Override
    public void tick(FakePlayer bot) {
        if (target == null || !target.isAlive()) {
            active = false;
            return;
        }

        double dist = bot.distanceToSqr(target);

        // Decision: retreat, shield, or fight
        if (shouldRetreat(bot)) {
            retreat(bot, dist);
            return;
        }

        // Shield block incoming projectiles (skeletons)
        if (shouldShield(bot)) {
            shield(bot);
            return;
        }
        shielding = false;

        // Ranged counter: use bow against distant skeletons
        if (target instanceof Skeleton && dist > 9.0 && dist < RANGED_RANGE * RANGED_RANGE) {
            rangedAttack(bot, dist);
            return;
        }

        // Melee
        bot.lookAt(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());
        equipBestWeapon(bot);

        if (dist > 6.25) {
            Vec3 toward = target.position().subtract(bot.position()).normalize().scale(0.2);
            bot.setDeltaMovement(toward.x, bot.getDeltaMovement().y, toward.z);
            bot.move(MoverType.SELF, new Vec3(toward.x, bot.getDeltaMovement().y, toward.z));
        }

        if (dist < 9.0 && attackCooldown <= 0) {
            bot.attack(target);
            bot.swing(InteractionHand.MAIN_HAND);
            attackCooldown = 10;
        }
        if (attackCooldown > 0) attackCooldown--;
    }

    private boolean shouldRetreat(FakePlayer bot) {
        return bot.getHealth() <= RETREAT_HEALTH || hostileCount >= OVERWHELM_COUNT;
    }

    private void retreat(FakePlayer bot, double dist) {
        retreating = true;
        Vec3 away = bot.position().subtract(target.position()).normalize().scale(0.25);
        bot.setDeltaMovement(away.x, bot.getDeltaMovement().y, away.z);
        bot.move(MoverType.SELF, new Vec3(away.x, bot.getDeltaMovement().y, away.z));
        // Look back at target while retreating
        bot.lookAt(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());
        if (dist > 64) active = false; // far enough
    }

    private boolean shouldShield(FakePlayer bot) {
        return target instanceof Skeleton && bot.getOffhandItem().getItem() == Items.SHIELD;
    }

    private void shield(FakePlayer bot) {
        shielding = true;
        bot.lookAt(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());
        bot.startUsingItem(InteractionHand.OFF_HAND); // raise shield
        // Move sideways to dodge
        Vec3 strafe = bot.position().subtract(target.position()).normalize();
        double sx = -strafe.z * 0.15;
        double sz = strafe.x * 0.15;
        bot.setDeltaMovement(sx, bot.getDeltaMovement().y, sz);
        bot.move(MoverType.SELF, new Vec3(sx, bot.getDeltaMovement().y, sz));
    }

    private void rangedAttack(FakePlayer bot, double dist) {
        bot.lookAt(target.getX(), target.getY() + target.getBbHeight() / 2, target.getZ());
        // Equip bow if available
        int bowSlot = findBow(bot);
        if (bowSlot >= 0) {
            if (bowSlot < 9) bot.getInventory().selected = bowSlot;
            // Use bow
            bot.startUsingItem(InteractionHand.MAIN_HAND);
            // Release arrow after a short hold
            if (attackCooldown <= -10) {
                bot.stopUsingItem();
                attackCooldown = 20;
            }
            attackCooldown--;
        }
    }

    private int findBow(FakePlayer bot) {
        for (int i = 0; i < 9; i++) {
            if (bot.getInventory().getItem(i).getItem() == Items.BOW) return i;
        }
        return -1;
    }

    private void equipBestWeapon(FakePlayer bot) {
        int bestSlot = -1;
        float bestDamage = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof SwordItem) {
                float dmg = 4.0f;
                if (stack.getItem() == Items.NETHERITE_SWORD) dmg = 5.0f;
                else if (stack.getItem() == Items.DIAMOND_SWORD) dmg = 4.0f;
                else if (stack.getItem() == Items.IRON_SWORD) dmg = 3.0f;
                else if (stack.getItem() == Items.STONE_SWORD) dmg = 2.0f;
                if (dmg > bestDamage) { bestDamage = dmg; bestSlot = i; }
            } else if (!stack.isEmpty() && stack.getItem() instanceof AxeItem) {
                float dmg = 3.0f;
                if (dmg > bestDamage) { bestDamage = dmg; bestSlot = i; }
            }
        }
        if (bestSlot >= 0 && bestSlot < 9) {
            bot.getInventory().selected = bestSlot;
        } else if (bestSlot >= 9) {
            var inv = bot.getInventory();
            ItemStack tmp = inv.getItem(0);
            inv.setItem(0, inv.getItem(bestSlot));
            inv.setItem(bestSlot, tmp);
            inv.selected = 0;
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() {
        active = false; target = null; retreating = false; shielding = false;
    }
    @Override public String name() { return "Defense"; }
}
