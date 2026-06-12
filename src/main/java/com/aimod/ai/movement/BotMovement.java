package com.aimod.ai.movement;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collections;
import java.util.Set;

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

    /**
     * Returns the set of positions where the bot's feet can be considered
     * "at" this movement's destination. Used for accurate arrival detection,
     * path deviation checks, and path splicing.
     *
     * <p>Default returns a singleton with {@link #dest}. Override for
     * movements where multiple positions are valid (e.g., long falls).</p>
     */
    public Set<BlockPos> calculateValidPositions() {
        return Collections.singleton(dest);
    }

    /**
     * Check whether the bot has arrived at this movement's destination
     * based on valid positions.
     */
    public boolean isAtDestination(FakePlayer bot) {
        BlockPos feet = bot.blockPosition();
        for (BlockPos vp : calculateValidPositions()) {
            if (feet.equals(vp) || feet.distSqr(vp) <= 1) return true;
        }
        return false;
    }

    public BlockPos getSrc() { return src; }
    public BlockPos getDest() { return dest; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public BlockPos getDirection() { return dest.subtract(src); }

    /**
     * Factory: create the appropriate movement type for src→dest.
     * Uses position-based dispatch only (no world access).
     */
    public static BotMovement create(BlockPos src, BlockPos dest) {
        return create(src, dest, null);
    }

    /**
     * Factory: create the appropriate movement type for src→dest with world access.
     * Enables water and climb detection.
     */
    public static BotMovement create(BlockPos src, BlockPos dest, net.minecraft.server.level.ServerLevel level) {
        int dx = dest.getX() - src.getX();
        int dy = dest.getY() - src.getY();
        int dz = dest.getZ() - src.getZ();
        int adx = Math.abs(dx), adz = Math.abs(dz);

        // Check for climbable blocks (ladder/vine)
        if (level != null && Math.abs(dy) >= 1 && adx + adz <= 1) {
            BlockState srcState = level.getBlockState(src);
            BlockState destState = level.getBlockState(dest);
            if (MovementClimb.isClimbable(srcState) || MovementClimb.isClimbable(destState)) {
                return new MovementClimb(src, dest);
            }
        }

        if (dy == 1 && adx + adz == 0) return new MovementPillar(src, dest);
        if (dy == 1 && adx + adz == 2) return new MovementAscend(src, dest);
        if (dy == 1 && adx + adz == 1) return new MovementStepUp(src, dest);
        if (dy == -1 && adx + adz == 1) return new MovementDescend(src, dest);
        if (dy <= -2 && adx + adz == 0) return new MovementDigDown(src, dest);
        if (dy < -1) return new MovementFall(src, dest);
        if (dy == -1 && adx + adz == 0) return new MovementDownward(src, dest);
        if (dy == 0 && adx + adz == 2) return new MovementDiagonal(src, dest);
        if (dy == 0 && adx + adz == 0) return null;
        if (dy == 0 && adx <= 1 && adz <= 1 && adx + adz == 1) return new MovementTraverse(src, dest);
        return new MovementTraverse(src, dest);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + src.toShortString() + " -> " + dest.toShortString() + " " + status + "}";
    }
}
