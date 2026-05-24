package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Base class for a single movement step from src to dest.
 * Inspired by Baritone's Movement abstraction, adapted for server-side
 * FakePlayer control (direct position/movement calls, no input simulation).
 *
 * Each concrete subclass defines how to execute one type of movement
 * (flat walk, pillar up, bridge, etc.) and how to calculate its cost
 * for the A* pathfinder.
 */
public abstract class BotMovement {

    public enum Status {
        PENDING,
        PREPPING,
        RUNNING,
        COMPLETE,
        FAILED
    }

    protected final BlockPos src;
    protected final BlockPos dest;
    protected Status status;

    protected BotMovement(BlockPos src, BlockPos dest) {
        this.src = src.immutable();
        this.dest = dest.immutable();
        this.status = Status.PENDING;
    }

    /**
     * Calculate the movement cost for pathfinding.
     * Called once during A* expansion; result is cached by the pathfinder.
     *
     * @param level the server level (for block state queries)
     * @return movement cost in ticks, or POSITIVE_INFINITY if impossible
     */
    public abstract double calculateCost(ServerLevel level);

    /**
     * Execute one tick of this movement.
     *
     * @param bot the FakePlayer to move
     * @return true when the movement is complete or failed (check getStatus())
     */
    public abstract boolean update(FakePlayer bot);

    /**
     * Check whether the prerequisites for this movement are satisfied.
     */
    public abstract boolean canExecute(FakePlayer bot);

    public BlockPos getSrc() { return src; }
    public BlockPos getDest() { return dest; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public BlockPos getDirection() { return dest.subtract(src); }

    /**
     * Factory: create the appropriate movement type for src→dest.
     */
    public static BotMovement create(BlockPos src, BlockPos dest) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();
        int adx = Math.abs(dx), adz = Math.abs(dz);

        if (dy == 1 && adx + adz == 0) return new MovementPillar(src, dest);
        if (dy == 1 && adx + adz == 2) return new MovementAscend(src, dest);
        if (dy == -1 && adx + adz == 1 && adx + adz <= 1) return new MovementDescend(src, dest);
        if (dy < -1) return new MovementFall(src, dest);
        if (dy == -1 && adx + adz == 0) return new MovementDownward(src, dest);
        if (dy == 0 && adx + adz == 2) return new MovementDiagonal(src, dest);
        if (dy == 0 && adx + adz == 0) return null; // same block
        if (dy == 0 && adx <= 1 && adz <= 1 && adx + adz == 1) return new MovementTraverse(src, dest);
        // Default: traverse for simple moves
        return new MovementTraverse(src, dest);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + src.toShortString() + " -> " + dest.toShortString() + " " + status + "}";
    }
}
