package com.example.aimod.ai.action;

import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.WorldScanner;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

public class MineBlockAction extends Action {
    private static final int STUCK_TIMEOUT = 200;

    private final String blockId;
    private final int count;
    private final int searchRadius;

    private BlockPos currentTarget;
    private int minedCount;
    private int breakProgress;
    private int breakTime;
    private boolean searching;

    private double lastDistSqr;
    private int stuckTicks;

    public MineBlockAction(String blockId, int count) {
        this(blockId, count, 32);
    }

    public MineBlockAction(String blockId, int count, int searchRadius) {
        super("Mine " + count + " " + blockId);
        this.blockId = blockId;
        this.count = Math.max(1, count);
        this.searchRadius = searchRadius;
        this.minedCount = 0;
        this.breakProgress = 0;
        this.breakTime = 0;
        this.searching = true;
        this.lastDistSqr = Double.MAX_VALUE;
        this.stuckTicks = 0;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        return true;
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            searching = true;
            DevLog.info("MINE_START", "block={}, count={}, radius={}", blockId, count, searchRadius);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            if (minedCount >= count) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MINE_DONE", "block={}, mined={}", blockId, minedCount);
                return;
            }

            if (currentTarget == null || searching) {
                WorldScanner scanner = new WorldScanner(bot);
                currentTarget = scanner.findNearestBlock(blockId, searchRadius);
                searching = false;
                lastDistSqr = Double.MAX_VALUE;
                stuckTicks = 0;

                if (currentTarget == null) {
                    DevLog.warn("MINE_NO_BLOCK_FOUND", "block={}, radius={}", blockId, searchRadius);
                    status = ActionStatus.FAILED;
                    return;
                }

                DevLog.info("MINE_FOUND", "block={}, pos={}", blockId, currentTarget.toShortString());
            }

            BlockState blockState = bot.level().getBlockState(currentTarget);
            if (blockState.isAir()) {
                minedCount++;
                DevLog.info("MINE_MINED", "block={}, total={}", blockId, minedCount);
                currentTarget = null;
                searching = true;
                breakProgress = 0;
                return;
            }

            double distSqr = navigateTo(bot, currentTarget, 1.0);

            if (distSqr > 6.25) {
                if (distSqr >= lastDistSqr - 0.01) {
                    stuckTicks++;
                } else {
                    stuckTicks = 0;
                }
                lastDistSqr = distSqr;

                if (stuckTicks > STUCK_TIMEOUT) {
                    DevLog.warn("MINE_STUCK", "block={}, target={}, dist={}, skipping",
                            blockId, currentTarget.toShortString(),
                            String.format("%.1f", Math.sqrt(distSqr)));
                    currentTarget = null;
                    searching = true;
                    stuckTicks = 0;
                }
                return;
            }

            stopNavigation(bot);

            if (breakProgress == 0) {
                float hardness = blockState.getDestroySpeed(bot.level(), currentTarget);
                breakTime = Math.max(20, (int) (hardness * 20));
                DevLog.info("MINE_BREAKING", "block={}, pos={}, breakTime={}",
                        blockId, currentTarget.toShortString(), breakTime);
            }

            FakePlayer fakePlayer = getFakePlayer(bot);
            if (fakePlayer != null) {
                fakePlayer.lookAt(
                        currentTarget.getX() + 0.5,
                        currentTarget.getY() + 0.5,
                        currentTarget.getZ() + 0.5);
            }

            breakProgress++;
            if (breakProgress >= breakTime) {
                Entity destroyer = fakePlayer != null ? fakePlayer : bot;
                bot.level().destroyBlock(currentTarget, true, destroyer);
                minedCount++;
                currentTarget = null;
                searching = true;
                breakProgress = 0;

                DevLog.info("MINE_MINED", "block={}, total={}", blockId, minedCount);
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public String getBlockId() { return blockId; }
    public int getCount() { return count; }
    public int getMinedCount() { return minedCount; }
}