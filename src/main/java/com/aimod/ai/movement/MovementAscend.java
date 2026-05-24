package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Diagonal ascend: move horizontally and up 1 block simultaneously.
 * Example: src=(0,64,0) to dest=(1,65,1).
 */
public class MovementAscend extends BotMovement {

    private static final double REACH_DIST_SQR = 0.3;
    private int stuckTicks;
    private double lastDistSqr;

    public MovementAscend(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();

        if (dy != 1) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) > 1 || Math.abs(dz) > 1) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) + Math.abs(dz) != 2) return Double.POSITIVE_INFINITY;

        BlockPos destFeet = dest;
        BlockPos destHead = dest.above();
        BlockState feetState = level.getBlockState(destFeet);
        BlockState headState = level.getBlockState(destHead);
        if (!MoveCost.canWalkThrough(level, destFeet, feetState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkThrough(level, destHead, headState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkOn(level, dest.below(), level.getBlockState(dest.below()))) return Double.POSITIVE_INFINITY;

        return MoveCost.WALK_ONE_BLOCK * MoveCost.DIAGONAL_MULTIPLIER + MoveCost.JUMP_ONE_BLOCK;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            status = Status.RUNNING;
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

        if (distSqr <= REACH_DIST_SQR && bot.getY() >= dest.getY() - 0.15) {
            bot.setPos(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
            bot.setDeltaMovement(0, 0, 0);
            status = Status.COMPLETE;
            return true;
        }

        if (distSqr >= lastDistSqr - 0.01 && bot.getY() < dest.getY() - 0.1) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastDistSqr = distSqr;
        if (stuckTicks > 50) {
            status = Status.FAILED;
            return true;
        }

        double distH = Math.sqrt(dx * dx + dz * dz);
        double speed = 0.216;
        double moveX = distH > 0.05 ? (dx / distH) * speed : 0;
        double moveZ = distH > 0.05 ? (dz / distH) * speed : 0;
        double moveY;

        if (bot.onGround() && distH < 1.2 && dy > 0.2) {
            moveY = 0.42;
        } else if (!bot.onGround()) {
            moveY = bot.getDeltaMovement().y;
        } else {
            moveY = 0;
        }

        Vec3 movement = new Vec3(moveX, moveY, moveZ);
        bot.setDeltaMovement(movement);
        bot.move(MoverType.SELF, movement);

        if (distH > 0.1) {
            float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
            bot.setYRot(yaw);
            bot.setYHeadRot(yaw);
        }

        return false;
    }
}