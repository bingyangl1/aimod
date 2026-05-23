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
    private BlockPos dangerPos;

    @Override public int priority() { return 90; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        BlockPos pos = bot.blockPosition();
        var level = bot.level();
        BlockState feet = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());

        // Lava at feet or below
        if (feet.getBlock() == Blocks.LAVA || feet.getFluidState().isSource()) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }
        if (below.getBlock() == Blocks.LAVA) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Lava nearby (3 block radius)
        if (DangerZone.isLavaNearby(bot, 3)) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // On fire
        if (bot.isOnFire() && !bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Drowning (air < 5 bubbles)
        if (bot.isInWater() && bot.getAirSupply() < 60) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Deep water (avoid drowning risk before it happens)
        if (DangerZone.isDeepWater(bot) && bot.getAirSupply() < 150) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Cliff ahead
        if (bot.onGround() && DangerZone.isCliffAhead(bot)) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
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

        // Stop after 40 ticks (2 seconds) or when safe
        BlockState feet = level.getBlockState(bot.blockPosition());
        boolean safe = feet.getBlock() != Blocks.LAVA
                && !bot.isOnFire()
                && bot.getAirSupply() >= 60
                && !DangerZone.isLavaNearby(bot, 1)
                && !DangerZone.isCliffAhead(bot);
        if (safe || escapeTicks > 40) {
            active = false;
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() { active = false; escapeTicks = 0; }
    @Override public String name() { return "Danger"; }
}
