package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;


public class PlaceBlockAction extends Action {
    private final BlockPos targetPos;
    private final BlockItem blockItem;

    public PlaceBlockAction(BlockPos targetPos, BlockItem blockItem) {
        super("Place " + blockItem.getDescription().getString() + " at " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.blockItem = blockItem;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        BlockState blockState = bot.level().getBlockState(targetPos);
        if (!(blockState.isAir() || blockState.canBeReplaced())) {
            DevLog.warn("PLACE_BLOCK_OCCUPIED", "pos={}, state={}", targetPos.toShortString(), blockState.getBlock().getDescriptionId());
            return false;
        }
        if (!hasBlockItem(bot)) {
            DevLog.warn("PLACE_BLOCK_NO_ITEM", "pos={}, item={}", targetPos.toShortString(), blockItem.getDescriptionId());
            return false;
        }
        return true;
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
        Direction placeFace;
        if (failCount == 0) {
            placeAt = targetPos;
            placeFace = findPlaceableFace(bot, targetPos);
        } else if (failCount == 1) {
            placeAt = targetPos.below();
            placeFace = findPlaceableFace(bot, targetPos.below());
        } else if (failCount == 2) {
            placeAt = findNearbyAir(bot);
            placeFace = findPlaceableFace(bot, placeAt);
        } else {
            placeAt = bot.blockPosition();
            placeFace = Direction.UP; // place at feet — use UP as placement face
        }

        // Attempt realistic placement via game mode
        boolean placed = attemptPlacement(bot, stack, placeAt, placeFace);

        if (placed) {
            status = ActionStatus.COMPLETED;
            DevLog.info("PLACE_COMPLETE", "pos={}", placeAt.toShortString());
            // If placed at feet, jump up
            if (placeAt.equals(bot.blockPosition()) && bot.onGround()) {
                bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
            }
        } else {
            failCount++;
            if (failCount > 5) {
                status = ActionStatus.FAILED;
                DevLog.warn("PLACE_FAIL_ALL", "tried 6 positions");
            }
        }
    }

    /**
     * Attempt to place a block using the game mode (realistic placement).
     * Selects the item in the bot's hand, then calls useItemOn.
     */
    private boolean attemptPlacement(FakePlayer bot, ItemStack stack, BlockPos pos, Direction face) {
        if (face == null) return fallbackSetBlock(bot, stack, pos);

        Level level = bot.level();
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return false;

        // Select the item in the bot's main hand
        int slot = findBlockSlot(bot);
        if (slot < 0) return false;
        bot.getInventory().selected = slot;

        // Build a BlockHitResult: click the face of the solid neighbor block
        // face = direction FROM pos TO the solid block (e.g. DOWN if block below is solid)
        // againstPos = the solid block we actually click on
        // clickFace = the face of the solid block facing toward pos
        BlockPos againstPos = pos.relative(face);
        Direction clickFace = face.getOpposite();
        Vec3 hitLoc = new Vec3(
                againstPos.getX() + 0.5 + clickFace.getStepX() * 0.5,
                againstPos.getY() + 0.5 + clickFace.getStepY() * 0.5,
                againstPos.getZ() + 0.5 + clickFace.getStepZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitLoc, clickFace, againstPos, false);

        // Use the game mode to place the block
        InteractionResult result = bot.gameMode.useItemOn(
                (ServerPlayer) bot, serverLevel,
                stack, InteractionHand.MAIN_HAND, hitResult
        );
        return result.consumesAction();
    }

    /** Fallback: direct setBlock if game mode placement fails. */
    private boolean fallbackSetBlock(FakePlayer bot, ItemStack stack, BlockPos pos) {
        BlockState state = blockItem.getBlock().defaultBlockState();
        bot.level().setBlock(pos, state, 3);
        if (!bot.level().getBlockState(pos).isAir()) {
            stack.shrink(1);
            return true;
        }
        return false;
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

    /** Find the inventory slot containing the block item. */
    private int findBlockSlot(FakePlayer bot) {
        var inventory = bot.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == blockItem) {
                return i;
            }
        }
        return -1;
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
        // Fallback: face toward bot
        BlockPos botPos = bot.blockPosition();
        return Direction.getNearest(
                pos.getX() - botPos.getX(),
                0,
                pos.getZ() - botPos.getZ()
        );
    }

    public BlockPos getTargetPos() {
        return targetPos;
    }
}
