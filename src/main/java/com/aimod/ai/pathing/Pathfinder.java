package com.aimod.ai.pathing;

import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;

import java.util.*;

/**
 * A* pathfinder inspired by Baritone's AStarPathFinder.
 * 
 * Key features:
 * - HashMap for O(1) node lookup
 * - PriorityQueue (binary heap) as open set
 * - Time-bounded search with best-so-far fallback
 * - Euclidean distance heuristic
 * - 16 movement types: 4 cardinal, 4 diagonal, 4 ascend, 4 descend
 * - Multi-block fall support (up to 4 blocks)
 * - Diagonal corner-cutting validation
 *
 * Supports two modes:
 * - CalculationContext mode (recommended): thread-safe, for async pathfinding
 * - ServerLevel mode (legacy): server-thread only
 */
public class Pathfinder {

    private static final long DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_MAX_RADIUS = 20;

    private final ServerLevel level;
    private final CalculationContext ctx;
    private final int startX, startY, startZ;
    private final int goalX, goalY, goalZ;
    private final long timeoutMs;
    private final int maxRadius;

    private final HashMap<Long, PathNode> nodeMap;
    private final BinaryHeapOpenSet openSet;
    private boolean cancelled;

    /**
     * Create pathfinder with a pre-built CalculationContext (thread-safe, recommended).
     * Call ctx.preloadRegion() on the server thread before calling findPath() from a background thread.
     */
    public Pathfinder(CalculationContext ctx, BlockPos start, BlockPos goal) {
        this(ctx, start, goal, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RADIUS);
    }

    public Pathfinder(CalculationContext ctx, BlockPos start, BlockPos goal, long timeoutMs, int maxRadius) {
        this.ctx = ctx;
        this.level = ctx.getLevel();
        this.startX = start.getX();
        this.startY = start.getY();
        this.startZ = start.getZ();
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.timeoutMs = timeoutMs;
        this.maxRadius = maxRadius;
        this.nodeMap = new HashMap<>();
        this.openSet = new BinaryHeapOpenSet();
    }

