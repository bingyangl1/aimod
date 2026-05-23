package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Manages behavior chains with priority-based scheduling.
 * Only one chain runs per tick. Higher-priority chains preempt lower ones.
 *
 * <p>When a chain with priority > 50 is active, the AI user task is
 * preempted (survival takes priority over executing user commands).</p>
 */
public class ChainManager {

    private final List<BehaviorChain> chains = new ArrayList<>();
    private BehaviorChain activeChain;

    /** Threshold above which chains preempt user tasks. */
    public static final int PREEMPT_THRESHOLD = 50;

    public ChainManager() {}

    /** Register a chain. Chains are evaluated in priority order each tick. */
    public void addChain(BehaviorChain chain) {
        chains.add(chain);
        Collections.sort(chains);
    }

    /**
     * Tick all chains. Called from FakePlayer.tick() before AI task execution.
     *
     * @return true if the AI user task should be preempted this tick
     */
    public boolean tick(FakePlayer bot) {
        // If current chain is still active, keep running it
        if (activeChain != null && activeChain.isActive()) {
            activeChain.tick(bot);
            return activeChain.priority() > PREEMPT_THRESHOLD;
        }

        // Stop previous chain
        if (activeChain != null) {
            String prev = activeChain.name();
            activeChain.stop();
            activeChain = null;
            DevLog.info("CHAIN_STOP", "chain={}", prev);
        }

        // Find highest-priority chain that should activate
        BehaviorChain best = null;
        for (BehaviorChain chain : chains) {
            if (chain.shouldActivate(bot)) {
                best = chain;
                break; // chains are pre-sorted by priority desc
            }
        }

        if (best != null) {
            activeChain = best;
            DevLog.info("CHAIN_ACTIVATE", "chain={}, priority={}", best.name(), best.priority());
            activeChain.tick(bot);
            return activeChain.priority() > PREEMPT_THRESHOLD;
        }

        return false;
    }

    /** Stop all chains (e.g., on task cancel). */
    public void stopAll() {
        if (activeChain != null) {
            activeChain.stop();
            activeChain = null;
        }
    }

    public BehaviorChain getActiveChain() { return activeChain; }
    public boolean isActive() { return activeChain != null && activeChain.isActive(); }
}
