package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;

/**
 * A priority-ordered autonomous behavior that can preempt AI task execution.
 * Inspired by Player2NPC's {@code TaskChain} system.
 *
 * <p>Higher priority chains (e.g., danger avoidance) run before lower
 * priority chains (e.g., user tasks). Only one chain is active per tick.</p>
 */
public abstract class BehaviorChain implements Comparable<BehaviorChain> {

    /** Priority 1-100. Higher = runs first. */
    public abstract int priority();

    /** Whether this chain should activate right now. */
    public abstract boolean shouldActivate(FakePlayer bot);

    /** Execute one tick of this chain's behavior. */
    public abstract void tick(FakePlayer bot);

    /** Whether this chain is still active (has more work to do). */
    public abstract boolean isActive();

    /** Stop this chain (cleanup, reset state). */
    public abstract void stop();

    /** Human-readable name for logging. */
    public abstract String name();

    @Override
    public int compareTo(BehaviorChain o) {
        return o.priority() - this.priority(); // descending
    }
}
