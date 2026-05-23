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
        return !blockState.isAir() && blockState.getDestroySpeed(bot.level(), targetPos) >= 0;
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

            // 计算破坏时间（基于方块硬度）
            if (!started) {
                float hardness = blockState.getDestroySpeed(bot.level(), targetPos);
                breakTime = Math.max(20, (int) (hardness * 20)); // 至少 1 秒
                started = true;
                DevLog.info("BREAK_START", "pos={}, hardness={}, breakTime={}",
                        targetPos.toShortString(), hardness, breakTime);
            }

            // 使用 FakePlayer 破坏方块
            FakePlayer fakePlayer = bot;
            if (fakePlayer != null) {
                // 面向方块
                fakePlayer.lookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

                // 模拟挖掘进度
                breakProgress++;

                // 每 tick 更新挖掘进度
                if (breakProgress >= breakTime) {
                    // 使用 FakePlayer 破坏方块
                    bot.level().destroyBlock(targetPos, true, fakePlayer);
                    status = ActionStatus.COMPLETED;
                    DevLog.info("BREAK_COMPLETE", "pos={}", targetPos.toShortString());
                }
            } else {
                // 没有 FakePlayer，直接破坏
                breakProgress++;
                if (breakProgress >= breakTime) {
                    bot.level().destroyBlock(targetPos, true, bot);
                    status = ActionStatus.COMPLETED;
                    DevLog.info("BREAK_COMPLETE_NO_FAKE", "pos={}", targetPos.toShortString());
                }
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
