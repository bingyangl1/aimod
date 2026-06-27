package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Automated farming action — harvest and replant crops.
 *
 * <p>Supports: wheat, carrots, potatoes, beetroot, melon, pumpkin,
 * sugar cane, bamboo, cactus, nether wart.</p>
 *
 * <p>Inspired by Baritone's FarmProcess.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Scan for mature crops in radius</li>
 *   <li>Navigate to crop</li>
 *   <li>Harvest (break the crop)</li>
 *   <li>Replant (place seed on farmland)</li>
 *   <li>Repeat until count reached or no more crops</li>
 * </ol>
 */
public class FarmAction extends Action {

    private static final int SCAN_RADIUS = 16;
    private static final int MAX_HARVEST = 64;

    private final int targetCount;
    private int harvestedCount;
    private BlockPos currentTarget;
    private boolean searching;

    public FarmAction(int count) {
        super("Farm " + count + " crops");
        this.targetCount = Math.max(1, count);
        this.harvestedCount = 0;
        this.searching = true;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return bot.level() instanceof ServerLevel;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            searching = true;
            DevLog.info("FARM_START", "count={}", targetCount);
        }

        if (status == ActionStatus.IN_PROGRESS) {
            if (harvestedCount >= targetCount) {
                status = ActionStatus.COMPLETED;
                DevLog.info("FARM_DONE", "harvested={}", harvestedCount);
                return;
            }

            if (currentTarget == null || searching) {
                currentTarget = findMatureCrop(bot);
                searching = false;
                if (currentTarget == null) {
                    // No mature crops found — wait or fail
                    DevLog.warn("FARM_NO_CROPS", "harvested={}", harvestedCount);
                    status = ActionStatus.FAILED;
                    setFailReason("No mature crops found in range");
                    return;
                }
                DevLog.info("FARM_FOUND", "pos={}", currentTarget.toShortString());
            }

            // Navigate to crop
            double dist = bot.distanceToSqr(currentTarget.getX() + 0.5, currentTarget.getY(), currentTarget.getZ() + 0.5);
            if (dist > 4.0) {
                navigateTo(bot, currentTarget, 1.0);
                return;
            }

            // Harvest the crop
            ServerLevel level = (ServerLevel) bot.level();
            BlockState state = level.getBlockState(currentTarget);
            Block block = state.getBlock();

            if (isMatureCrop(state)) {
                level.destroyBlock(currentTarget, true, bot);
                harvestedCount++;
                DevLog.info("FARM_HARVEST", "pos={}, total={}", currentTarget.toShortString(), harvestedCount);

                // Replant if farmland below
                BlockPos below = currentTarget.below();
                BlockState belowState = level.getBlockState(below);
                if (belowState.getBlock() instanceof FarmBlock) {
                    ItemStack seed = getSeedForCrop(block);
                    if (!seed.isEmpty()) {
                        level.setBlock(currentTarget, ((net.minecraft.world.item.BlockItem) seed.getItem()).getBlock().defaultBlockState(), 3);
                        seed.shrink(1);
                    }
                }
            }

            currentTarget = null;
            searching = true;
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private BlockPos findMatureCrop(FakePlayer bot) {
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos center = bot.blockPosition();
        List<BlockPos> matureCrops = new ArrayList<>();

        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (isMatureCrop(state)) {
                        matureCrops.add(pos);
                    }
                }
            }
        }

        // Sort by distance
        matureCrops.sort((a, b) -> Double.compare(
                a.distSqr(center), b.distSqr(center)));

        return matureCrops.isEmpty() ? null : matureCrops.get(0);
    }

    private boolean isMatureCrop(BlockState state) {
        Block block = state.getBlock();

        // Wheat, carrots, potatoes, beetroot — check age property
        if (block instanceof CropBlock crop) {
            return crop.isMaxAge(state);
        }

        // Melon, pumpkin — always harvestable (use registry name check)
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
        if (blockId.equals("minecraft:melon") || blockId.equals("minecraft:pumpkin")) {
            return true;
        }

        // Sugar cane, bamboo, cactus — always harvestable
        if (block instanceof SugarCaneBlock || block instanceof CactusBlock) {
            return true;
        }

        // Nether wart — check age
        if (block instanceof NetherWartBlock) {
            return state.getValue(NetherWartBlock.AGE) >= 3;
        }

        return false;
    }

    private ItemStack getSeedForCrop(Block crop) {
        if (crop instanceof CropBlock) {
            if (crop == Blocks.WHEAT) return new ItemStack(Items.WHEAT_SEEDS);
            if (crop == Blocks.CARROTS) return new ItemStack(Items.CARROT);
            if (crop == Blocks.POTATOES) return new ItemStack(Items.POTATO);
            if (crop == Blocks.BEETROOTS) return new ItemStack(Items.BEETROOT_SEEDS);
        }
        // Melon, pumpkin, sugar cane, bamboo, cactus, nether wart don't replant automatically
        return ItemStack.EMPTY;
    }
}
