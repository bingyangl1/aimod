package com.aimod.ai.action;

import com.aimod.ai.VeinScanner;
import com.aimod.ai.WorldScanner;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

/**
 * Vein mining: mine connected blocks of the same type.
 * First finds the nearest block, then BFS-scans connected ones, mines them all.
 */
public class VeinMineAction extends Action {

    private final String blockId;
    private final int count;
    private final int searchRadius;

    private BlockPos currentTarget;
    private List<BlockPos> veinBlocks;
    private int minedCount;
    private int breakProgress;
    private int breakTime;
    private boolean scanning;
    private boolean veinScanned;

    private double lastDistSqr;
    private int stuckTicks;
    private static final int STUCK_TIMEOUT = 200;

    public VeinMineAction(String blockId, int count, int searchRadius) {
        super("Vein mine " + count + " " + blockId);
        this.blockId = blockId;
        this.count = Math.max(1, count);
        this.searchRadius = searchRadius;
        this.minedCount = 0;
        this.scanning = true;
        this.veinScanned = false;
    }

    public VeinMineAction(String blockId, int count) {
        this(blockId, count, 32);
    }

    @Override
    public boolean canExecute(FakePlayer bot) { return true; }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            scanning = true;
            DevLog.info("VEIN_START", "block={}, count={}", blockId, count);
        }
        if (status != ActionStatus.IN_PROGRESS) return;
        if (minedCount >= count) { stopNavigation(bot); status = ActionStatus.COMPLETED; return; }

        // Phase 1: find nearest target block
        if ((currentTarget == null || scanning) && !veinScanned) {
            currentTarget = findNearestBlock(bot);
            scanning = false;
            if (currentTarget == null) { status = ActionStatus.FAILED; return; }
            DevLog.info("VEIN_FOUND", "first={}", currentTarget.toShortString());
        }

        // Phase 2: BFS scan for connected vein
        if (currentTarget != null && !veinScanned) {
            BlockState state = bot.level().getBlockState(currentTarget);
            Block targetBlock = state.getBlock();
            if (targetBlock == Blocks.AIR) { veinScanned = true; scanning = true; currentTarget = null; return; }
            veinBlocks = VeinScanner.findVein((ServerLevel) bot.level(), currentTarget, targetBlock, count);
            veinScanned = true;
            DevLog.info("VEIN_SCANNED", "veinSize={}", veinBlocks.size());
        }

        // Phase 3: mine next block in vein
        if (veinBlocks != null && !veinBlocks.isEmpty()) {
            currentTarget = veinBlocks.get(0);
            if (currentTarget == null) { veinBlocks.clear(); status = ActionStatus.COMPLETED; return; }

            BlockState blockState = bot.level().getBlockState(currentTarget);
            if (blockState.isAir()) {
                minedCount++;
                veinBlocks.remove(0);
                breakProgress = 0;
                return;
            }

            // Navigate to block
            double dx = currentTarget.getX() + 0.5 - bot.getX();
            double dy = currentTarget.getY() + 0.5 - bot.getY();
            double dz = currentTarget.getZ() + 0.5 - bot.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;
            double dxzSqr = dx * dx + dz * dz;

            if (dxzSqr > 9.0 || dy < -1.5 || dy > 4.0) {
                // Too far — navigate closer
                navigateTo(bot, currentTarget, 1.0);
                if (distSqr >= lastDistSqr - 0.01) stuckTicks++;
                else stuckTicks = 0;
                lastDistSqr = distSqr;
                if (stuckTicks > STUCK_TIMEOUT) {
                    DevLog.warn("VEIN_STUCK", "skipping={}", currentTarget.toShortString());
                    veinBlocks.remove(0);
                    stuckTicks = 0;
                }
                return;
            }

            stopNavigation(bot);
            // Break the block
            FakePlayer fp = bot;
            if (fp != null) fp.lookAt(currentTarget.getX() + 0.5, currentTarget.getY() + 0.5, currentTarget.getZ() + 0.5);

            if (breakProgress == 0) {
                float hardness = blockState.getDestroySpeed(bot.level(), currentTarget);
                breakTime = Math.max(20, (int) (hardness * 20));
            }
            breakProgress++;
            bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (breakProgress >= breakTime) {
                bot.level().destroyBlock(currentTarget, true, bot);
                minedCount++;
                DevLog.info("VEIN_BROKEN", "pos={}, total={}", currentTarget.toShortString(), minedCount);
                veinBlocks.remove(0);
                breakProgress = 0;
                breakTime = 0;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private BlockPos findNearestBlock(FakePlayer bot) {
        WorldScanner scanner = new WorldScanner(bot);
        return scanner.findNearestBlock(blockId, searchRadius);
    }

    public String getBlockId() { return blockId; }
    public int getCount() { return count; }
}
