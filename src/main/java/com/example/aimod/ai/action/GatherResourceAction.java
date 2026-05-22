package com.example.aimod.ai.action;

import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.WorldScanner;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public class GatherResourceAction extends Action {

    public enum ResourceType {
        WOOD, STONE, DIRT, SAND, COBBLESTONE
    }

    private static final int STUCK_TIMEOUT = 200; // 10 seconds

    private final ResourceType resourceType;
    private final int count;
    private final int searchRadius;

    private BlockPos currentTarget;
    private int gatheredCount;
    private int breakProgress;
    private int breakTime;
    private boolean searching;

    private double lastDistSqr;
    private int stuckTicks;

    public GatherResourceAction(ResourceType resourceType, int count) {
        this(resourceType, count, 32);
    }

    public GatherResourceAction(ResourceType resourceType, int count, int searchRadius) {
        super("Gather " + count + " " + resourceType.name());
        this.resourceType = resourceType;
        this.count = Math.max(1, count);
        this.searchRadius = searchRadius;
        this.gatheredCount = 0;
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
            DevLog.info("GATHER_START", "type={}, count={}, radius={}", resourceType, count, searchRadius);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            if (gatheredCount >= count) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("GATHER_DONE", "type={}, gathered={}", resourceType, gatheredCount);
                return;
            }

            if (currentTarget == null || searching) {
                currentTarget = findResource(bot);
                searching = false;
                lastDistSqr = Double.MAX_VALUE;
                stuckTicks = 0;

                if (currentTarget == null) {
                    DevLog.warn("GATHER_NO_RESOURCE", "type={}, radius={}", resourceType, searchRadius);
                    status = ActionStatus.FAILED;
                    return;
                }

                DevLog.info("GATHER_FOUND", "type={}, pos={}", resourceType, currentTarget.toShortString());
            }

            BlockState blockState = bot.level().getBlockState(currentTarget);
            if (blockState.isAir()) {
                gatheredCount++;
                DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
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
                    DevLog.warn("GATHER_STUCK", "type={}, target={}, dist={}, skipping",
                            resourceType, currentTarget.toShortString(),
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
                DevLog.info("GATHER_BREAKING", "type={}, pos={}, breakTime={}",
                        resourceType, currentTarget.toShortString(), breakTime);
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
                gatheredCount++;
                currentTarget = null;
                searching = true;
                breakProgress = 0;

                DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private BlockPos findResource(AIBotEntity bot) {
        WorldScanner scanner = new WorldScanner(bot);
        List<BlockPos> candidates = new ArrayList<>();

        for (Block block : getBlocksForType()) {
            List<BlockPos> found = scanner.findNearbyBlocks(block, searchRadius);
            candidates.addAll(found);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingDouble(pos -> bot.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));

        return candidates.get(0);
    }

    private List<Block> getBlocksForType() {
        return switch (resourceType) {
            case WOOD -> List.of(
                    Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
                    Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
                    Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG);
            case STONE -> List.of(Blocks.STONE, Blocks.COBBLESTONE);
            case DIRT -> List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT);
            case SAND -> List.of(Blocks.SAND, Blocks.RED_SAND);
            case COBBLESTONE -> List.of(Blocks.COBBLESTONE, Blocks.STONE);
        };
    }

    public ResourceType getResourceType() { return resourceType; }
    public int getCount() { return count; }
    public int getGatheredCount() { return gatheredCount; }
}