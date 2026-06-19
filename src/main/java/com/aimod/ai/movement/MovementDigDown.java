package com.aimod.ai.movement;

import com.aimod.ai.pathing.MoveCost;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Multi-block vertical dig-down movement.
 * Repeatedly breaks the block below the bot and falls into the hole
 * until reaching the target depth.
 *
 * <p>Triggered when dy <= -2 and adx+adz == 0 (straight down).</p>
 */
public class MovementDigDown extends BotMovement {

    private static final int MAX_BREAK_TICKS_PER_BLOCK = 40; // 2 seconds per block
    private static final int SETTLE_TICKS = 10; // ticks to wait after landing

    private int breakProgress;
    private int settleCooldown;
    private int totalTicks;
    private int blocksDug;
    private int targetDepth; // how many blocks to dig down

    public MovementDigDown(BlockPos src, BlockPos dest) {
        super(src, dest);
        this.targetDepth = src.getY() - dest.getY();
    }

    @Override
    public double calculateCost(ServerLevel level) {
        int dy = dest.getY() - src.getY();
        if (dy >= -1) return Double.POSITIVE_INFINITY; // use MovementDownward for 1 block
        if (Math.abs(dest.getX() - src.getX()) > 0 || Math.abs(dest.getZ() - src.getZ()) > 0) {
            return Double.POSITIVE_INFINITY; // must be straight down
        }

        int depth = -dy;
        double totalCost = 0;

        // Check each block we need to dig through
        for (int i = 1; i <= depth; i++) {
            BlockPos checkPos = src.below(i);
            if (checkPos.getY() < -64) return Double.POSITIVE_INFINITY; // void

            BlockState state = level.getBlockState(checkPos);
            if (state.isAir()) continue; // already open

            // Check for liquids
            FluidState fluid = level.getFluidState(checkPos);
            if (!fluid.isEmpty()) return Double.POSITIVE_INFINITY;

            // Check for unbreakable blocks (bedrock)
            float hardness = state.getDestroySpeed(level, checkPos);
            if (hardness < 0) return Double.POSITIVE_INFINITY;

            totalCost += MoveCost.BREAK_BASE + hardness * 1.5 + MoveCost.WALK_ONE_BLOCK;
        }

        return Math.max(1.0, totalCost);
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        if (status == Status.PENDING) {
            // Pre-validate: check if we can dig down
            ServerLevel level = (ServerLevel) bot.level();

            // Warn if no pickaxe (will be very slow)
            if (!hasPickaxe(bot)) {
                DevLog.warn("DIG_DOWN_NO_PICKAXE", "bot will dig slowly without a pickaxe");
            }
            for (int i = 1; i <= targetDepth; i++) {
                BlockPos checkPos = src.below(i);
                if (checkPos.getY() < -64) {
                    DevLog.warn("DIG_DOWN_VOID", "pos={}", checkPos.toShortString());
                    status = Status.FAILED;
                    return false;
                }

                BlockState state = level.getBlockState(checkPos);
                if (state.isAir()) continue;

                FluidState fluid = level.getFluidState(checkPos);
                if (!fluid.isEmpty()) {
                    DevLog.warn("DIG_DOWN_LIQUID", "pos={}, fluid={}", checkPos.toShortString(), fluid.getType());
                    status = Status.FAILED;
                    return false;
                }

                float hardness = state.getDestroySpeed(level, checkPos);
                if (hardness < 0) {
                    DevLog.warn("DIG_DOWN_UNBREAKABLE", "pos={}", checkPos.toShortString());
                    status = Status.FAILED;
                    return false;
                }
            }

            status = Status.RUNNING;
            breakProgress = 0;
            settleCooldown = 0;
            totalTicks = 0;
            blocksDug = 0;
        }
        return status == Status.RUNNING;
    }

