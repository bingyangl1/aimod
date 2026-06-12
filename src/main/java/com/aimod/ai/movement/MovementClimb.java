package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Climbing movement: ascend or descend on ladders and vines.
 * Handles vertical movement on climbable blocks.
 *
 * <p>Triggered when src and dest are on the same X/Z and there's a
 * climbable block (ladder/vine) at the position.</p>
 */
public class MovementClimb extends BotMovement {

    private static final double REACH_DIST_SQR = 0.3;
    private int stuckTicks;
    private double lastDistSqr;

    public MovementClimb(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dy = dest.getY() - src.getY();
        if (Math.abs(dy) != 1) return Double.POSITIVE_INFINITY;
        int adx = Math.abs(dest.getX() - src.getX());
        int adz = Math.abs(dest.getZ() - src.getZ());
        if (adx + adz > 1) return Double.POSITIVE_INFINITY;

        // Check if source or destination has a climbable block
        BlockState srcState = level.getBlockState(src);
        BlockState destState = level.getBlockState(dest);
        if (!isClimbable(srcState) && !isClimbable(destState)) {
            return Double.POSITIVE_INFINITY;
        }

        return MoveCost.WALK_ONE_BLOCK; // climbing is roughly same cost as walking
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

        // Check if reached destination
        if (distSqr <= REACH_DIST_SQR) {
            bot.setPos(dest.getX() + 0.5, dest.getY(), dest.getZ() + 0.5);
            bot.setDeltaMovement(0, 0, 0);
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

        // Move toward destination
        double distH = Math.sqrt(dx * dx + dz * dz);
        double speed = 0.2;
        double moveX = distH > 0.05 ? (dx / distH) * speed : 0;
        double moveZ = distH > 0.05 ? (dz / distH) * speed : 0;
        double moveY;

        // Climbing: move up or down based on ladder/vine
        if (dy > 0.3) {
            moveY = 0.2; // climb up
        } else if (dy < -0.3) {
            moveY = -0.2; // descend
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

    /**
     * Check if a block is climbable (ladder or vine).
     */
    public static boolean isClimbable(BlockState state) {
        return state.getBlock() instanceof LadderBlock
                || state.getBlock() instanceof VineBlock;
    }
}
