package com.example.aimod.ai.action;

import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.WorldScanner;
import com.example.aimod.ai.pathing.Pathfinder;
import com.example.aimod.ai.pathing.PathResult;
import com.example.aimod.entity.AIBotEntity;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
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

    private final ResourceType resourceType;
    private final int count;
    private final int searchRadius;

    private BlockPos currentTarget;
    private int gatheredCount;
    private int breakProgress;
    private int breakTime;
    private boolean searching;

    private double lastDistSqr;
    private int stuckTicks;
    private int placeBlockCooldown;

    public GatherResourceAction(ResourceType resourceType, int count) {
        this(resourceType, count, 32);
    }

    public GatherResourceAction(ResourceType resourceType, int count, int searchRadius) {
        super("Gather " + count + " " + resourceType.name());
        this.resourceType = resourceType;
        this.count = Math.max(1, count);
        this.searchRadius = searchRadius;
        this.gatheredCount = 0;
        this.breakProgress = 0;
        this.breakTime = 0;
        this.searching = true;
        this.lastDistSqr = Double.MAX_VALUE;
        this.stuckTicks = 0;
        this.placeBlockCooldown = 0;
    }

    @Override
    public boolean canExecute(AIBotEntity bot) {
        return true;
    }

    @Override
    public void execute(AIBotEntity bot) {
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

        if (currentTarget == null || searching) {
            currentTarget = findResource(bot);
            searching = false;
            lastDistSqr = Double.MAX_VALUE;
            stuckTicks = 0;
            placeBlockCooldown = 0;

            if (currentTarget == null) {
                DevLog.warn("GATHER_NO_RESOURCE", "type={}, radius={}", resourceType, searchRadius);
                status = ActionStatus.FAILED;
                return;
            }
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
        if (distSqr <= REACH_DISTANCE_SQR) {
            stopNavigation(bot);
            breakTarget(bot, blockState);
            return;
        }

        // === Try to reach the target ===
        boolean moved = false;

        // Strategy 1: Adjacent target — move directly (skip A* overhead)
        if (distSqr <= 25.0) { // Within 5 blocks
            BlockPos standPos = findAdjacentStandPos(bot);
            if (standPos != null) {
                navigateTo(bot, standPos, 1.0);
                moved = true;
                DevLog.info("GATHER_MOVE_ADJACENT", "stand={}", standPos.toShortString());
            }
        }

        // Strategy 2: Use A* pathfinder for distant or complex terrain
        if (!moved) {
            BlockPos standPos = findBestStandPos(bot);
            if (standPos != null) {
                if (distSqr > 25.0) {
                    // Distant: use A*
                    moved = navigateWithAStar(bot, standPos);
                }
                if (!moved) {
                    // Fallback: vanilla pathfinding
                    navigateTo(bot, standPos, 1.0);
                    moved = true;
                    DevLog.info("GATHER_MOVE_DIRECT", "stand={}", standPos.toShortString());
                }
            }
        }

        // Strategy 3: Target is elevated, no stand pos found — try to pillar up
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

            if (stuckTicks > STUCK_TIMEOUT) {
                DevLog.warn("GATHER_STUCK", "type={}, target={}, dist={}, skipping",
                        resourceType, currentTarget.toShortString(),
                        String.format("%.1f", Math.sqrt(distSqr)));
                resetTarget();
            }
        } else {
            // No strategy worked — skip this target
            DevLog.warn("GATHER_UNREACHABLE", "type={}, target={}", resourceType, currentTarget.toShortString());
            resetTarget();
        }
    }

    @Override
    public boolean isComplete(AIBotEntity bot) {
        return status == ActionStatus.COMPLETED || status == ActionStatus.FAILED;
    }

    // ========== Target Breaking ==========

    private void breakTarget(AIBotEntity bot, BlockState blockState) {
        if (breakProgress == 0) {
            float hardness = blockState.getDestroySpeed(bot.level(), currentTarget);
            breakTime = Math.max(20, (int) (hardness * 20));
            DevLog.info("GATHER_BREAKING", "type={}, pos={}, breakTime={}",
                    resourceType, currentTarget.toShortString(), breakTime);
        }

        FakePlayer fakePlayer = getFakePlayer(bot);
        if (fakePlayer != null) {
            fakePlayer.lookAt(
                    currentTarget.getX() + 0.5,
                    currentTarget.getY() + 0.5,
                    currentTarget.getZ() + 0.5);
        }

        breakProgress++;
        if (breakProgress >= breakTime) {
            Entity destroyer = fakePlayer != null ? fakePlayer : bot;
            bot.level().destroyBlock(currentTarget, true, destroyer);
            gatheredCount++;
            DevLog.info("GATHER_COLLECTED", "type={}, total={}", resourceType, gatheredCount);
            resetTarget();
        }
    }

    // ========== Stand Position Finding ==========

    /**
     * Find a valid position to stand adjacent to the target:
     * - Solid block below
     * - Passable feet + head
     * - Not in water/lava
     * Returns the closest valid position, or null.
     */
    private BlockPos findBestStandPos(AIBotEntity bot) {
        if (!(bot.level() instanceof ServerLevel level)) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        // Check all 6 faces of the target block
        for (Direction dir : Direction.values()) {
            BlockPos candidate = currentTarget.relative(dir);
            if (isValidStandPos(level, candidate)) {
                double dist = bot.distanceToSqr(
                        candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
        }

        // Also check diagonal positions (for tree trunks, etc.)
        if (best == null) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    for (int dy = -1; dy <= 1; dy++) {
                        BlockPos candidate = currentTarget.offset(dx, dy, dz);
                        if (isValidStandPos(level, candidate)) {
                            double dist = bot.distanceToSqr(
                                    candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                            if (dist < bestDist) {
                                bestDist = dist;
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }

        return best;
    }

    /**
     * Find an adjacent stand position (within 2 blocks, for nearby targets).
     */
    private BlockPos findAdjacentStandPos(AIBotEntity bot) {
        if (!(bot.level() instanceof ServerLevel level)) return null;

        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;

        for (Direction dir : Direction.values()) {
            BlockPos candidate = currentTarget.relative(dir);
            if (isValidStandPos(level, candidate)) {
                double dist = bot.distanceToSqr(
                        candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /**
     * Is this a valid position for the bot to stand?
     * - Solid block below
     * - Passable at feet level (y) and head level (y+1)
     * - Not in water or lava
     */
    private boolean isValidStandPos(ServerLevel level, BlockPos pos) {
        BlockPos below = pos.below();
        BlockState belowState = level.getBlockState(below);
        BlockState feetState = level.getBlockState(pos);
        BlockState headState = level.getBlockState(pos.above());

        // Must have solid ground
        if (!Block.isShapeFullBlock(belowState.getCollisionShape(level, below))) {
            return false;
        }

        // Feet and head must be passable
        if (!isPassable(level, pos, feetState) || !isPassable(level, pos.above(), headState)) {
            return false;
        }

        // Avoid water/lava positions
        FluidState fluidFeet = level.getFluidState(pos);
        FluidState fluidBelow = level.getFluidState(below);
        if (!fluidFeet.isEmpty() || !fluidBelow.isEmpty()) {
            return false;
        }

        return true;
    }

    private boolean isPassable(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir()) return true;
        if (state.getFluidState().isEmpty() == false) return true;
        return state.isPathfindable(net.minecraft.world.level.pathfinder.PathComputationType.LAND);
    }

    // ========== A* Navigation ==========

    private boolean navigateWithAStar(AIBotEntity bot, BlockPos goal) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) return false;

        BlockPos botPos = bot.blockPosition();
        DevLog.info("GATHER_ASTAR", "from={}, to={}", botPos.toShortString(), goal.toShortString());

        Pathfinder pathfinder = new Pathfinder(serverLevel, botPos, goal);
        PathResult result = pathfinder.findPath();

        if (result.isFound() && result.getLength() >= 2) {
            // Navigate to second waypoint (first is current position)
            BlockPos nextWp = result.getPath().get(1);
            bot.getNavigation().moveTo(nextWp.getX() + 0.5, nextWp.getY(), nextWp.getZ() + 0.5, 1.0);
            DevLog.info("GATHER_ASTAR_OK", "length={}, next={}", result.getLength(), nextWp.toShortString());
            return true;
        }

        DevLog.info("GATHER_ASTAR_FAIL", "reason=no_path");
        return false;
    }

    // ========== Pillar Up (Place Block Below) ==========

    /**
     * Try to place a block below the bot to reach an elevated target.
     * Finds a throwaway block in inventory and places it below the bot.
     */
    private boolean tryPillarUp(AIBotEntity bot) {
        if (placeBlockCooldown > 0) {
            placeBlockCooldown--;
            return true; // Wait, don't skip
        }

        // Find a throwaway block in bot inventory
        ItemStack throwaway = findThrowawayBlock(bot);
        if (throwaway.isEmpty()) {
            DevLog.warn("GATHER_PILLAR_NO_BLOCKS", "no throwaway blocks in inventory");
            return false;
        }

        BlockPos belowBot = bot.blockPosition().below();
        BlockState belowState = bot.level().getBlockState(belowBot);

        // If there's already a solid block below, try to jump up
        if (Block.isShapeFullBlock(belowState.getCollisionShape(bot.level(), belowBot))) {
            // Jump and place
            if (bot.onGround()) {
                bot.jumpFromGround();
                placeBlockCooldown = PLACE_BLOCK_COOLDOWN;
                DevLog.info("GATHER_PILLAR_JUMP", "pos={}", bot.blockPosition().toShortString());
            }
            return true;
        }

        // Place a block below the bot
        Block block = throwaway.getItem() instanceof BlockItem blockItem ? blockItem.getBlock() : null;
        if (block != null) {
            BlockState placeState = block.defaultBlockState();
            bot.level().setBlock(belowBot, placeState, 3);
            throwaway.shrink(1);
            placeBlockCooldown = PLACE_BLOCK_COOLDOWN;
            DevLog.info("GATHER_PILLAR_PLACED", "block={}, pos={}", block.getName().getString(), belowBot.toShortString());
            return true;
        }

        return false;
    }

    /**
     * Find a throwaway block in the bot's inventory (dirt, cobblestone, etc.)
     */
    private ItemStack findThrowawayBlock(AIBotEntity bot) {
        var inventory = bot.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof BlockItem blockItem) {
                Block block = blockItem.getBlock();
                // Only use "cheap" blocks for scaffolding
                if (isThrowawayBlock(block)) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean isThrowawayBlock(Block block) {
        return block == Blocks.DIRT || block == Blocks.COBBLESTONE
                || block == Blocks.GRASS_BLOCK || block == Blocks.COARSE_DIRT
                || block == Blocks.STONE || block == Blocks.NETHERRACK
                || block == Blocks.OAK_PLANKS || block == Blocks.COBBLESTONE;
    }

    // ========== Water Handling ==========

    private boolean isInWater(AIBotEntity bot) {
        return bot.level().getFluidState(bot.blockPosition()).isEmpty() == false
                || bot.level().getFluidState(bot.blockPosition().below()).isEmpty() == false;
    }

    /**
     * Try to move out of water current by jumping or moving to a dry adjacent block.
     */
    private void handleWaterEscape(AIBotEntity bot) {
        // Try jumping out of water
        if (bot.onGround() || bot.isInWater()) {
            bot.jumpFromGround();
        }

        // Find nearest dry adjacent block
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos candidate = bot.blockPosition().relative(dir);
            FluidState fluid = bot.level().getFluidState(candidate);
            BlockState state = bot.level().getBlockState(candidate);
            if (fluid.isEmpty() && state.isAir()) {
                BlockPos below = candidate.below();
                if (bot.level().getBlockState(below).isSolidRender()) {
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
        currentTarget = null;
        searching = true;
        breakProgress = 0;
    }

    private BlockPos findResource(AIBotEntity bot) {
        WorldScanner scanner = new WorldScanner(bot);
        List<BlockPos> candidates = new ArrayList<>();

        for (Block block : getBlocksForType()) {
            List<BlockPos> found = scanner.findNearbyBlocks(block, searchRadius);
            candidates.addAll(found);
        }

        if (candidates.isEmpty()) {
            return null;
        }

        candidates.sort(Comparator.comparingDouble(pos -> bot.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));

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