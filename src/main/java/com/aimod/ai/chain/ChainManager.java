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
 * <p>Preemption threshold: chains with priority > PREEMPT_THRESHOLD (65)
 * pause the user task while active. Only DangerChain (90) and DefenseChain (70)
 * preempt; FoodChain (55) and UnstuckChain (50) run alongside the task.</p>
 */
public class ChainManager {

    private final List<BehaviorChain> chains = new ArrayList<>();
    private BehaviorChain activeChain;
    private BehaviorChain lastActiveChain;
    private boolean sorted;

    public static final int PREEMPT_THRESHOLD = 65;

    public ChainManager() {}

    /** Register a chain. Call before ticking starts (not thread-safe during ticks). */
    public void addChain(BehaviorChain chain) {
        chains.add(chain);
        sorted = false;
    }

    private void ensureSorted() {
        if (!sorted) { Collections.sort(chains); sorted = true; }
    }

    /**
     * Tick all chains. Called from FakePlayer.tick() before AI task execution.
     *
     * @return true if the AI user task should be preempted this tick
     */
    public boolean tick(FakePlayer bot) {
        ensureSorted();

        // If current chain is still active, check for preemption by higher-priority chains
        if (activeChain != null && activeChain.isActive()) {
            for (BehaviorChain chain : chains) {
                if (chain == activeChain) break; // same or lower priority, stop checking
                if (chain.priority() > activeChain.priority() && chain.shouldActivate(bot)) {
                    DevLog.info("CHAIN_PREEMPT", "higher={}({}) interrupts lower={}({})",
                            chain.name(), chain.priority(), activeChain.name(), activeChain.priority());
                    activeChain.stop();
                    lastActiveChain = activeChain;
                    activeChain = chain;
                    break;
                }
            }
            activeChain.tick(bot);
            return activeChain.priority() > PREEMPT_THRESHOLD;
        }

        // Stop previous chain
        if (activeChain != null) {
            String prev = activeChain.name();
            activeChain.stop();
            lastActiveChain = activeChain;
            activeChain = null;
            DevLog.info("CHAIN_STOP", "chain={}", prev);
        }

        // Find highest-priority chain that should activate
        BehaviorChain best = null;
        for (BehaviorChain chain : chains) {
            if (chain == lastActiveChain) continue; // don't reactivate same tick
            if (chain.shouldActivate(bot)) {
                best = chain;
                break;
            }
        }
        lastActiveChain = null;

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
        lastActiveChain = null;
    }

    public BehaviorChain getActiveChain() { return activeChain; }
    public boolean isActive() { return activeChain != null && activeChain.isActive(); }
}
