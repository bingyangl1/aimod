package com.aimod.ai.action;

import com.aimod.ai.WorldScanner;
import com.aimod.ai.pathing.Pathfinder;
import com.aimod.ai.pathing.PathResult;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    // Pathfinding state
    private List<BlockPos> currentPath;
    private int pathIndex;

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
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
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
                currentPath = null;
                pathIndex = 0;

                if (currentTarget == null) {
                    DevLog.warn("MINE_NO_BLOCK_FOUND", "block={}, radius={}", blockId, searchRadius);
                    status = ActionStatus.FAILED;
                    return;
                }

                DevLog.info("MINE_FOUND", "block={}, pos={}", blockId, currentTarget.toShortString());
                computePath(bot);
            }

            BlockState blockState = bot.level().getBlockState(currentTarget);
            if (blockState.isAir()) {
                minedCount++;
                DevLog.info("MINE_MINED", "block={}, total={}", blockId, minedCount);
                currentTarget = null;
                searching = true;
                breakProgress = 0;
                currentPath = null;
                return;
            }

            double distSqr = followPath(bot);

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
                    currentPath = null;
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

            FakePlayer fakePlayer = bot;
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
                DevLog.info("MINE_MINED", "block={}, total={}", blockId, minedCount);
                currentTarget = null;
                searching = true;
                breakProgress = 0;
                currentPath = null;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private void computePath(FakePlayer bot) {
        if (!(bot.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        BlockPos botPos = bot.blockPosition();
        BlockPos approachPos = currentTarget.below();

        DevLog.info("MINE_PATH_COMPUTE", "from={}, to={}", botPos.toShortString(), approachPos.toShortString());

        Pathfinder pathfinder = new Pathfinder(serverLevel, botPos, approachPos);
        PathResult result = pathfinder.findPath();

        if (result.isFound() && result.getLength() >= 2) {
            currentPath = result.getPath();
            pathIndex = 1;
            DevLog.info("MINE_PATH_FOUND", "length={}, nodes={}", result.getLength(), result.getNodesExplored());
        } else {
            currentPath = null;
            DevLog.info("MINE_PATH_FALLBACK", "reason=no_path, using_direct_navigate");
        }
    }

    private double followPath(FakePlayer bot) {
        double dx = currentTarget.getX() + 0.5 - bot.getX();
        double dy = currentTarget.getY() - bot.getY();
        double dz = currentTarget.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        if (currentPath != null && pathIndex < currentPath.size()) {
            BlockPos waypoint = currentPath.get(pathIndex);
            double wpDist = bot.distanceToSqr(waypoint.getX() + 0.5, waypoint.getY(), waypoint.getZ() + 0.5);

            if (wpDist < 2.25) {
                pathIndex++;
                if (pathIndex >= currentPath.size()) {
                    navigateTo(bot, currentTarget, 1.0);
                    return distSqr;
                }
            }

            BlockPos nextWp = currentPath.get(pathIndex);
            navigateTo(bot, nextWp, 1.0);
            return distSqr;
        }

        navigateTo(bot, currentTarget, 1.0);
        return distSqr;
    }

    public String getBlockId() { return blockId; }
    public int getCount() { return count; }
    public int getMinedCount() { return minedCount; }
}