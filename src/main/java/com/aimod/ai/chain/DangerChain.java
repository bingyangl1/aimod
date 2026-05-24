package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Danger avoidance chain. Handles lava, fire, deep falls, and drowning.
 * Priority 90 — only beaten by nothing (highest survival priority).
 */
public class DangerChain extends BehaviorChain {

    private boolean active;
    private int escapeTicks;
    private int cooldownTicks;
    private BlockPos lastDangerPos;
    private static final int COOLDOWN = 60; // 3 seconds after danger clears
    private static final int MAX_ESCAPE = 40;

    @Override public int priority() { return 90; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        if (cooldownTicks > 0) { cooldownTicks--; return false; }

        BlockPos pos = bot.blockPosition();
        var level = bot.level();
        BlockState feet = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());

        // Lava at feet or below — always activate
        if (feet.getBlock() == Blocks.LAVA || feet.getFluidState().isSource()) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }
        if (below.getBlock() == Blocks.LAVA) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }
        if (DangerZone.isLavaNearby(bot, 3)) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // On fire
        if (bot.isOnFire() && !bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Drowning
        if (bot.isInWater() && bot.getAirSupply() < 60) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }
        if (DangerZone.isDeepWater(bot) && bot.getAirSupply() < 150) {
            lastDangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Cliff ahead — only if position changed (not same cliff as before)
        if (bot.onGround() && DangerZone.isCliffAhead(bot)) {
            if (pos.equals(lastDangerPos)) return false; // already tried, don't loop
            lastDangerPos = pos;
            active = true; escapeTicks = 0;
            return true;
        }

        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        escapeTicks++;
        var level = bot.level();
        BlockPos pos = bot.blockPosition();

        // Use DangerZone to find safe direction
        Direction bestDir = DangerZone.findSafeDirection(bot);
        if (bestDir == null) {
            // Fallback: manual scan
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos target = pos.relative(dir);
                BlockState there = level.getBlockState(target);
                if (there.getBlock() == Blocks.LAVA || there.getFluidState().isSource()) continue;
                if (!level.getBlockState(target.below()).isSolid()) continue;
                bestDir = dir;
                break;
            }
        }

        if (bestDir != null) {
            double speed = 0.3;
            Vec3 move = new Vec3(bestDir.getStepX() * speed, 0, bestDir.getStepZ() * speed);
            if (bot.onGround() && (bot.isOnFire() || DangerZone.isLavaNearby(bot, 1))) {
                move = new Vec3(move.x, 0.42, move.z); // jump while escaping
            }
            if (bot.isInWater()) {
                move = new Vec3(move.x, 0.3, move.z); // swim up
            }
            bot.setDeltaMovement(move);
            bot.move(MoverType.SELF, move);
        }

        // Stop after max escape ticks or when safe (lava/fire/drowning only; cliff stays active for full duration)
        BlockState feet = level.getBlockState(bot.blockPosition());
        boolean urgentSafe = feet.getBlock() != Blocks.LAVA
                && !bot.isOnFire()
                && bot.getAirSupply() >= 60
                && !DangerZone.isLavaNearby(bot, 1);
        boolean cliffSafe = !DangerZone.isCliffAhead(bot);
        boolean forcedStop = escapeTicks > MAX_ESCAPE;

        if ((urgentSafe && cliffSafe) || forcedStop) {
            active = false;
            cooldownTicks = COOLDOWN;
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() {
        active = false; escapeTicks = 0;
        cooldownTicks = COOLDOWN;
    }
    @Override public String name() { return "Danger"; }
}
