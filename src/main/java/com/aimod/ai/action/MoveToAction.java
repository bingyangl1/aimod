package com.aimod.ai.action;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Move to a target position using A* pathfinding + auto-dig/pillar.
 *
 * <p>Handles all terrain challenges:
 * <ul>
 *   <li>Dig down to underground targets</li>
 *   <li>Dig through walls when pathfinding fails</li>
 *   <li>Pillar up to elevated targets</li>
 *   <li>A* pathfinding for normal terrain</li>
 * </ul>
 */
public class MoveToAction extends Action {
    private static final int STUCK_TIMEOUT = 100; // 5 seconds
    private static final int DIG_COOLDOWN = 5;    // ticks between digs
    private static final int MAX_WALL_DIGS = 20;   // max blocks to dig through

    private final BlockPos targetPos;
    private final double speed;
    private boolean pathfindingStarted;
    private int stuckTicks;
    private int digCooldown;
    private int wallDigsDone;

    // Navigation modes
    private enum Mode { PATHFIND, DIG_DOWN, DIG_THROUGH, PILLAR_UP }
    private Mode mode = Mode.PATHFIND;

    public MoveToAction(BlockPos targetPos, double speed) {
        super("Move to " + targetPos.toShortString());
        this.targetPos = targetPos;
        this.speed = speed;
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
            wallDigsDone = 0;
            mode = Mode.PATHFIND;
        }

