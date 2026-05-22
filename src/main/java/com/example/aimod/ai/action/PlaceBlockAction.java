package com.example.aimod.ai.action;

import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;


public class PlaceBlockAction extends Action {
    private final BlockPos targetPos;
    private final BlockItem blockItem;
    private boolean attempted;

    public PlaceBlockAction(BlockPos targetPos, BlockItem blockItem) {
        super("Place " + blockItem.getDescription().getString() + " at " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.blockItem = blockItem;
        this.attempted = false;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        BlockState blockState = bot.level().getBlockState(targetPos);
        if (!blockState.isAir()) {
            return false;
        }
        return hasBlockItem(bot);
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            attempted = false;
        }

        if (status == ActionStatus.IN_PROGRESS) {
            ItemStack stack = findBlockItem(bot);
            if (stack.isEmpty()) {
                DevLog.warn("PLACE_FAIL_NO_ITEM", "pos={}, item={}", targetPos.toShortString(), blockItem);
                status = ActionStatus.FAILED;
                return;
            }

            // 使用 FakePlayer 放置方块
            FakePlayer fakePlayer = bot;
            if (fakePlayer != null) {
                // 面向放置位置
                fakePlayer.lookAt(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);

                // 找到相邻的可放置面
                Direction placeFace = findPlaceableFace(bot, targetPos);
                if (placeFace != null) {
                    BlockPos adjacentPos = targetPos.relative(placeFace.getOpposite());
                    BlockHitResult hitResult = new BlockHitResult(
                            new Vec3(adjacentPos.getX() + 0.5, adjacentPos.getY() + 0.5, adjacentPos.getZ() + 0.5),
                            placeFace,
                            adjacentPos,
                            false
                    );

                    // 使用 FakePlayer 放置方块
                    UseOnContext context = new UseOnContext(fakePlayer, InteractionHand.MAIN_HAND, hitResult);
                    stack.useOn(context);

                    // 检查是否放置成功
                    if (!bot.level().getBlockState(targetPos).isAir()) {
                        status = ActionStatus.COMPLETED;
                        DevLog.info("PLACE_COMPLETE", "pos={}", targetPos.toShortString());
                    } else if (!attempted) {
                        // 尝试直接放置
                        BlockState state = blockItem.getBlock().defaultBlockState();
                        bot.level().setBlock(targetPos, state, 3);
                        stack.shrink(1);
                        attempted = true;

                        if (!bot.level().getBlockState(targetPos).isAir()) {
                            status = ActionStatus.COMPLETED;
                            DevLog.info("PLACE_COMPLETE_DIRECT", "pos={}", targetPos.toShortString());
                        } else {
                            status = ActionStatus.FAILED;
                            DevLog.warn("PLACE_FAIL", "pos={}", targetPos.toShortString());
                        }
                    }
                } else {
                    // 没有可放置的面，直接放置
                    BlockState state = blockItem.getBlock().defaultBlockState();
                    bot.level().setBlock(targetPos, state, 3);
                    stack.shrink(1);
                    status = ActionStatus.COMPLETED;
                    DevLog.info("PLACE_COMPLETE_NO_FACE", "pos={}", targetPos.toShortString());
                }
            } else {
                // 没有 FakePlayer，直接放置
                BlockState state = blockItem.getBlock().defaultBlockState();
                bot.level().setBlock(targetPos, state, 3);
                stack.shrink(1);
                status = ActionStatus.COMPLETED;
                DevLog.info("PLACE_COMPLETE_NO_FAKE", "pos={}", targetPos.toShortString());
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private boolean hasBlockItem(FakePlayer bot) {
        return !findBlockItem(bot).isEmpty();
    }

    private ItemStack findBlockItem(FakePlayer bot) {
        var inventory = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == blockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 找到可放置方块的相邻面
     */
    private Direction findPlaceableFace(FakePlayer bot, BlockPos pos) {
        for (Direction face : Direction.values()) {
            BlockPos adjacent = pos.relative(face);
            BlockState adjacentState = bot.level().getBlockState(adjacent);
            if (!adjacentState.isAir() && adjacentState.isSolid()) {
                return face;
            }
        }
        return null;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}
