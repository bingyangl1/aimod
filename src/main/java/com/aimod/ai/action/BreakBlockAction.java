package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;

public class BreakBlockAction extends Action {
    private final BlockPos targetPos;
    private int breakProgress;
    private int breakTime;
    private boolean started;

    public BreakBlockAction(BlockPos targetPos) {
        super("Break block at " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.breakProgress = 0;
        this.breakTime = 20; // 默认 1 秒
        this.started = false;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        BlockState blockState = bot.level().getBlockState(targetPos);
        if (blockState.isAir()) {
            setFailReason("目标位置已经是空气: " + targetPos.toShortString());
            return false;
        }
        if (blockState.getDestroySpeed(bot.level(), targetPos) < 0) {
            setFailReason("方块不可破坏: " + blockState.getBlock().getDescriptionId());
            return false;
        }
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            breakProgress = 0;
            started = false;
        }
        
        if (status == ActionStatus.IN_PROGRESS) {
            BlockState blockState = bot.level().getBlockState(targetPos);

            // 检查方块是否还存在
            if (blockState.isAir()) {
                status = ActionStatus.COMPLETED;
                return;
            }

            // 计算破坏时间（基于方块硬度 + 工具速度）
            if (!started) {
                // 选择最佳工具
                var toolSet = new com.aimod.ai.pathing.ToolSet(bot);
                int bestSlot = toolSet.getBestSlot(blockState.getBlock());
                if (bestSlot >= 0) {
                    if (bestSlot < 9) bot.getInventory().selected = bestSlot;
                    else {
                        var tmp = bot.getInventory().getItem(0);
                        bot.getInventory().setItem(0, bot.getInventory().getItem(bestSlot));
                        bot.getInventory().setItem(bestSlot, tmp);
                        bot.getInventory().selected = 0;
                    }
                }
                double breakTicks = toolSet.getBreakTicks(blockState);
                breakTime = breakTicks > 0 ? Math.max(1, (int) breakTicks) : Math.max(20, (int) (blockState.getDestroySpeed(bot.level(), targetPos) * 20));
                started = true;
                DevLog.info("BREAK_START", "pos={}, breakTime={}, toolSlot={}",
                        targetPos.toShortString(), breakTime, bestSlot);
            }

            // 面向方块
            bot.lookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

            // 模拟挖掘进度
            breakProgress++;

            // 每 tick 更新挖掘进度
            if (breakProgress >= breakTime) {
                bot.level().destroyBlock(targetPos, true, bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("BREAK_COMPLETE", "pos={}", targetPos.toShortString());
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}
