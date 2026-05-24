package com.aimod.ai.action;

import com.aimod.ai.WorldScanner;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块交互动作：与工作台、熔炉、箱子等方块交互。
 * 支持自动查找最近的指定方块并交互。
 */
public class InteractBlockAction extends Action {

    public enum InteractType {
        CRAFTING_TABLE, FURNACE, CHEST, ANVIL, ENCHANTING_TABLE, BREWING_STAND
    }

    private final InteractType interactType;
    private BlockPos targetPos;
    private final int searchRadius;
    private boolean attempted;

    public InteractBlockAction(InteractType interactType) {
        this(interactType, 32);
    }

    public InteractBlockAction(InteractType interactType, int searchRadius) {
        super("Interact with " + interactType.name());
        this.interactType = interactType;
        this.searchRadius = searchRadius;
        this.attempted = false;
    }

    public InteractBlockAction(InteractType interactType, BlockPos targetPos) {
        super("Interact with " + interactType.name() + " at " + targetPos.toShortString());
        this.interactType = interactType;
        this.targetPos = targetPos;
        this.searchRadius = 32;
        this.attempted = false;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (targetPos != null) {
            BlockState state = bot.level().getBlockState(targetPos);
            return matchesType(state);
        }
        return findTargetBlock(bot) != null;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            if (targetPos == null) {
                targetPos = findTargetBlock(bot);
            }

            if (targetPos == null) {
                DevLog.warn("INTERACT_NO_TARGET", "type={}", interactType);
                status = ActionStatus.FAILED;
                return;
            }

            status = ActionStatus.IN_PROGRESS;
            DevLog.info("INTERACT_START", "type={}, pos={}", interactType, targetPos.toShortString());
        }

        if (status == ActionStatus.IN_PROGRESS) {
            BlockState blockState = bot.level().getBlockState(targetPos);
            if (!matchesType(blockState)) {
                status = ActionStatus.FAILED;
                DevLog.warn("INTERACT_BLOCK_MISSING", "type={}, pos={}", interactType, targetPos.toShortString());
                return;
            }

            double distance = bot.distanceToSqr(
                    targetPos.getX() + 0.5,
                    targetPos.getY() + 0.5,
                    targetPos.getZ() + 0.5);

            if (distance > 6.25) {
                moveToward(bot, targetPos);
                return;
            }

            FakePlayer fakePlayer = bot;
            if (fakePlayer != null) {
                fakePlayer.lookAt(
                        targetPos.getX() + 0.5,
                        targetPos.getY() + 0.5,
                        targetPos.getZ() + 0.5);
                fakePlayer.interactWithBlock(targetPos, InteractionHand.MAIN_HAND);
                fakePlayer.useItem(InteractionHand.MAIN_HAND);
                DevLog.info("INTERACT_DONE", "type={}, pos={}", interactType, targetPos.toShortString());
                status = ActionStatus.COMPLETED;
            } else {
                // 没有 FakePlayer，标记失败
                DevLog.warn("INTERACT_FAIL_NO_FAKE", "type={}, pos={}", interactType, targetPos.toShortString());
                status = ActionStatus.FAILED;
            }
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    private BlockPos findTargetBlock(FakePlayer bot) {
        WorldScanner scanner = new WorldScanner(bot);
        return switch (interactType) {
            case CRAFTING_TABLE -> scanner.findNearestCraftingTable(searchRadius);
            case FURNACE -> scanner.findNearestFurnace(searchRadius);
            case CHEST -> scanner.findNearestChest(searchRadius);
            case ANVIL -> scanner.findNearestAnvil(searchRadius);
            case ENCHANTING_TABLE -> scanner.findNearestBlock("minecraft:enchanting_table", searchRadius);
            case BREWING_STAND -> scanner.findNearestBlock("minecraft:brewing_stand", searchRadius);
        };
    }

    private boolean matchesType(BlockState state) {
        return switch (interactType) {
            case CRAFTING_TABLE -> state.is(Blocks.CRAFTING_TABLE);
            case FURNACE -> state.is(Blocks.FURNACE) || state.is(Blocks.BLAST_FURNACE) || state.is(Blocks.SMOKER);
            case CHEST -> state.is(Blocks.CHEST) || state.is(Blocks.TRAPPED_CHEST);
            case ANVIL -> state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL);
            case ENCHANTING_TABLE -> state.is(Blocks.ENCHANTING_TABLE);
            case BREWING_STAND -> state.is(Blocks.BREWING_STAND);
        };
    }

    private void moveToward(FakePlayer bot, BlockPos target) {
        double dx = target.getX() + 0.5 - bot.getX();
        double dy = target.getY() - bot.getY();
        double dz = target.getZ() + 0.5 - bot.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        if (distance > 0.1) {
            double speed = 0.3;
            double moveX = (dx / distance) * speed;
            double moveZ = (dz / distance) * speed;

            if (dy > 0.5 && bot.onGround()) {
                bot.jumpFromGround();
            }

            bot.setDeltaMovement(moveX, bot.getDeltaMovement().y, moveZ);
        }
    }

    public InteractType getInteractType() {
        return interactType;
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}
