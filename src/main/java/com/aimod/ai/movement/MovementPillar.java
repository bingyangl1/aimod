package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.ai.pathing.MoveCost;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vertical movement: place a block below and jump to ascend one block.
 * Used when the path requires going up (e.g., climbing a hill, pillar up).
 */
public class MovementPillar extends BotMovement {

    private static final int PLACE_COOLDOWN = 5;
    private int cooldown;
    private int stuckTicks;

    public MovementPillar(BlockPos src, BlockPos dest) {
        super(src, dest);
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dy = dest.getY() - src.getY();
        if (dy != 1) return Double.POSITIVE_INFINITY;
        int dx = Math.abs(dest.getX() - src.getX());
        int dz = Math.abs(dest.getZ() - src.getZ());
        if (dx + dz > 1) return Double.POSITIVE_INFINITY;

        // Cost: place block + jump
        return MoveCost.JUMP_ONE_BLOCK + 2.0; // extra cost for placing
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            status = Status.RUNNING;
        }
        return status == Status.RUNNING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) {
            canExecute(bot);
        }

        if (status != Status.RUNNING) {
            return status == Status.COMPLETE || status == Status.FAILED;
        }

        // Check if already at destination
        if (bot.blockPosition().equals(dest) || bot.blockPosition().getY() >= dest.getY()) {
            status = Status.COMPLETE;
            return true;
        }

        if (cooldown > 0) {
            cooldown--;
            return false;
        }

        ServerLevel level = (ServerLevel) bot.level();

        // Need solid ground below dest to stand on
        BlockPos belowDest = dest.below();
        BlockState belowState = level.getBlockState(belowDest);

        if (!MoveCost.canWalkOn(level, belowDest, belowState)) {
            // Place a block
            ItemStack throwaway = findThrowawayBlock(bot);
            if (throwaway.isEmpty()) {
                status = Status.FAILED;
                return true;
            }
            BlockState placeState = throwaway.getItem() instanceof BlockItem bi
                    ? bi.getBlock().defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState();
            level.setBlock(belowDest, placeState, 3);
            throwaway.shrink(1);
            cooldown = PLACE_COOLDOWN;
            return false;
        }

        // Jump up
        if (bot.onGround()) {
            bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
            bot.setOnGround(false);
            cooldown = 5;
        }

        // Check if reached
        if (bot.getY() >= dest.getY() - 0.1) {
            bot.setPos(bot.getX(), dest.getY(), bot.getZ());
            status = Status.COMPLETE;
            return true;
        }

        stuckTicks++;
        if (stuckTicks > 40) {
            status = Status.FAILED;
            return true;
        }

        return false;
    }

    private static ItemStack findThrowawayBlock(FakePlayer bot) {
        var inventory = bot.getInventory();
        // Priority: DIRT > COBBLESTONE > any BlockItem > LOGS (last resort)
        for (var pref : new net.minecraft.world.item.Item[]{
                net.minecraft.world.item.Items.DIRT,
                net.minecraft.world.item.Items.COBBLESTONE,
                net.minecraft.world.item.Items.COARSE_DIRT,
                net.minecraft.world.item.Items.STONE}) {
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                var stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.getItem() == pref) return stack;
            }
        }
        // Fallback: any BlockItem EXCEPT logs
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            var stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem
                    && !isLog(stack.getItem())) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }
    private static boolean isLog(net.minecraft.world.item.Item item) {
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
        return key.contains("_log") || key.contains("_stem") || key.contains("_hyphae");
    }
}