    @Override
    public boolean update(FakePlayer bot) {
        if (status == Status.PENDING) canExecute(bot);
        if (status == Status.FAILED || status == Status.COMPLETE) return true;
        if (status != Status.RUNNING) return false;

        totalTicks++;
        ServerLevel level = (ServerLevel) bot.level();

        // Total timeout check
        int maxTicks = targetDepth * (MAX_BREAK_TICKS_PER_BLOCK + SETTLE_TICKS + 10);
        if (totalTicks > maxTicks) {
            DevLog.warn("DIG_DOWN_TIMEOUT", "totalTicks={}, targetDepth={}", totalTicks, targetDepth);
            status = Status.FAILED;
            return true;
        }

        // Check if we've reached the target depth
        if (bot.getY() <= dest.getY() + 0.5 && bot.onGround()) {
            bot.setPos(bot.getX(), dest.getY(), bot.getZ());
            bot.setDeltaMovement(0, 0, 0);
            status = Status.COMPLETE;
            DevLog.info("DIG_DOWN_COMPLETE", "blocksDug={}, totalTicks={}", blocksDug, totalTicks);
            return true;
        }

        // Settle cooldown after landing
        if (settleCooldown > 0) {
            settleCooldown--;
            return false;
        }

        // If bot is in the air (falling), wait for landing
        if (!bot.onGround()) {
            // Apply gravity
            double moveY = Math.max(bot.getDeltaMovement().y - 0.08, -0.5);
            bot.setDeltaMovement(0, moveY, 0);
            bot.move(MoverType.SELF, bot.getDeltaMovement());
            return false;
        }

        // Bot is on ground — dig the block below
        BlockPos belowFeet = bot.blockPosition().below();
        BlockState breakState = level.getBlockState(belowFeet);

        // If already air, we might be at the target
        if (breakState.isAir()) {
            // Check if we've reached or passed the destination
            if (bot.getY() <= dest.getY() + 0.5) {
                bot.setPos(bot.getX(), dest.getY(), bot.getZ());
                bot.setDeltaMovement(0, 0, 0);
                status = Status.COMPLETE;
                return true;
            }
            // Fall into the air gap
            double moveY = Math.max(bot.getDeltaMovement().y - 0.08, -0.5);
            bot.setDeltaMovement(0, moveY, 0);
            bot.move(MoverType.SELF, bot.getDeltaMovement());
            return false;
        }

        // Safety: don't break bedrock or liquids
        float hardness = breakState.getDestroySpeed(level, belowFeet);
        if (hardness < 0) {
            DevLog.warn("DIG_DOWN_UNBREAKABLE_MID", "pos={}", belowFeet.toShortString());
            status = Status.FAILED;
            return true;
        }

        FluidState fluid = level.getFluidState(belowFeet);
        if (!fluid.isEmpty()) {
            DevLog.warn("DIG_DOWN_LIQUID_MID", "pos={}", belowFeet.toShortString());
            status = Status.FAILED;
            return true;
        }

        // Simulate breaking with tool efficiency
        breakProgress++;
        float toolSpeed = getToolSpeed(bot);
        int breakTime = Math.max(1, (int) (hardness * 20 / toolSpeed));

        if (breakProgress >= breakTime) {
            // Break the block
            level.destroyBlock(belowFeet, true, bot);
            blocksDug++;
            breakProgress = 0;
            settleCooldown = SETTLE_TICKS;
            DevLog.info("DIG_DOWN_DUG", "pos={}, blocksDug={}, toolSpeed={}", belowFeet.toShortString(), blocksDug, toolSpeed);
        }

        return false;
    }

    /**
     * Get the best tool speed from the bot's inventory.
     * Returns 1.0 for bare hands, higher for appropriate tools.
     */
    private float getToolSpeed(FakePlayer bot) {
        float bestSpeed = 1.0f;
        for (int i = 0; i <= 40; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            // Skip nearly-broken tools (durability < 10)
            if (stack.isDamageableItem() && (stack.getMaxDamage() - stack.getDamageValue()) < 10) continue;
            // Only consider pickaxes for mining (swords/axes are not effective on stone)
            if (stack.getItem() instanceof net.minecraft.world.item.PickaxeItem pickaxe) {
                float speed = pickaxe.getTier().getSpeed();
                if (speed > bestSpeed) {
                    bestSpeed = speed;
                }
            }
        }
        return bestSpeed;
    }

    /**
     * Check if the bot has any pickaxe in inventory.
     */
    private boolean hasPickaxe(FakePlayer bot) {
        for (int i = 0; i <= 40; i++) {
            ItemStack stack = bot.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            String name = stack.getItem().getClass().getSimpleName().toLowerCase();
            if (name.contains("pickaxe")) return true;
        }
        return false;
    }
}
