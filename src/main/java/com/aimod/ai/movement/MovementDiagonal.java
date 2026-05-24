package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Diagonal horizontal movement: move on both X and Z axes simultaneously.
 * Both intermediate corners must be passable to prevent corner-cutting through walls.
 *
 * Example: src=(0,64,0) to dest=(1,64,1) — requires (0,64,1) or (1,64,0) to be passable.
 */
public class MovementDiagonal extends BotMovement {

    private static final double REACH_DIST_SQR = 0.3;
    private int stuckTicks;
    private double lastDistSqr;

    public MovementDiagonal(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();

        if (dy != 0) return Double.POSITIVE_INFINITY;
        if (Math.abs(dx) != 1 || Math.abs(dz) != 1) return Double.POSITIVE_INFINITY;

        // Corner-cutting check: both intermediate positions must be passable
        BlockPos corner1 = src.offset(dx, 0, 0);
        BlockPos corner2 = src.offset(0, 0, dz);
        BlockState c1Feet = level.getBlockState(corner1);
        BlockState c2Feet = level.getBlockState(corner2);
        BlockState c1Head = level.getBlockState(corner1.above());
        BlockState c2Head = level.getBlockState(corner2.above());

        boolean c1Passable = MoveCost.canWalkThrough(level, corner1, c1Feet)
                && MoveCost.canWalkThrough(level, corner1.above(), c1Head);
        boolean c2Passable = MoveCost.canWalkThrough(level, corner2, c2Feet)
                && MoveCost.canWalkThrough(level, corner2.above(), c2Head);

        if (!c1Passable && !c2Passable) return Double.POSITIVE_INFINITY;

        // Check destination
        BlockPos destFeet = dest;
        BlockPos destHead = dest.above();
        BlockState feetState = level.getBlockState(destFeet);
        BlockState headState = level.getBlockState(destHead);
        if (!MoveCost.canWalkThrough(level, destFeet, feetState)) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkOn(level, dest.below(), level.getBlockState(dest.below()))) return Double.POSITIVE_INFINITY;
        if (!MoveCost.canWalkThrough(level, destHead, headState)) return Double.POSITIVE_INFINITY;

        double baseCost = MoveCost.WALK_ONE_BLOCK * MoveCost.DIAGONAL_MULTIPLIER;

        // Water penalty
        if (MoveCost.isWater(feetState)) {
            baseCost = MoveCost.WATER_COST * MoveCost.DIAGONAL_MULTIPLIER;
        }

        return baseCost;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            ServerLevel level = (ServerLevel) bot.level();
            BlockPos destFloor = dest.below();
            BlockState floorState = level.getBlockState(destFloor);
            BlockState feetState = level.getBlockState(dest);

            if (MoveCost.canWalkThrough(level, dest, feetState)
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
        double moveY;

        // Step-up if needed
        if (dy > 0.3 && dy <= 1.5 && bot.onGround()) {
            moveY = 0.42;
        } else if (!bot.onGround()) {
            moveY = bot.getDeltaMovement().y;
        } else {
            moveY = dy < -0.5 ? -0.4 : 0;
        }

        Vec3 movement = new Vec3(moveX, moveY, moveZ);
        bot.setDeltaMovement(movement);
        bot.move(MoverType.SELF, movement);

        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90;
        bot.setYRot(yaw);
        bot.setYHeadRot(yaw);

        return false;
    }
}