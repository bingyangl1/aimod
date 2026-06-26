package com.aimod.ai.action;

import com.aimod.ai.WorldScanner;
import com.aimod.ai.pathing.Pathfinder;
import com.aimod.ai.pathing.PathResult;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MineBlockAction extends Action {
    private static final int STUCK_TIMEOUT = 200;
    private static final int AUTO_DIG_RESCAN_INTERVAL = 10; // Re-scan every 10 blocks dug

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

    // Dig down state
    private int digDownCooldown;

    // Auto-dig-to-ore-level state (hierarchical planning)
    private boolean autoDigging;       // true when auto-digging to ore spawn level
    private int autoDigTargetY;        // target Y level to dig to
    private int autoDigBlocksDug;      // blocks dug during auto-dig
    private int autoDigRescanCounter;  // re-scan counter during auto-dig

    // Pathfinding state
    private List<BlockPos> currentPath;
    private int pathIndex;

    public MineBlockAction(String blockId, int count) {
        this(blockId, count, 32);
    }

    private static final int MAX_SEARCH_RADIUS = 128;

    public MineBlockAction(String blockId, int count, int searchRadius) {
        super("Mine " + count + " " + blockId);
        this.blockId = blockId;
        this.count = Math.max(1, count);
        this.searchRadius = Math.min(searchRadius, MAX_SEARCH_RADIUS);
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
                // Wire up OreIndex for incremental scanning (XRay optimization)
                if (bot.level() != null) {
                    com.aimod.ai.OreIndex oreIndex = com.aimod.ai.OreIndexHolder.getForLevel(bot.level());
                    if (oreIndex != null) scanner.setOreIndex(oreIndex);
                }
                currentTarget = scanner.findNearestBlock(blockId, searchRadius);
                searching = false;
                lastDistSqr = Double.MAX_VALUE;
                stuckTicks = 0;
                currentPath = null;
                pathIndex = 0;

                if (currentTarget == null) {
                    // Hierarchical planning: auto-dig to ore spawn level
                    if (!autoDigging && com.aimod.ai.OreDepthHelper.isKnownOre(blockId)) {
                        int botY = bot.blockPosition().getY();
                        int targetY = com.aimod.ai.OreDepthHelper.getTargetY(blockId);
                        if (botY > targetY + 10) {
                            // Bot is above ore spawn range — auto-dig down
                            autoDigging = true;
                            autoDigTargetY = targetY;
                            autoDigBlocksDug = 0;
                            autoDigRescanCounter = 0;
                            DevLog.info("MINE_AUTO_DIG_START", "block={}, currentY={}, targetY={}",
                                    blockId, botY, targetY);
                            return; // Will dig down on next tick
                        }
                    }
                    // Already at ore level or unknown ore — fail
                    DevLog.warn("MINE_NO_BLOCK_FOUND", "block={}, radius={}, autoDigging={}",
                            blockId, searchRadius, autoDigging);
                    status = ActionStatus.FAILED;
                    return;
                }

                DevLog.info("MINE_FOUND", "block={}, pos={}", blockId, currentTarget.toShortString());
                computePath(bot);
            }

            // Auto-dig to ore spawn level (hierarchical planning)
            if (autoDigging && currentTarget == null) {
                int botY = bot.blockPosition().getY();
                if (botY <= autoDigTargetY + 2) {
                    // Reached target Y — re-scan one more time
                    autoDigging = false;
                    searching = true;
                    DevLog.info("MINE_AUTO_DIG_REACHED", "block={}, y={}, blocksDug={}",
                            blockId, botY, autoDigBlocksDug);
                    return;
                }
                // Dig down
                boolean dug = tryDigDown(bot);
                if (dug) {
                    autoDigBlocksDug++;
                    autoDigRescanCounter++;
                    // Re-scan periodically to check if ore appeared
                    if (autoDigRescanCounter >= AUTO_DIG_RESCAN_INTERVAL) {
                        autoDigRescanCounter = 0;
                        searching = true;
                        DevLog.info("MINE_AUTO_DIG_RESCAN", "block={}, y={}, blocksDug={}",
                                blockId, botY, autoDigBlocksDug);
                    }
                } else {
                    // Can't dig further (liquid, bedrock, void)
                    autoDigging = false;
                    searching = true;
                    DevLog.warn("MINE_AUTO_DIG_STOPPED", "block={}, y={}, reason=obstacle", blockId, botY);
                }
                return;
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

            // Select best tool for this block type
            if (breakProgress == 0) {
                var toolSet = new com.aimod.ai.pathing.ToolSet(bot);
                int bestSlot = toolSet.getBestSlot(blockState.getBlock());
                if (bestSlot >= 0) {
                    if (bestSlot < 9) bot.getInventory().selected = bestSlot;
                    else { var tmp = bot.getInventory().getItem(0); bot.getInventory().setItem(0, bot.getInventory().getItem(bestSlot)); bot.getInventory().setItem(bestSlot, tmp); bot.getInventory().selected = 0; }
                }
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

                // Vein mining: break connected blocks of the same type
                if (bot.level() instanceof ServerLevel level) {
                    if (VeinMiningHelper.isOreBlock(blockState.getBlock())) {
                        int veinSize = VeinMiningHelper.veinMineOre(level, currentTarget,
                                blockState.getBlock(), 64, true, bot);
                        if (veinSize > 1) {
                            minedCount += veinSize - 1;
                            DevLog.info("MINE_VEIN_ORE", "block={}, veinSize={}", blockId, veinSize);
                        }
                    } else if (VeinMiningHelper.isLogBlock(blockState.getBlock())) {
                        int treeSize = VeinMiningHelper.veinMineTree(level, currentTarget,
                                blockState.getBlock(), 64, true, bot);
                        if (treeSize > 1) {
                            minedCount += treeSize - 1;
                            DevLog.info("MINE_VEIN_TREE", "block={}, treeSize={}", blockId, treeSize);
                        }
                    }
                }

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

        Pathfinder pathfinder = new Pathfinder(serverLevel, botPos, approachPos, 500, 20);
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

    /**
     * Try to dig down to reach an underground target.
     * Breaks the block below the bot's feet and lets gravity pull it down.
     * Returns true if digging was performed or bot is falling.
     */
    private boolean tryDigDown(FakePlayer bot) {
        if (digDownCooldown > 0) {
            digDownCooldown--;
            return true;
        }

        BlockPos feetPos = bot.blockPosition();
        BlockPos belowFeet = feetPos.below();

        // Safety: don't dig below Y=-64 (void)
        if (belowFeet.getY() < -64) {
            DevLog.warn("MINE_DIG_DOWN_VOID", "pos={}", belowFeet.toShortString());
            return false;
        }

        if (!(bot.level() instanceof ServerLevel level)) return false;
        BlockState belowState = level.getBlockState(belowFeet);

        // If already air, just fall
        if (belowState.isAir()) {
            if (!bot.onGround()) {
                // Already falling, wait
                return true;
            }
            // Need to move to a position with solid ground below to dig further
            return false;
        }

        // Safety: don't break bedrock
        float hardness = belowState.getDestroySpeed(level, belowFeet);
        if (hardness < 0) {
            DevLog.warn("MINE_DIG_DOWN_UNBREAKABLE", "pos={}", belowFeet.toShortString());
            return false;
        }

        // Safety: don't dig into liquids
        if (!level.getFluidState(belowFeet).isEmpty()) {
            DevLog.warn("MINE_DIG_DOWN_LIQUID", "pos={}", belowFeet.toShortString());
            return false;
        }

        // Break the block below
        level.destroyBlock(belowFeet, true, bot);
        digDownCooldown = 5; // wait for gravity
        DevLog.info("MINE_DIG_DOWN", "pos={}, target={}", belowFeet.toShortString(), currentTarget.toShortString());
        return true;
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

        // No path found — try digging down if target is below
        if (currentPath == null && dy < -2) {
            if (tryDigDown(bot)) {
                return distSqr;
            }
        }

        navigateTo(bot, currentTarget, 1.0);
        return distSqr;
    }

    public String getBlockId() { return blockId; }
    public int getCount() { return count; }
    public int getMinedCount() { return minedCount; }
}