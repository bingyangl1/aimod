package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Move to a target position using A* pathfinding + auto-dig/pillar.
 *
 * <p>When A* pathfinding fails (e.g., target is underground or in the sky),
 * this action automatically digs down or pillars up to reach the target.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>Try A* pathfinding first</li>
 *   <li>If A* fails and target is below → auto-dig down</li>
 *   <li>If A* fails and target is above → auto-pillar up (future)</li>
 *   <li>When close enough → complete</li>
 * </ol>
 */
public class MoveToAction extends Action {
    private static final int STUCK_TIMEOUT = 200; // 10 seconds
    private static final int DIG_COOLDOWN = 5;    // ticks between digs

    private final BlockPos targetPos;
    private final double speed;
    private boolean pathfindingStarted;
    private int stuckTicks;
    private int digCooldown;
    private boolean autoDigging;

    public MoveToAction(BlockPos targetPos, double speed) {
        super("Move to " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.speed = speed;
        this.pathfindingStarted = false;
        this.stuckTicks = 0;
        this.digCooldown = 0;
        this.autoDigging = false;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            pathfindingStarted = false;
            stuckTicks = 0;
            digCooldown = 0;
            autoDigging = false;
        }

        if (status == ActionStatus.IN_PROGRESS) {
            double dy = targetPos.getY() - bot.getY();

            // If target is significantly below us, try auto-dig
            if (dy < -2 && !autoDigging) {
                autoDigging = true;
                DevLog.info("MOVE_TO_AUTO_DIG", "target={}, dy={}", targetPos.toShortString(), (int) dy);
            }

            // Auto-dig mode: dig down to reach underground target
            if (autoDigging) {
                executeAutoDig(bot);
                return;
            }

            // Normal mode: A* pathfinding
            if (!pathfindingStarted) {
                navigateWithPathfinding(bot, targetPos);
                pathfindingStarted = true;
                DevLog.info("MOVE_TO_PATHFIND", "target={}", targetPos.toShortString());
            }
        }
    }

    /**
     * Auto-dig down to reach underground target.
     * Digs the block below the bot's feet, lets gravity pull it down.
     */
    private void executeAutoDig(FakePlayer bot) {
        if (digCooldown > 0) {
            digCooldown--;
            return;
        }

        // Check if we've reached the target Y
        double dy = targetPos.getY() - bot.getY();
        if (dy >= -1) {
            // Reached target level — switch to horizontal navigation
            autoDigging = false;
            pathfindingStarted = false;
            DevLog.info("MOVE_TO_DIG_REACHED", "target={}", targetPos.toShortString());
            return;
        }

        if (!(bot.level() instanceof ServerLevel level)) return;

        BlockPos belowFeet = bot.blockPosition().below();

        // Safety: don't dig below Y=-64
        if (belowFeet.getY() < -64) {
            DevLog.warn("MOVE_TO_DIG_VOID", "pos={}", belowFeet.toShortString());
            autoDigging = false;
            status = ActionStatus.FAILED;
            setFailReason("Cannot dig below void");
            return;
        }

        BlockState belowState = level.getBlockState(belowFeet);

        // If already air, fall down
        if (belowState.isAir()) {
            if (bot.onGround()) {
                // We're standing on air? Move forward slightly to fall
                bot.setDeltaMovement(0, -0.1, 0);
            }
            return;
        }

        // Safety: don't break bedrock
        float hardness = belowState.getDestroySpeed(level, belowFeet);
        if (hardness < 0) {
            DevLog.warn("MOVE_TO_DIG_UNBREAKABLE", "pos={}", belowFeet.toShortString());
            autoDigging = false;
            status = ActionStatus.FAILED;
            setFailReason("Cannot break bedrock");
            return;
        }

        // Safety: don't dig into liquids
        FluidState fluid = level.getFluidState(belowFeet);
        if (!fluid.isEmpty()) {
            DevLog.warn("MOVE_TO_DIG_LIQUID", "pos={}", belowFeet.toShortString());
            // Try to move horizontally to find a non-liquid spot
            autoDigging = false;
            pathfindingStarted = false;
            return;
        }

        // Break the block below
        level.destroyBlock(belowFeet, true, bot);
        digCooldown = DIG_COOLDOWN;
        DevLog.info("MOVE_TO_DIG", "pos={}, y={}", belowFeet.toShortString(), belowFeet.getY());
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            // Check if arrived
            if (bot.getMovementController().hasArrived()) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MOVE_TO_ARRIVED", "target={}", targetPos.toShortString());
                return true;
            }

            // Check distance — close enough counts as arrived
            double dx = targetPos.getX() + 0.5 - bot.getX();
            double dy = targetPos.getY() - bot.getY();
            double dz = targetPos.getZ() + 0.5 - bot.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < 4.0) { // within 2 blocks
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MOVE_TO_CLOSE_ENOUGH", "target={}, dist={}",
                        targetPos.toShortString(), String.format("%.1f", Math.sqrt(distSqr)));
                return true;
            }

            // Stuck detection (only when not auto-digging)
            if (!autoDigging) {
                if (bot.getMovementController().isStuck()) {
                    stuckTicks++;
                    if (stuckTicks > STUCK_TIMEOUT) {
                        DevLog.warn("MOVE_TO_STUCK", "target={}, stuckTicks={}", targetPos.toShortString(), stuckTicks);
                        stopNavigation(bot);
                        status = ActionStatus.FAILED;
                        setFailReason("Stuck trying to reach " + targetPos.toShortString());
                        return true;
                    }
                } else {
                    stuckTicks = 0;
                }

                // Re-trigger pathfinding if it completed but we're not there yet
                if (!bot.getMovementController().isNavigating() && distSqr > 4.0) {
                    navigateWithPathfinding(bot, targetPos);
                    DevLog.info("MOVE_TO_REPATH", "target={}", targetPos.toShortString());
                }
            }
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() { return targetPos; }
}