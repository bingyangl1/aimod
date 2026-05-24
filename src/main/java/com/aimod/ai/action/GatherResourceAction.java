package com.aimod.ai.action;

import com.aimod.ai.InventoryUtils;
import com.aimod.ai.WorldScanner;
import com.aimod.ai.pathing.MoveCost;
import com.aimod.ai.pathing.Pathfinder;
import com.aimod.ai.pathing.PathResult;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.*;

public class GatherResourceAction extends Action {

    public enum ResourceType {
        WOOD, STONE, DIRT, SAND, COBBLESTONE
    }

    private static final int STUCK_TIMEOUT = 200;
    private static final double REACH_DISTANCE_SQR = 6.25; // 2.5 blocks
    private static final int PLACE_BLOCK_COOLDOWN = 10;
    private static final int MAX_SEARCH_RADIUS = 128;
    private static final int RADIUS_EXPAND_STEP = 32;
    private static final int MAX_NO_RESOURCE_RETRIES = 5;

    private final ResourceType resourceType;
    private final int count;
    private int searchRadius;

    private BlockPos currentTarget;
    private int gatheredCount;
    private int breakProgress;
    private int breakTime;
    private boolean searching;

    private double lastDistSqr;
    private int stuckTicks;
    private int placeBlockCooldown;
    private final java.util.Set<BlockPos> failedTargets = new java.util.HashSet<>();
    private com.aimod.ai.pathing.PathExecutor cachedPathExecutor;
    private BlockPos cachedPathGoal;
    private int pathFailCooldown = 0;
    private static final int PATH_FAIL_COOLDOWN_TICKS = 40;
    private static final int STUCK_BREAK_THRESHOLD = 40; // Try breaking after 2s stuck
    private int obstacleBreakProgress = 0;
    private BlockPos obstacleTarget = null;
    private int noResourceRetries = 0;
    private int waitTicks = 0;

    public GatherResourceAction(ResourceType resourceType, int count) {
        this(resourceType, count, 32);
    }

    public GatherResourceAction(ResourceType resourceType, int count, int searchRadius) {
        super("Gather " + count + " " + resourceType.name());
        this.resourceType = resourceType;
        this.count = Math.max(1, count);
        this.searchRadius = Math.min(searchRadius, MAX_SEARCH_RADIUS);
        this.gatheredCount = 0;
        this.breakProgress = 0;
        this.breakTime = 0;
        this.searching = true;
        this.lastDistSqr = Double.MAX_VALUE;
        this.stuckTicks = 0;
        this.placeBlockCooldown = 0;
    }

    @Override
    public boolean canExecute(FakePlayer bot) {
        return true;
    }

