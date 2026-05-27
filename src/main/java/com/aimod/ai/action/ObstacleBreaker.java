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
     * Attempt to break an obstacle near the bot.
     * Scans all 6 adjacent directions (up/down/north/south/east/west)
     * and breaks the first breakable block found.
     * @return true if breaking is in progress or complete
     */
    public boolean tryBreakObstacle(FakePlayer bot, BlockPos gatherTarget) {
        BlockPos botPos = bot.blockPosition();
        ServerLevel level = (ServerLevel) bot.level();

        // Check all 6 adjacent directions + bot's own position
        BlockPos[] candidates = {
            botPos.north(), botPos.south(), botPos.east(), botPos.west(),
            botPos.above(), botPos.below(),
            botPos // in case bot is inside a block
        };

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