        if (status == ActionStatus.IN_PROGRESS) {
            double dy = targetPos.getY() - bot.getY();

            // Auto-select mode based on target position
            if (mode == Mode.PATHFIND && !pathfindingStarted) {
                if (dy < -2) {
                    // Target is significantly below — dig down
                    mode = Mode.DIG_DOWN;
                    DevLog.info("MOVE_TO_MODE", "mode=DIG_DOWN, target={}", targetPos.toShortString());
                } else if (dy > 2) {
                    // Target is significantly above — pillar up
                    mode = Mode.PILLAR_UP;
                    DevLog.info("MOVE_TO_MODE", "mode=PILLAR_UP, target={}", targetPos.toShortString());
                } else {
                    // Target is at similar Y — try pathfinding
                    navigateWithPathfinding(bot, targetPos);
                    pathfindingStarted = true;
                    DevLog.info("MOVE_TO_PATHFIND", "target={}", targetPos.toShortString());
                }
            }

            // Execute current mode
            switch (mode) {
                case DIG_DOWN -> executeDigDown(bot);
                case DIG_THROUGH -> executeDigThrough(bot);
                case PILLAR_UP -> executePillarUp(bot);
                case PATHFIND -> {} // handled by pathfindingStarted above
            }
        }
    }

    /**
     * Dig down to reach underground target.
     */
    private void executeDigDown(FakePlayer bot) {
        if (digCooldown > 0) { digCooldown--; return; }

        double dy = targetPos.getY() - bot.getY();
        if (dy >= -1) {
            // Reached target level — switch to horizontal navigation
            mode = Mode.PATHFIND;
            pathfindingStarted = false;
            DevLog.info("MOVE_TO_DIG_REACHED", "target={}", targetPos.toShortString());
            return;
        }

        if (!(bot.level() instanceof ServerLevel level)) return;

        BlockPos belowFeet = bot.blockPosition().below();
        if (belowFeet.getY() < -64) {
            status = ActionStatus.FAILED;
            setFailReason("Cannot dig below void");
            return;
        }

        BlockState belowState = level.getBlockState(belowFeet);
        if (belowState.isAir()) {
            if (bot.onGround()) bot.setDeltaMovement(0, -0.1, 0);
            return;
        }

        float hardness = belowState.getDestroySpeed(level, belowFeet);
        if (hardness < 0) {
            status = ActionStatus.FAILED;
            setFailReason("Cannot break bedrock");
            return;
        }

        FluidState fluid = level.getFluidState(belowFeet);
        if (!fluid.isEmpty()) {
            mode = Mode.PATHFIND;
            pathfindingStarted = false;
            return;
        }

        level.destroyBlock(belowFeet, true, bot);
        digCooldown = DIG_COOLDOWN;
        DevLog.info("MOVE_TO_DIG", "pos={}, y={}", belowFeet.toShortString(), belowFeet.getY());
    }

    /**
     * Dig through wall/obstacle to reach target horizontally.
     * Breaks blocks in the direction of the target.
     */
    private void executeDigThrough(FakePlayer bot) {
        if (digCooldown > 0) { digCooldown--; return; }

        if (!(bot.level() instanceof ServerLevel level)) return;

        // Check if we've arrived
        double dx = targetPos.getX() + 0.5 - bot.getX();
        double dz = targetPos.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dz * dz;
        if (distSqr < 4.0) {
            mode = Mode.PATHFIND;
            pathfindingStarted = false;
            DevLog.info("MOVE_TO_DIG_THROUGH_ARRIVED", "target={}", targetPos.toShortString());
            return;
        }

        if (wallDigsDone >= MAX_WALL_DIGS) {
            // Give up digging, try pathfinding again
            mode = Mode.PATHFIND;
            pathfindingStarted = false;
            wallDigsDone = 0;
            DevLog.warn("MOVE_TO_DIG_THROUGH_LIMIT", "digs={}", wallDigsDone);
            return;
        }

        // Find the block in front of us (toward target)
        Direction dir = getDirectionToward(bot, targetPos);
        BlockPos frontPos = bot.blockPosition().relative(dir);
        BlockPos headPos = frontPos.above();

        BlockState frontState = level.getBlockState(frontPos);
        BlockState headState = level.getBlockState(headPos);

        // If front is passable, move forward
        if (frontState.isAir() && headState.isAir()) {
            bot.setDeltaMovement(dir.getStepX() * 0.2, 0, dir.getStepZ() * 0.2);
            return;
        }

        // Break the blocking block
        if (!frontState.isAir()) {
            float hardness = frontState.getDestroySpeed(level, frontPos);
            if (hardness >= 0 && hardness < 100) { // not bedrock/unbreakable
                level.destroyBlock(frontPos, true, bot);
                wallDigsDone++;
                digCooldown = DIG_COOLDOWN;
                DevLog.info("MOVE_TO_DIG_THROUGH", "pos={}, dir={}", frontPos.toShortString(), dir.getName());
            }
        }

        // Also break head-level block if needed
        if (!headState.isAir()) {
            float hardness = headState.getDestroySpeed(level, headPos);
            if (hardness >= 0 && hardness < 100) {
                level.destroyBlock(headPos, true, bot);
                wallDigsDone++;
            }
        }
    }

    /**
     * Pillar up to reach elevated target.
     */
    private void executePillarUp(FakePlayer bot) {
        if (digCooldown > 0) { digCooldown--; return; }

        double dy = targetPos.getY() - bot.getY();
        if (dy <= 1) {
            // Reached target level
            mode = Mode.PATHFIND;
            pathfindingStarted = false;
            DevLog.info("MOVE_TO_PILLAR_REACHED", "target={}", targetPos.toShortString());
            return;
        }

        if (!(bot.level() instanceof ServerLevel level)) return;

        BlockPos belowFeet = bot.blockPosition().below();
        BlockState belowState = level.getBlockState(belowFeet);

        // Place block below if air
        if (belowState.isAir()) {
            // Find a block item in inventory
            var inv = bot.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
                    if (bi.getBlock() instanceof net.minecraft.world.level.block.FallingBlock) continue;
                    level.setBlock(belowFeet, bi.getBlock().defaultBlockState(), 3);
                    stack.shrink(1);
                    break;
                }
            }
        }

        // Jump
        if (bot.onGround()) {
            bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
        }
        digCooldown = DIG_COOLDOWN;
    }

    /**
     * Get the horizontal direction toward a target position.
     */
    private Direction getDirectionToward(FakePlayer bot, BlockPos target) {
        double dx = target.getX() - bot.getX();
        double dz = target.getZ() - bot.getZ();
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        if (status == ActionStatus.IN_PROGRESS) {
            // Check distance to target
            double dx = targetPos.getX() + 0.5 - bot.getX();
            double dy = targetPos.getY() - bot.getY();
            double dz = targetPos.getZ() + 0.5 - bot.getZ();
            double distSqr = dx * dx + dy * dy + dz * dz;

            // Close enough — done
            if (distSqr < 4.0) {
                stopNavigation(bot);
                status = ActionStatus.COMPLETED;
                DevLog.info("MOVE_TO_CLOSE_ENOUGH", "target={}, dist={}",
                        targetPos.toShortString(), String.format("%.1f", Math.sqrt(distSqr)));
                return true;
            }

            // PATHFIND mode checks
            if (mode == Mode.PATHFIND && pathfindingStarted) {
                // Check if pathfinder says we arrived
                if (bot.getMovementController().hasArrived()) {
                    stopNavigation(bot);
                    status = ActionStatus.COMPLETED;
                    DevLog.info("MOVE_TO_ARRIVED", "target={}", targetPos.toShortString());
                    return true;
                }

                // Stuck detection — switch to dig-through mode
                if (bot.getMovementController().isStuck()) {
                    stuckTicks++;
                    if (stuckTicks > STUCK_TIMEOUT) {
                        stopNavigation(bot);
                        mode = Mode.DIG_THROUGH;
                        wallDigsDone = 0;
                        stuckTicks = 0;
                        DevLog.info("MOVE_TO_STUCK_SWITCH", "mode=DIG_THROUGH, target={}", targetPos.toShortString());
                    }
                } else {
                    stuckTicks = 0;
                }

                // Re-trigger pathfinding if needed
                if (!bot.getMovementController().isNavigating() && distSqr > 4.0) {
                    navigateWithPathfinding(bot, targetPos);
                    DevLog.info("MOVE_TO_REPATH", "target={}", targetPos.toShortString());
                }
            }

            // DIG_DOWN / DIG_THROUGH / PILLAR_UP modes — keep going until close enough
            // These modes are handled in execute(), isComplete just checks distance
        }
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    public BlockPos getTargetPos() { return targetPos; }
}