    @Override
    public void execute(FakePlayer bot) {
        if (status == ActionStatus.PENDING) {
            status = ActionStatus.IN_PROGRESS;
            searching = true;
            DevLog.info("GATHER_START", "type={}, count={}, radius={}", resourceType, count, searchRadius);
        }

        if (status != ActionStatus.IN_PROGRESS) return;

        if (gatheredCount >= count) {
            stopNavigation(bot);
            status = ActionStatus.COMPLETED;
            DevLog.info("GATHER_DONE", "type={}, gathered={}", resourceType, gatheredCount);
            return;
        }

        // Wait cooldown (e.g. after expanding radius, give world time)
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (currentTarget == null || searching) {
            currentTarget = findResource(bot);
            searching = false;
            lastDistSqr = Double.MAX_VALUE;
            stuckTicks = 0;
            placeBlockCooldown = 0;

            if (currentTarget == null) {
                noResourceRetries++;
                if (noResourceRetries < MAX_NO_RESOURCE_RETRIES && searchRadius < MAX_SEARCH_RADIUS) {
                    // Expand search radius and retry
                    searchRadius = Math.min(searchRadius + RADIUS_EXPAND_STEP, MAX_SEARCH_RADIUS);
                    searching = true;
                    waitTicks = 10; // wait 0.5s before retry
                    DevLog.info("GATHER_EXPAND_RADIUS", "type={}, newRadius={}, retry={}/{}",
                            resourceType, searchRadius, noResourceRetries, MAX_NO_RESOURCE_RETRIES);
                    return;
                }
                DevLog.warn("GATHER_NO_RESOURCE", "type={}, radius={}, retries={}",
                        resourceType, searchRadius, noResourceRetries);
                status = ActionStatus.FAILED;
                return;
            }
            noResourceRetries = 0; // reset on success
            DevLog.info("GATHER_FOUND", "type={}, pos={}", resourceType, currentTarget.toShortString());
        }

        // Check if target was already broken (by someone else, etc.)
        BlockState blockState = bot.level().getBlockState(currentTarget);
        if (blockState.isAir()) {
            gatheredCount++;
            DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
            resetTarget();
            return;
        }

        // === Handle water current: move out of water first ===
        if (isInWater(bot)) {
            handleWaterEscape(bot);
            return;
        }

        // === Calculate distance to target ===
        double dx = currentTarget.getX() + 0.5 - bot.getX();
        double dy = currentTarget.getY() + 0.5 - bot.getY();
        double dz = currentTarget.getZ() + 0.5 - bot.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        // === Close enough to break? ===
        double dxzSqr = dx * dx + dz * dz;
        boolean canReach = (dxzSqr <= 9.0 && dy >= -1.5 && dy <= 4.0);
        if (canReach) {
            stopNavigation(bot);
            breakTarget(bot, blockState);
            return;
        }

        // === Try to reach the target ===
        boolean moved = false;

        // Strategy 1: Adjacent target — move directly
        if (distSqr <= 25.0) {
            BlockPos standPos = findAdjacentStandPos(bot);
            if (standPos != null) {
                double distToStand = bot.distanceToSqr(
                        standPos.getX() + 0.5, standPos.getY(), standPos.getZ() + 0.5);
                // Only navigate if not already at the stand position
                if (distToStand > 1.0) {
                    navigateTo(bot, standPos, 1.0);
                    DevLog.info("GATHER_MOVE_ADJACENT", "stand={}", standPos.toShortString());
                }
                moved = true;
            }
        }

        // Strategy 2: Use A* pathfinder
        if (!moved) {
            BlockPos standPos = findBestStandPos(bot);
            if (standPos != null) {
                if (pathFailCooldown > 0) {
                    pathFailCooldown--;
                    // Direct move fallback during cooldown
                    navigateTo(bot, standPos, 1.0);
                    moved = true;
                } else {
                    moved = navigateWithAStar(bot, standPos);
                    if (!moved) {
                        navigateTo(bot, standPos, 1.0);
                        moved = true;
                        DevLog.info("GATHER_MOVE_DIRECT", "stand={}", standPos.toShortString());
                    }
                }
            }
        }

        // Strategy 3: Target is elevated — try to pillar up
        if (!moved) {
            if (currentTarget.getY() > bot.blockPosition().getY()) {
                moved = tryPillarUp(bot);
            }
        }

        // === Stuck detection ===
        if (moved) {
            if (distSqr >= lastDistSqr - 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            lastDistSqr = distSqr;

            // After STUCK_BREAK_THRESHOLD, try breaking obstacles in the way
            if (stuckTicks > STUCK_BREAK_THRESHOLD && stuckTicks <= STUCK_TIMEOUT) {
                if (tryBreakObstacle(bot)) {
                    DevLog.info("GATHER_BREAK_OBSTACLE", "target={}", obstacleTarget != null ? obstacleTarget.toShortString() : "null");
                    stuckTicks = STUCK_BREAK_THRESHOLD - 10; // Reset slightly so we try again
                }
            }
            if (stuckTicks > STUCK_TIMEOUT) {
                if (currentTarget.getY() > bot.blockPosition().getY() && tryPillarUp(bot)) {
                    stuckTicks = STUCK_BREAK_THRESHOLD;
                    DevLog.info("GATHER_STUCK_PILLAR", "type={}, target={}", resourceType, currentTarget.toShortString());
                } else {
                    DevLog.warn("GATHER_STUCK", "type={}, target={}, dist={}, skipping",
                            resourceType, currentTarget.toShortString(),
                            String.format("%.1f", Math.sqrt(distSqr)));
                    failedTargets.add(currentTarget);
                    resetTarget();
                }
            }
        } else {
            failedTargets.add(currentTarget);
            DevLog.warn("GATHER_UNREACHABLE", "type={}, target={}", resourceType, currentTarget.toShortString());
            resetTarget();
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    // ========== Block Breaking ==========

    private void breakTarget(FakePlayer bot, BlockState blockState) {
        if (breakTime == 0) {
            breakTime = Math.max(1, (int) (blockState.getDestroySpeed(bot.level(), currentTarget) * 20));
            breakProgress = 0;
            DevLog.info("GATHER_BREAKING", "type={}, pos={}, breakTime={}",
                    resourceType, currentTarget.toShortString(), breakTime);
        }

        breakProgress++;
        bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        if (breakProgress >= breakTime) {
            ServerLevel level = (ServerLevel) bot.level();
            level.destroyBlock(currentTarget, true, bot);
            gatheredCount++;
            breakProgress = 0;
            breakTime = 0;
            DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
            resetTarget();
        }
    }

    // ========== Navigation ==========

    private boolean navigateWithAStar(FakePlayer bot, BlockPos goal) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) return false;

        // Use cached path if goal hasn't changed
        if (cachedPathExecutor != null && cachedPathGoal != null && cachedPathGoal.equals(goal)) {
            if (!cachedPathExecutor.isCompleted() && !cachedPathExecutor.isFailed()) {
                BlockPos next = cachedPathExecutor.tick(bot);
                if (next != null) {
                    navigateTo(bot, next, 1.0);
                    return true;
                }
            }
            cachedPathExecutor = null;
            cachedPathGoal = null;
        }

        BlockPos botPos = bot.blockPosition();
        Pathfinder pathfinder = new Pathfinder(serverLevel, botPos, goal);
        PathResult result = pathfinder.findPath();

        if (result.isFound() && result.getLength() >= 2) {
            cachedPathExecutor = new com.aimod.ai.pathing.PathExecutor(result.getPath());
            cachedPathGoal = goal;
            BlockPos next = cachedPathExecutor.tick(bot);
            if (next != null) {
                navigateTo(bot, next, 1.0);
            }
            DevLog.info("GATHER_ASTAR_OK", "length={}, next={}", result.getLength(), String.valueOf(next));
            return true;
        }

        pathFailCooldown = PATH_FAIL_COOLDOWN_TICKS;
        DevLog.info("GATHER_ASTAR_FAIL", "reason=no_path");
        return false;
    }


    /**
     * Try to break an obstacle block between the bot and the target.
     * Finds the most likely blocking block in the direction of travel and breaks it.
     */
    private boolean tryBreakObstacle(FakePlayer bot) {
        if (currentTarget == null) return false;

        BlockPos botPos = bot.blockPosition();
        BlockPos targetPos = currentTarget;
        
        // Direction toward target
        double dx = targetPos.getX() - botPos.getX();
        double dz = targetPos.getZ() - botPos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.5) return false;
        
        // Normalize direction
        int dirX = (int) Math.signum(dx);
        int dirZ = (int) Math.signum(dz);
        
        // Check blocks in the movement direction at feet and head level
        BlockPos[] candidates = {
            // Block in front at feet level
            botPos.offset(dirX, 0, dirZ),
            // Block in front at head level
            botPos.offset(dirX, 1, dirZ),
            // Block in front one below (for step-up obstacles)
            botPos.offset(dirX, -1, dirZ),
            // Diagonal blocks
            botPos.offset(dirX, 0, 0),
            botPos.offset(0, 0, dirZ),
        };
        
        ServerLevel level = (ServerLevel) bot.level();
        
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            float hardness = state.getDestroySpeed(level, pos);
            
            if (!state.isAir() && hardness >= 0 && hardness <= 3.0
                    && state.getFluidState().isEmpty()
                    && !MoveCost.avoidBreaking(level, pos, state)) {
                // Break this block!
                if (obstacleTarget == null || !obstacleTarget.equals(pos)) {
                    obstacleTarget = pos;
                    obstacleBreakProgress = 0;
                }
                
                int breakTimeTicks = Math.max(10, (int) (hardness * 15));
                obstacleBreakProgress++;
                
                bot.lookAt(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                
                if (obstacleBreakProgress >= breakTimeTicks) {
                    level.destroyBlock(pos, true, bot);
                    DevLog.info("OBSTACLE_BROKEN", "pos={}, block={}", pos.toShortString(),
                            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                    obstacleTarget = null;
                    obstacleBreakProgress = 0;
                    return true;
                }
                return true; // Still breaking
            }
        }
        
        return false; // No breakable obstacle found
    }
    private BlockPos findAdjacentStandPos(FakePlayer bot) {
        BlockPos botPos = bot.blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = currentTarget.relative(dir);
            if (canStandAt(bot, candidate)) {
                double dist = bot.distanceToSqr(
                        candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
        }
        // Also check above target
        BlockPos above = currentTarget.above();
        if (canStandAt(bot, above)) {
            double dist = bot.distanceToSqr(
                    above.getX() + 0.5, above.getY(), above.getZ() + 0.5);
            if (dist < bestDist) {
                best = above;
            }
        }
        return best;
    }

    private BlockPos findBestStandPos(FakePlayer bot) {
        // Search in expanding rings for a standable position near target
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        int searchR = 4;

        for (int dx = -searchR; dx <= searchR; dx++) {
            for (int dz = -searchR; dz <= searchR; dz++) {
                for (int dy = -2; dy <= 3; dy++) {
                    BlockPos candidate = currentTarget.offset(dx, dy, dz);
                    if (canStandAt(bot, candidate)) {
                        double distToBot = bot.distanceToSqr(
                                candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                        double distToTarget = distSqr(candidate, currentTarget);
                        double score = distToBot + distToTarget * 2;
                        if (score < bestScore) {
                            bestScore = score;
                            best = candidate;
                        }
                    }
                }
            }
        }
        return best;
    }

    private double distSqr(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private boolean canStandAt(FakePlayer bot, BlockPos pos) {
        BlockState feet = bot.level().getBlockState(pos);
        BlockState below = bot.level().getBlockState(pos.below());
        BlockState above = bot.level().getBlockState(pos.above());

        boolean feetPassable = !feet.isSolidRender(bot.level(), pos);
        boolean belowSolid = below.isSolidRender(bot.level(), pos.below());
        boolean headClear = !above.isSolidRender(bot.level(), pos.above());

        return feetPassable && belowSolid && headClear;
    }

    // ========== Pillar Up ==========

    private boolean tryPillarUp(FakePlayer bot) {
        if (placeBlockCooldown > 0) {
            placeBlockCooldown--;
            return true;
        }

        ItemStack throwaway = findThrowawayBlock(bot);
        if (throwaway.isEmpty()) {
            boolean mined = mineNearbyGround(bot);
            if (mined) {
                DevLog.info("GATHER_PILLAR_MINING", "mined ground for scaffolding material");
                return true;
            }
            DevLog.warn("GATHER_PILLAR_NO_BLOCKS", "no blocks in inventory and nothing to mine nearby");
            return false;
        }

        BlockPos feetPos = bot.blockPosition();
        BlockPos belowFeet = feetPos.below();
        BlockState belowState = bot.level().getBlockState(belowFeet);
        boolean solidBelow = Block.isShapeFullBlock(belowState.getCollisionShape(bot.level(), belowFeet));

        Block block = throwaway.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        if (block == null) return false;

        if (solidBelow && bot.onGround()) {
            BlockState placeState = block.defaultBlockState();
            bot.level().setBlock(feetPos, placeState, 3);
            throwaway.shrink(1);
            bot.setPos(bot.getX(), bot.getY() + 1, bot.getZ());
            placeBlockCooldown = PLACE_BLOCK_COOLDOWN;
            DevLog.info("GATHER_PILLAR_UP", "block={}, at={}", block, feetPos.toShortString());
            return true;
        }

        return false;
    }

    private boolean mineNearbyGround(FakePlayer bot) {
        BlockPos botPos = bot.blockPosition();
        ServerLevel level = (ServerLevel) bot.level();
        BlockPos[] candidates = {
            botPos.offset(1, -1, 0),
            botPos.offset(-1, -1, 0),
            botPos.offset(0, -1, 1),
            botPos.offset(0, -1, -1),
            botPos.offset(1, -2, 0),
            botPos.offset(-1, -2, 0),
            botPos.offset(0, -2, 1),
            botPos.offset(0, -2, -1),
        };
        for (BlockPos pos : candidates) {
            BlockState state = level.getBlockState(pos);
            float hardness = state.getDestroySpeed(level, pos);
            if (!state.isAir() && hardness >= 0 && hardness <= 3.0
                    && state.getFluidState().isEmpty()) {
                level.destroyBlock(pos, true, bot);
                DevLog.info("GATHER_MINE_GROUND", "pos={}, block={}", pos.toShortString(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
                return true;
            }
        }
        return false;
    }

    private ItemStack findThrowawayBlock(FakePlayer bot) {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                if (isThrowawayBlock(block)) {
                    return stack;
                }
            }
        }
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isThrowawayBlock(Block block) {
        return block == Blocks.DIRT || block == Blocks.COBBLESTONE
                || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT
                || block == Blocks.STONE || block == Blocks.NETHERRACK
                || block == Blocks.OAK_PLANKS;
    }

    // ========== Water Handling ==========

    private boolean isInWater(FakePlayer bot) {
        return !bot.level().getFluidState(bot.blockPosition()).isEmpty()
                || !bot.level().getFluidState(bot.blockPosition().below()).isEmpty();
    }

    private void handleWaterEscape(FakePlayer bot) {
        if (bot.onGround() || bot.isInWater()) {
            bot.jumpFromGround();
        }

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = bot.blockPosition().relative(dir);
            FluidState fluid = bot.level().getFluidState(candidate);
            BlockState state = bot.level().getBlockState(candidate);
            if (fluid.isEmpty() && state.isAir()) {
                BlockPos below = candidate.below();
                if (bot.level().getBlockState(below).isSolidRender(bot.level(), below)) {
                    double dist = bot.distanceToSqr(
                            candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = candidate;
                    }
                }
            }
        }

        if (best != null) {
            navigateTo(bot, best, 1.0);
            DevLog.info("GATHER_WATER_ESCAPE", "moving to {}", best.toShortString());
        }
    }

    // ========== Utilities ==========

    private void resetTarget() {
        cachedPathExecutor = null;
        cachedPathGoal = null;
        currentTarget = null;
        searching = true;
        breakProgress = 0;
    }

    private static final int SHORT_CIRCUIT_THRESHOLD = 10; // Stop scanning after finding enough candidates

    private BlockPos findResource(FakePlayer bot) {
        WorldScanner scanner = new WorldScanner(bot);
        List<BlockPos> candidates = new ArrayList<>();

        for (Block block : getBlocksForType()) {
            List<BlockPos> found = scanner.findNearbyBlocks(block, searchRadius);
            candidates.addAll(found);
            // Short-circuit: if we have enough candidates, stop scanning other block types
            if (candidates.size() >= SHORT_CIRCUIT_THRESHOLD) {
                DevLog.info("GATHER_SCAN_SHORTCIRCUIT", "type={}, found={}, skipping remaining block types",
                        resourceType, candidates.size());
                break;
            }
        }

        // Filter out previously failed targets
        candidates.removeAll(failedTargets);

        if (candidates.isEmpty()) {
            return null;
        }

        // Sort: prefer same Y level (within 1 block), then by distance
        int botY = bot.blockPosition().getY();
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> {
            int dy = Math.abs(pos.getY() - botY);
            double dist = bot.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            return dist + (dy > 1 ? dy * 100.0 : 0);
        }));

        return candidates.get(0);
    }

    private List<Block> getBlocksForType() {
        return switch (resourceType) {
            case WOOD -> List.of(
                    Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG,
                    Blocks.JUNGLE_LOG, Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG,
                    Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG);
            case STONE -> List.of(Blocks.STONE, Blocks.COBBLESTONE);
            case DIRT -> List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT);
            case SAND -> List.of(Blocks.SAND, Blocks.RED_SAND);
            case COBBLESTONE -> List.of(Blocks.COBBLESTONE, Blocks.STONE);
        };
    }

    public ResourceType getResourceType() { return resourceType; }
    public int getCount() { return count; }
    public int getGatheredCount() { return gatheredCount; }
}