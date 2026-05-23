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
 * - HashMap<Long, PathNode> for O(1) node lookup
 * - PriorityQueue (binary heap) as open set
 * - Time-bounded search with best-so-far fallback
 * - Euclidean distance heuristic
 * - 16 movement types: 4 cardinal, 4 diagonal, 4 ascend, 4 descend
 * - Multi-block fall support (up to 4 blocks)
 * - Diagonal corner-cutting validation
 */
public class Pathfinder {
    
    private static final long DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_MAX_RADIUS = 20;
    
    private final ServerLevel level;
    private final int startX, startY, startZ;
    private final int goalX, goalY, goalZ;
    private final long timeoutMs;
    private final int maxRadius;
    
    private final HashMap<Long, PathNode> nodeMap;
    private final PriorityQueue<PathNode> openSet;
    private boolean cancelled;
    
    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal) {
        this(level, start, goal, DEFAULT_TIMEOUT_MS, DEFAULT_MAX_RADIUS);
    }
    
    public Pathfinder(ServerLevel level, BlockPos start, BlockPos goal, long timeoutMs, int maxRadius) {
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
        this.openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.combinedCost));
    }
    
    /**
     * Run A* search. Returns PathResult with the best path found.
     */
    public PathResult findPath() {
        long startTime = System.currentTimeMillis();
        
        PathNode startNode = getOrCreateNode(startX, startY, startZ);
        startNode.cost = 0;
        startNode.heuristic = PathNode.heuristic(startX, startY, startZ, goalX, goalY, goalZ);
        startNode.combinedCost = startNode.heuristic;
        openSet.add(startNode);
        startNode.inOpenSet = true;
        
        PathNode bestNode = startNode;
        double bestHeuristic = startNode.heuristic;
        
        int nodesExplored = 0;
        int movesConsidered = 0;
        
        while (!openSet.isEmpty() && !cancelled) {
            // Timeout check every 32 nodes
            if ((nodesExplored & 31) == 0 && System.currentTimeMillis() - startTime > timeoutMs) {
                DevLog.info("PATHFINDER_TIMEOUT", "nodes={}, moves={}, elapsed={}ms",
                        nodesExplored, movesConsidered, System.currentTimeMillis() - startTime);
                break;
            }
            
            PathNode current = openSet.poll();
            current.inOpenSet = false;
            nodesExplored++;
            
            // Goal check
            if (current.x == goalX && current.y == goalY && current.z == goalZ) {
                DevLog.info("PATHFINDER_FOUND", "nodes={}, moves={}, cost={}, elapsed={}ms",
                        nodesExplored, movesConsidered, String.format("%.1f", current.cost),
                        System.currentTimeMillis() - startTime);
                return new PathResult(reconstructPath(current), true, nodesExplored);
            }
            
            // Track best-so-far
            if (current.heuristic < bestHeuristic) {
                bestHeuristic = current.heuristic;
                bestNode = current;
            }
            
            // Radius check
            int dxFromStart = current.x - startX;
            int dzFromStart = current.z - startZ;
            if (dxFromStart * dxFromStart + dzFromStart * dzFromStart > maxRadius * maxRadius) {
                continue;
            }
            
            // Expand neighbors: 4 cardinal + 4 diagonal
            for (int[] offset : MoveCost.OFFSETS) {
                int ox = offset[0];
                int oy = offset[1];
                int oz = offset[2];
                
                int nx = current.x + ox;
                int ny = current.y + oy;
                int nz = current.z + oz;
                movesConsidered++;
                
                if (ny < -64 || ny > 319) continue;
                if (!level.isLoaded(new BlockPos(nx, ny, nz))) continue;
                
                double moveCost = MoveCost.costOf(level, current.x, current.y, current.z, ox, oy, oz);
                if (moveCost >= MoveCost.VOID_COST) continue;
                
                double tentativeCost = current.cost + moveCost;
                
                long hash = PathNode.hash(nx, ny, nz);
                PathNode neighbor = getOrCreateNode(nx, ny, nz);
                
                if (tentativeCost + 0.01 < neighbor.cost) {
                    neighbor.previous = current;
                    neighbor.cost = tentativeCost;
                    neighbor.heuristic = PathNode.heuristic(nx, ny, nz, goalX, goalY, goalZ);
                    neighbor.combinedCost = tentativeCost + neighbor.heuristic;
                    
                    if (neighbor.inOpenSet) {
                        openSet.remove(neighbor);
                    }
                    openSet.add(neighbor);
                    neighbor.inOpenSet = true;
                }
            }
            
            // Also try multi-block falls from current position
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
    
    /**
     * Expand multi-block fall moves from the current position.
     * When standing at (x,y,z), try falling 2-4 blocks down in each cardinal direction.
     * This handles terrain like cliffs, stairs, and ravines.
     */
    private void expandMultiBlockFalls(PathNode current, int movesConsidered) {
        int[][] cardinalOffsets = {{1,0},{-1,0},{0,1},{0,-1}};
        
        for (int[] dir : cardinalOffsets) {
            int nx = current.x + dir[0];
            int nz = current.z + dir[1];
            
            // Try falling 2, 3, 4 blocks
            for (int fall = 2; fall <= MoveCost.MAX_FALL_BLOCKS; fall++) {
                int ny = current.y - fall;
                if (ny < -64) break;
                if (!level.isLoaded(new BlockPos(nx, ny, nz))) break;
                
                // Check if the horizontal move is passable first
                BlockPos midFeet = new BlockPos(nx, current.y, nz);
                BlockPos midHead = new BlockPos(nx, current.y + 1, nz);
                boolean midFeetPassable = MoveCost.canWalkThrough(level, midFeet, level.getBlockState(midFeet));
                boolean midHeadPassable = MoveCost.canWalkThrough(level, midHead, level.getBlockState(midHead));
                
                if (!midFeetPassable || !midHeadPassable) break; // Can't walk off if blocked
                
                // Check the fall destination
                BlockPos destFeet = new BlockPos(nx, ny, nz);
                BlockPos destHead = new BlockPos(nx, ny + 1, nz);
                BlockPos destFloor = new BlockPos(nx, ny - 1, nz);
                
                BlockState destFeetState = level.getBlockState(destFeet);
                BlockState destHeadState = level.getBlockState(destHead);
                BlockState destFloorState = level.getBlockState(destFloor);
                
                boolean destFeetPassable = MoveCost.canWalkThrough(level, destFeet, destFeetState);
                boolean destHeadPassable = MoveCost.canWalkThrough(level, destHead, destHeadState);
                boolean destFloorSolid = MoveCost.canWalkOn(level, destFloor, destFloorState);
                
                if (!destFeetPassable || !destHeadPassable || !destFloorSolid) continue;
                
                // Valid multi-block fall!
                double moveCost = MoveCost.WALK_ONE_BLOCK + MoveCost.FALL_N_BLOCK(fall);
                
                // Add fall damage cost if applicable
                if (fall > MoveCost.FALL_DAMAGE_THRESHOLD) {
                    moveCost += (fall - MoveCost.FALL_DAMAGE_THRESHOLD) * 2.0;
                }
                
                double tentativeCost = current.cost + moveCost;
                long hash = PathNode.hash(nx, ny, nz);
                PathNode neighbor = getOrCreateNode(nx, ny, nz);
                
                if (tentativeCost + 0.01 < neighbor.cost) {
                    neighbor.previous = current;
                    neighbor.cost = tentativeCost;
                    neighbor.heuristic = PathNode.heuristic(nx, ny, nz, goalX, goalY, goalZ);
                    neighbor.combinedCost = tentativeCost + neighbor.heuristic;
                    
                    if (neighbor.inOpenSet) {
                        openSet.remove(neighbor);
                    }
                    openSet.add(neighbor);
                    neighbor.inOpenSet = true;
                }
                
                break; // Only consider the shortest valid fall for each direction
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
}