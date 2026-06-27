package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.ai.pathing.MoveCost;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Parkour movement — sprint-jump over 2-4 block gaps.
 *
 * <p>Inspired by Baritone's PARKOUR movement type. When the bot needs to
 * cross a gap that's too wide for a normal jump, this movement sprints
 * and jumps to clear the gap.</p>
 */
public class MovementParkour extends BotMovement {

    private static final int MAX_PARKOUR_TICKS = 60; // 3 seconds max

    private int ticks;

    public MovementParkour(BlockPos src, BlockPos dest) {
        super(src, dest);
        this.ticks = 0;
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = Math.abs(dest.getX() - src.getX());
        int dz = Math.abs(dest.getZ() - src.getZ());
        double dist = Math.sqrt(dx * dx + dz * dz);
        // Parkour is faster than walking around, but has risk
        return dist * 0.5;
    }

    @Override
    public boolean update(FakePlayer bot) {
        ticks++;

        if (ticks > MAX_PARKOUR_TICKS) {
            status = Status.FAILED;
            DevLog.warn("PARKOUR_TIMEOUT", "src={}, dest={}", src.toShortString(), dest.toShortString());
            return true;
        }

        double dx = dest.getX() + 0.5 - bot.getX();
        double dy = dest.getY() - bot.getY();
        double dz = dest.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        // Close enough — landed
        if (distSqr < 2.0 && bot.onGround()) {
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
            status = Status.COMPLETE;
            return true;
        }

        // Sprint toward destination
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 0.1) {
            double speed = 0.28; // sprint speed
            double moveX = (dx / dist) * speed;
            double moveZ = (dz / dist) * speed;
            bot.setDeltaMovement(moveX, bot.getDeltaMovement().y, moveZ);
        }

        // Jump when at edge
        double distFromSrc = bot.distanceToSqr(src.getX() + 0.5, src.getY(), src.getZ() + 0.5);
        if (distFromSrc < 4.0 && bot.onGround()) {
            bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
        }

        return false;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (!(bot.level() instanceof ServerLevel level)) return false;

        // Check destination is walkable
        BlockPos destFloor = dest.below();
        BlockState destFloorState = level.getBlockState(destFloor);
        if (!MoveCost.canWalkOn(level, destFloor, destFloorState)) return false;

        BlockState destFeet = level.getBlockState(dest);
        BlockState destHead = level.getBlockState(dest.above());
        if (!destFeet.isAir() || !destHead.isAir()) return false;

        return true;
    }
}