    /**
     * Legacy constructor using raw ServerLevel (synchronous server-thread use only).
     * For async pathfinding, use the CalculationContext constructor instead.
     */
    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal) {
        this(level, start, goal, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RADIUS);
    }

    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal, long timeoutMs, int maxRadius) {
        this.ctx = null;
        this.level = level;
        this.startX = start.getX();
        this.startY = start.getY();
        this.startZ = start.getZ();
        this.goalX = goal.getX();
        this.goalY = goal.getY();
        this.goalZ = goal.getZ();
        this.timeoutMs = timeoutMs;
        this.maxRadius = maxRadius;
        this.nodeMap = new HashMap<>();
        this.openSet = new BinaryHeapOpenSet();
    }

    public PathResult findPath() {
        long startTime = System.currentTimeMillis();

        PathNode startNode = getOrCreateNode(startX, startY, startZ);
        startNode.cost = 0;
        startNode.heuristic = PathNode.heuristic(startX, startY, startZ, goalX, goalY, goalZ);
        startNode.combinedCost = startNode.heuristic;
        openSet.insert(startNode);
        startNode.inOpenSet = true;

        PathNode bestNode = startNode;
        double bestHeuristic = startNode.heuristic;

        int nodesExplored = 0;
        int movesConsidered = 0;

        while (!openSet.isEmpty() && !cancelled) {
            if ((nodesExplored & 31) == 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                DevLog.info("PATHFINDER_TIMEOUT", "nodes={}, moves={}, elapsed={}ms",
                        nodesExplored, movesConsidered, System.currentTimeMillis() - startTime);
                break;
            }

            PathNode current = openSet.removeLowest();
            current.inOpenSet = false;
            nodesExplored++;

            if (current.x == goalX && current.y == goalY && current.z == goalZ) {
                DevLog.info("PATHFINDER_FOUND", "nodes={}, moves={}, cost={}, elapsed={}ms",
                        nodesExplored, movesConsidered, String.format("%.1f", current.cost),
                        System.currentTimeMillis() - startTime);
                return new PathResult(reconstructPath(current), true, nodesExplored);
            }

            if (current.heuristic < bestHeuristic) {
                bestHeuristic = current.heuristic;
                bestNode = current;
            }

            int dxFromStart = current.x - startX;
            int dzFromStart = current.z - startZ;
            if (dxFromStart * dxFromStart + dzFromStart * dzFromStart > maxRadius * maxRadius) {
                continue;
            }

            for (int[] offset : MoveCost.OFFSETS) {
                int ox = offset[0];
                int oy = offset[1];
                int oz = offset[2];

                int nx = current.x + ox;
                int ny = current.y + oy;
                int nz = current.z + oz;
                movesConsidered++;

                if (ny < -64 || ny > 319) continue;
                BlockPos nPos = new BlockPos(nx, ny, nz);
                if (!isLoaded(nPos)) continue;

                double moveCost = ctx != null
                        ? MoveCost.costOf(ctx, current.x, current.y, current.z, ox, oy, oz)
                        : MoveCost.costOf(level, current.x, current.y, current.z, ox, oy, oz);
                if (moveCost >= MoveCost.VOID_COST) continue;

                double tentativeCost = current.cost + moveCost;

                PathNode neighbor = getOrCreateNode(nx, ny, nz);

                if (tentativeCost + 0.01 < neighbor.cost) {
                    neighbor.previous = current;
                    neighbor.cost = tentativeCost;
                    neighbor.heuristic = PathNode.heuristic(nx, ny, nz, goalX, goalY, goalZ);
                    neighbor.combinedCost = tentativeCost + neighbor.heuristic;

                    if (neighbor.inOpenSet) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                        neighbor.inOpenSet = true;
                    }
                }
            }

            expandMultiBlockFalls(current, movesConsidered);
        }

        DevLog.info("PATHFINDER_BEST_SO_FAR", "nodes={}, moves={}, bestDist={}, elapsed={}ms",
                nodesExplored, movesConsidered, String.format("%.1f", bestHeuristic),
                System.currentTimeMillis() - startTime);

        if (bestHeuristic < 3.0) {
            return new PathResult(reconstructPath(bestNode), true, nodesExplored);
        }

        return new PathResult(Collections.emptyList(), false, nodesExplored);
    }

    private void expandMultiBlockFalls(PathNode current, int movesConsidered) {
        int[][] cardinalOffsets = {{1,0},{-1,0},{0,1},{0,-1}};

        for (int[] dir : cardinalOffsets) {
            int nx = current.x + dir[0];
            int nz = current.z + dir[1];

            int maxFall = ctx != null ? ctx.getMaxFallBlocks() : MoveCost.MAX_FALL_BLOCKS;
            for (int fall = 2; fall <= maxFall; fall++) {
                int ny = current.y - fall;
                if (ny < -64) break;
                BlockPos fallPos = new BlockPos(nx, ny, nz);
                if (!isLoaded(fallPos)) break;

                BlockPos midFeet = new BlockPos(nx, current.y, nz);
                BlockPos midHead = new BlockPos(nx, current.y + 1, nz);
                boolean midFeetPassable = canWalkThrough(midFeet);
                boolean midHeadPassable = canWalkThrough(midHead);

                if (!midFeetPassable || !midHeadPassable) break;

                BlockPos destFeet = fallPos;
                BlockPos destHead = new BlockPos(nx, ny + 1, nz);
                BlockPos destFloor = new BlockPos(nx, ny - 1, nz);

                BlockState destFeetState = getBlockState(destFeet);
                BlockState destHeadState = getBlockState(destHead);
                BlockState destFloorState = getBlockState(destFloor);

                boolean destFeetPassable = canWalkThrough(destFeet);
                boolean destHeadPassable = canWalkThrough(destHead);
                boolean destFloorSolid = canWalkOn(destFloor, destFloorState);

                if (!destFeetPassable || !destHeadPassable || !destFloorSolid) continue;

                double fallThreshold = ctx != null ? ctx.getFallDamageThreshold() : MoveCost.FALL_DAMAGE_THRESHOLD;
                double moveCost = MoveCost.WALK_ONE_BLOCK + MoveCost.FALL_N_BLOCK(fall);
                if (fall > fallThreshold) {
                    moveCost += (fall - fallThreshold) * 2.0;
                }

                double tentativeCost = current.cost + moveCost;
                PathNode neighbor = getOrCreateNode(nx, ny, nz);

                if (tentativeCost + 0.01 < neighbor.cost) {
                    neighbor.previous = current;
                    neighbor.cost = tentativeCost;
                    neighbor.heuristic = PathNode.heuristic(nx, ny, nz, goalX, goalY, goalZ);
                    neighbor.combinedCost = tentativeCost + neighbor.heuristic;

                    if (neighbor.inOpenSet) {
                        openSet.update(neighbor);
                    } else {
                        openSet.insert(neighbor);
                        neighbor.inOpenSet = true;
                    }
                }

                break;
            }
        }
    }

    private PathNode getOrCreateNode(int x, int y, int z) {
        long hash = PathNode.hash(x, y, z);
        PathNode node = nodeMap.get(hash);
        if (node == null) {
            node = new PathNode(x, y, z);
            nodeMap.put(hash, node);
        }
        return node;
    }

    private List<BlockPos> reconstructPath(PathNode end) {
        LinkedList<BlockPos> path = new LinkedList<>();
        PathNode current = end;
        while (current != null) {
            path.addFirst(current.toBlockPos());
            current = current.previous;
        }
        return path;
    }

    public void cancel() {
        this.cancelled = true;
    }

    // Internal helpers (dispatch between ctx and level)

    private BlockState getBlockState(BlockPos pos) {
        return ctx != null ? ctx.getBlockState(pos) : level.getBlockState(pos);
    }

    private boolean isLoaded(BlockPos pos) {
        return ctx != null ? ctx.isLoaded(pos) : level.isLoaded(pos);
    }

    private boolean canWalkThrough(BlockPos pos) {
        BlockState state = getBlockState(pos);
        return ctx != null
                ? MoveCost.canWalkThrough(ctx, pos, state)
                : MoveCost.canWalkThrough(level, pos, state);
    }

    private boolean canWalkOn(BlockPos pos, BlockState state) {
        return ctx != null
                ? MoveCost.canWalkOn(ctx, pos, state)
                : MoveCost.canWalkOn(level, pos, state);
    }
}