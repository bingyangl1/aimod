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

        // On fire
        if (bot.isOnFire() && !bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        // Drowning (air < 5 bubbles)
        if (bot.isInWater() && bot.getAirSupply() < 60) {
            dangerPos = pos; active = true; escapeTicks = 0; return true;
        }

        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        escapeTicks++;
        var level = bot.level();
        BlockPos pos = bot.blockPosition();

        // Find safe direction (no lava, no deep fall)
        Direction bestDir = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos target = pos.relative(dir);
            BlockPos belowTarget = target.below();
            BlockState there = level.getBlockState(target);
            BlockState belowThere = level.getBlockState(belowTarget);

            if (there.getBlock() == Blocks.LAVA || there.getFluidState().isSource()) continue;
            if (belowThere.getBlock() == Blocks.LAVA) continue;
            if (!belowThere.isSolid()) continue; // cliff

            double dist = target.distSqr(dangerPos);
            if (dist > bestDist) continue;
            bestDist = dist;
            bestDir = dir;
        }

        if (bestDir != null) {
            double speed = 0.3;
            Vec3 move = new Vec3(bestDir.getStepX() * speed, 0, bestDir.getStepZ() * speed);
            if (bot.onGround() && bot.isOnFire()) {
                move = new Vec3(move.x, 0.42, move.z); // jump while escaping fire
            }
            if (bot.isInWater()) {
                move = new Vec3(move.x, 0.3, move.z); // swim up
            }
            bot.setDeltaMovement(move);
            bot.move(MoverType.SELF, move);
        }

        // Stop after 40 ticks (2 seconds) or when safe
        BlockState feet = level.getBlockState(bot.blockPosition());
        boolean safe = feet.getBlock() != Blocks.LAVA && !bot.isOnFire() && bot.getAirSupply() >= 60;
        if (safe || escapeTicks > 40) {
            active = false;
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
        }
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() { active = false; escapeTicks = 0; }
    @Override public String name() { return "Danger"; }
}
