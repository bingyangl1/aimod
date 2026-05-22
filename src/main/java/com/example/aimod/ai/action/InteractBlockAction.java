package com.example.aimod.ai.action;

import com.example.aimod.ai.WorldScanner;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
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
    public boolean canExecute(AIBotEntity bot) {
        // 如果已指定位置，检查该位置是否有正确的方块
        if (targetPos != null) {
            BlockState state = bot.level().getBlockState(targetPos);
            return matchesType(state);
        }
        // 否则检查附近是否有目标方块
        return findTargetBlock(bot) != null;
    }

    @Override
    public void execute(AIBotEntity bot) {
        if (status == ActionStatus.PENDING) {
            // 查找目标方块
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
            // 检查方块是否还存在
            BlockState blockState = bot.level().getBlockState(targetPos);
            if (!matchesType(blockState)) {
                status = ActionStatus.FAILED;
                DevLog.warn("INTERACT_BLOCK_MISSING", "type={}, pos={}", interactType, targetPos.toShortString());
                return;
            }

            // 移动到目标附近
            double distance = bot.distanceToSqr(
                    targetPos.getX() + 0.5,
                    targetPos.getY() + 0.5,
                    targetPos.getZ() + 0.5);

            if (distance > 6.25) { // 2.5 blocks
                moveToward(bot, targetPos);
                return;
            }

            // 与方块交互
            FakePlayer fakePlayer = getFakePlayer(bot);
            if (fakePlayer != null) {
                // 面向方块
                fakePlayer.lookAt(
                        targetPos.getX() + 0.5,
                        targetPos.getY() + 0.5,
                        targetPos.getZ() + 0.5);

                // 右键交互
                fakePlayer.interactWithBlock(targetPos, InteractionHand.MAIN_HAND);
                fakePlayer.useItem(InteractionHand.MAIN_HAND);

                DevLog.info("INTERACT_DONE", "type={}, pos={}", interactType, targetPos.toShortString());
                status = ActionStatus.COMPLETED;
            } else {
                // 没有 FakePlayer，尝试直接交互
                if (!attempted) {
                    fakePlayer.interactWithBlock(targetPos, InteractionHand.MAIN_HAND);
                    attempted = true;
                    DevLog.info("INTERACT_DONE_NO_FAKE", "type={}, pos={}", interactType, targetPos.toShortString());
                    status = ActionStatus.COMPLETED;
                } else {
                    status = ActionStatus.FAILED;
                }
            }
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    /**
     * 查找目标方块
     */
    private BlockPos findTargetBlock(AIBotEntity bot) {
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

    /**
     * 检查方块是否匹配交互类型
     */
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

    /**
     * 向目标移动
     */
    private void moveToward(AIBotEntity bot, BlockPos target) {
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
