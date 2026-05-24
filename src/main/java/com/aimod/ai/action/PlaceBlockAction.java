package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
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

    private int failCount;

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            failCount = 0;
        }
        if (status != ActionStatus.IN_PROGRESS) return;

        ItemStack stack = findBlockItem(bot);
        if (stack.isEmpty()) { status = ActionStatus.FAILED; return; }

        // Try placing: target pos → nearby alternatives → air place
        BlockPos placeAt;
        if (failCount == 0) placeAt = targetPos;
        else if (failCount == 1) placeAt = targetPos.below(); // try block below
        else if (failCount == 2) placeAt = findNearbyAir(bot); // search nearby
        else placeAt = bot.blockPosition(); // place at feet (pillar up)

        BlockState state = blockItem.getBlock().defaultBlockState();
        bot.level().setBlock(placeAt, state, 3);
        stack.shrink(1);

        if (!bot.level().getBlockState(placeAt).isAir()) {
            status = ActionStatus.COMPLETED;
            DevLog.info("PLACE_COMPLETE", "pos={}", placeAt.toShortString());
            // If placed at feet, jump up
            if (placeAt.equals(bot.blockPosition()) && bot.onGround()) {
                bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
            }
        } else {
            failCount++;
            if (failCount > 5) { status = ActionStatus.FAILED; DevLog.warn("PLACE_FAIL_ALL", "tried 6 positions"); }
        }
    }

    /** Find nearby air position when original target is blocked. */
    private BlockPos findNearbyAir(FakePlayer bot) {
        for (int r = 1; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        var pos = targetPos.offset(dx, dy, dz);
                        if (bot.level().getBlockState(pos).isAir()
                                && !bot.level().getBlockState(pos.below()).isAir()) {
                            return pos;
                        }
                    }
                }
            }
        }
        return targetPos; // fallback
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
