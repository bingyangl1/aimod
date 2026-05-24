package com.aimod.ai.action;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Breaks obstacles blocking the bot's path to a target.
 * Extracted from GatherResourceAction (P2-14).
 */
public class ObstacleBreaker {

    private int breakProgress;
    private BlockPos target;

    private static final int MAX_HARDNESS = 3;

    /**
     * Attempt to break an obstacle between the bot and its gather target.
     * @return true if breaking is in progress or complete
     */
    public boolean tryBreakObstacle(FakePlayer bot, BlockPos gatherTarget) {
        if (gatherTarget == null) return false;

        BlockPos botPos = bot.blockPosition();

        double dx = gatherTarget.getX() - botPos.getX();
        double dz = gatherTarget.getZ() - botPos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return false;

        int dirX = (int) Math.signum(dx);
        int dirZ = (int) Math.signum(dz);

        BlockPos[] candidates = {
            botPos.offset(dirX, 0, dirZ),
            botPos.offset(dirX, 1, dirZ),
            botPos.offset(dirX, -1, dirZ),
            botPos.offset(dirX, 0, 0),
            botPos.offset(0, 0, dirZ),
        };

        ServerLevel level = (ServerLevel) bot.level();

        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            float hardness = state.getDestroySpeed(level, pos);

            if (!state.isAir() && hardness >= 0 && hardness <= MAX_HARDNESS
                    && state.getFluidState().isEmpty()
                    && !MoveCost.avoidBreaking(level, pos, state)) {
                if (target == null || !target.equals(pos)) {
                    target = pos;
                    breakProgress = 0;
                }

                int breakTimeTicks = Math.max(10, (int) (hardness * 15));
                breakProgress++;

                bot.lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

                if (breakProgress >= breakTimeTicks) {
                    level.destroyBlock(pos, true, bot);
                    DevLog.info("OBSTACLE_BROKEN", "pos={}", pos.toShortString());
                    target = null;
                    breakProgress = 0;
                    return true;
                }
                return true;
            }
        }

        return false;
    }

    public void reset() {
        target = null;
        breakProgress = 0;
    }
}
