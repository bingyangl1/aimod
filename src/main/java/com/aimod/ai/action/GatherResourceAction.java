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
    private static final int INITIAL_SEARCH_RADIUS = 16;
    private static final int MAX_SEARCH_RADIUS = 64;
    private static final int RADIUS_EXPAND_STEP = 16;
    private static final int MAX_NO_RESOURCE_RETRIES = 3;

    private final ResourceType resourceType;
    private final int count;
    private int searchRadius;

    private BlockPos currentTarget;
    private int gatheredCount;
    private long breakStartMs;
    private int breakDurationMs;
    private boolean searching;

    private double lastDistSqr;
    private int stuckTicks;
    private int placeBlockCooldown;
    private final java.util.Set<BlockPos> failedTargets = new java.util.HashSet<>();
    private int consecutiveUnreachable;
    private static final int MAX_CONSECUTIVE_UNREACHABLE = 5;
    public String failReason; // set on failure for player feedback
    private com.aimod.ai.pathing.PathExecutor cachedPathExecutor;
    private BlockPos cachedPathGoal;
    private int pathFailCooldown = 0;
    private static final int PATH_FAIL_COOLDOWN_TICKS = 40;
    private static final int STUCK_BREAK_THRESHOLD = 40; // Try breaking after 2s stuck
    private final ObstacleBreaker obstacleBreaker = new ObstacleBreaker();
    private int noResourceRetries = 0;
    private int waitTicks = 0;
    private int totalTargetsSkipped = 0;
    private static final int MAX_TARGETS_SKIPPED = 10;
    private int consecutivePathFails = 0;
    private static final int MAX_CONSECUTIVE_PATH_FAILS = 5;

    public GatherResourceAction(ResourceType resourceType, int count) {
        this(resourceType, count, INITIAL_SEARCH_RADIUS);
    }

    public GatherResourceAction(ResourceType resourceType, int count, int searchRadius) {
        super("Gather " + count + " " + resourceType.name());
        this.resourceType = resourceType;
        this.count = Math.max(1, count);
        // Small-quantity gathers stay nearby: count<=2 → max 24, count<=8 → max 48
        int maxRadius = count <= 2 ? 24 : (count <= 8 ? 48 : MAX_SEARCH_RADIUS);
        this.searchRadius = Math.min(searchRadius, maxRadius);
        this.gatheredCount = 0;
        this.breakStartMs = 0;
        this.breakDurationMs = 0;
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

        // Global skip limit — prevent infinite loop cycling through unreachable targets
        if (totalTargetsSkipped >= MAX_TARGETS_SKIPPED) {
            failReason = "连续" + totalTargetsSkipped + "个目标无法到达，放弃采集";
            DevLog.warn("GATHER_MAX_TARGETS_SKIPPED", "type={}, skipped={}", resourceType, totalTargetsSkipped);
            status = ActionStatus.FAILED;
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
                    failedTargets.clear(); // clear stale targets from previous radius
                    searching = true;
                    waitTicks = 10; // wait 0.5s before retry
                    DevLog.info("GATHER_EXPAND_RADIUS", "type={}, newRadius={}, retry={}/{}",
                            resourceType, searchRadius, noResourceRetries, MAX_NO_RESOURCE_RETRIES);
                    return;
                }
                DevLog.warn("GATHER_NO_RESOURCE", "type={}, radius={}, retries={}",
                        resourceType, searchRadius, noResourceRetries);
                failReason = "在" + searchRadius + "格内找不到" + resourceType;
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
            // If target is overhead (XZ close but Y > 2 blocks up), pillar up
            if (dxzSqr <= 1.5 && dy > 1.8) {
                boolean pillared = tryPillarUp(bot);
                if (pillared) return;
            }
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
                if (obstacleBreaker.tryBreakObstacle(bot, currentTarget)) {
                    DevLog.info("GATHER_BREAK_OBSTACLE", "target={}", currentTarget.toShortString());
                    stuckTicks = STUCK_BREAK_THRESHOLD - 10;
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
                    totalTargetsSkipped++;
                    resetTarget();
                }
            }
        } else {
            failedTargets.add(currentTarget);
            consecutiveUnreachable++;
            totalTargetsSkipped++;
            DevLog.warn("GATHER_UNREACHABLE", "type={}, target={}, consecutive={}, totalSkipped={}",
                    resourceType, currentTarget.toShortString(), consecutiveUnreachable, totalTargetsSkipped);
            if (consecutiveUnreachable >= MAX_CONSECUTIVE_UNREACHABLE) {
                failReason = "连续" + MAX_CONSECUTIVE_UNREACHABLE + "个目标无法到达，可能需要工具或洞穴入口";
                status = ActionStatus.FAILED;
                return;
            }
            resetTarget();
        }
    }

    @Override
    public boolean isComplete(FakePlayer bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    // ========== Block Breaking ==========

    private void breakTarget(FakePlayer bot, BlockState blockState) {
        if (breakStartMs == 0) {
            int breakTicks = Math.max(1, (int) (blockState.getDestroySpeed(bot.level(), currentTarget) * 20));
            breakDurationMs = breakTicks * 50;
            breakStartMs = System.currentTimeMillis();
            DevLog.info("GATHER_BREAKING", "type={}, pos={}, breakDurationMs={}",
                    resourceType, currentTarget.toShortString(), breakDurationMs);
        }

        bot.swing(net.minecraft.world.InteractionHand.MAIN_HAND);

        if (System.currentTimeMillis() - breakStartMs >= breakDurationMs) {
            ServerLevel level = (ServerLevel) bot.level();
            Block targetBlock = blockState.getBlock();

            if (resourceType == ResourceType.WOOD && com.aimod.config.ModConfig.getVeinMine()
                    && VeinMiningHelper.isLogBlock(targetBlock)) {
                int treeSize = VeinMiningHelper.veinMineTree(level, currentTarget, targetBlock, 64, true);
                gatheredCount += treeSize;
                DevLog.info("GATHER_VEIN_TREE", "type={}, treeSize={}, total={}", resourceType, treeSize, gatheredCount);
            } else {
                level.destroyBlock(currentTarget, true, bot);
                gatheredCount++;
            }
            breakStartMs = 0;
            breakDurationMs = 0;
            DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
            failedTargets.clear(); // successful collection — reset failed targets
            totalTargetsSkipped = 0;
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
            consecutivePathFails = 0; // reset on success
            DevLog.info("GATHER_ASTAR_OK", "length={}, next={}", result.getLength(), String.valueOf(next));
            return true;
        }

        consecutivePathFails++;
        pathFailCooldown = PATH_FAIL_COOLDOWN_TICKS;
        DevLog.info("GATHER_ASTAR_FAIL", "reason=no_path, consecutiveFails={}", consecutivePathFails);

        // After too many consecutive A* failures on same target, give up early
        if (consecutivePathFails >= MAX_CONSECUTIVE_PATH_FAILS) {
            DevLog.warn("GATHER_PATH_GIVE_UP", "type={}, target={}, fails={}",
                    resourceType, goal.toShortString(), consecutivePathFails);
            consecutivePathFails = 0;
            return false; // will trigger unreachable logic in caller
        }
        return false;
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
        breakStartMs = 0;
        breakDurationMs = 0;
        obstacleBreaker.reset();
    }

    private BlockPos findResource(FakePlayer bot) {
        WorldScanner scanner = new WorldScanner(bot);
        List<Block> blockTypes = getBlocksForType();

        // Single-pass batch scan — all block types in one pass
        List<BlockPos> candidates = scanner.findNearbyBlocksBatched(blockTypes, searchRadius);

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

        DevLog.info("GATHER_SCAN_BATCHED", "type={}, blocks={}, radius={}, candidates={}",
                resourceType, blockTypes.size(), searchRadius, candidates.size());
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