package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Descend 1 block in a cardinal direction (dy = -1).
 * The destination must have a solid floor and passable feet/head.
 * Falls are handled by MovementFall instead.
 */
public class MovementDescend extends BotMovement {

    private static final double REACH_DIST_SQR = 0.36; // 0.6 blocks
    private int stuckTicks;
    private double lastDistSqr;

    public MovementDescend(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();
        if (dy != -1) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) + Math.abs(dz) != 1) return Double.POSITIVE_INFINITY;

        return MoveCost.costOf(level, src.getX(), src.getY(), src.getZ(), dx, dy, dz);
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos destFeet = dest;
            BlockPos destFloor = dest.below();
            BlockState floorState = level.getBlockState(destFloor);
            BlockState feetState = level.getBlockState(destFeet);

            if (MoveCost.canWalkThrough(level, destFeet, feetState)
                    && MoveCost.canWalkOn(level, destFloor, floorState)) {
                status = Status.RUNNING;
                return true;
            }
            status = Status.FAILED;
            return false;
        }
        return status == Status.RUNNING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) canExecute(bot);
        if (status == Status.FAILED || status == Status.COMPLETE) return true;

        double dx = dest.getX() + 0.5 - bot.getX();
        double dy = dest.getY() - bot.getY();
        double dz = dest.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        if (distSqr <= REACH_DIST_SQR) {
            bot.setPos(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);
            status = Status.COMPLETE;
            return true;
        }

        // Stuck detection
        if (distSqr >= lastDistSqr - 0.01) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastDistSqr = distSqr;
        if (stuckTicks > 40) {
            status = Status.FAILED;
            return true;
        }

        double dist = Math.sqrt(distSqr);
        double speed = 0.216;
        double moveX = (dx / dist) * speed;
        double moveZ = (dz / dist) * speed;
        double moveY = bot.onGround() ? -0.4 : bot.getDeltaMovement().y;

        Vec3 movement = new Vec3(moveX, moveY, moveZ);
        bot.setDeltaMovement(movement);
        bot.move(MoverType.SELF, movement);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);

        return false;
    }
}