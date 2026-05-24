package com.aimod.ai.chain;

import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Danger avoidance chain. Handles lava, fire, deep falls, and drowning.
 * Priority 90 — highest survival priority.
 *
 * <p>Inspired by Player2NPC's WorldSurvivalChain + MLGBucketFallChain.
 * Uses A* pathfinding to escape (not just direct back-off), bridge-building
 * when surrounded, water bucket MLG for falls, and auto-retry of cancelled tasks.</p>
 */
public class DangerChain extends BehaviorChain {

    private boolean active;
    private int escapeTicks;
    private int cooldownTicks;
    private BlockPos lastDangerPos;
    private String savedTaskCommand; // for auto-retry after danger clears
    private BlockPos escapeTarget;
    private boolean mlgDeployed;

    private static final int COOLDOWN = 60;
    private static final int MAX_ESCAPE = 200; // 10 seconds — A* needs more time
    private static final double MLG_FALL_SPEED = -0.7;

    @Override public int priority() { return 90; }

    @Override
    public boolean shouldActivate(FakePlayer bot) {
        if (cooldownTicks > 0) { cooldownTicks--; return false; }

        BlockPos pos = bot.blockPosition();
        var level = bot.level();
        BlockState feet = level.getBlockState(pos);
        BlockState below = level.getBlockState(pos.below());

        boolean urgent = false;
        boolean passive = false;
        boolean falling = bot.getDeltaMovement().y < MLG_FALL_SPEED && !bot.onGround()
                && !bot.isInWater() && !bot.isSwimming();

        if (feet.getBlock() == Blocks.LAVA || feet.getFluidState().isSource()) urgent = true;
        else if (below.getBlock() == Blocks.LAVA) urgent = true;
        else if (bot.isOnFire() && !bot.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE)) urgent = true;
        else if (bot.isInWater() && bot.getAirSupply() < 60) urgent = true;
        else if (falling && DangerZone.isCliffAhead(bot, 3, 1)) urgent = true; // falling + danger ahead = MLG
        else if (DangerZone.isLavaNearby(bot, 3)) passive = true;
        else if (DangerZone.isDeepWater(bot) && bot.getAirSupply() < 150) passive = true;
        else if (bot.onGround() && DangerZone.isCliffAhead(bot)) {
            if (pos.equals(lastDangerPos)) return false;
            passive = true;
        }

        if (urgent || passive) {
            lastDangerPos = pos;
            active = true;
            escapeTicks = 0;
            escapeTarget = null;
            mlgDeployed = false;

            if (urgent) {
                // Save task for auto-retry
                var task = bot.getCurrentTask();
                if (task != null && !task.isCompleted()) {
                    savedTaskCommand = task.getDescription();
                }
                bot.cancelTask();
            }
            return true;
        }
        return false;
    }

    @Override
    public void tick(FakePlayer bot) {
        escapeTicks++;
        var level = bot.level();
        BlockPos pos = bot.blockPosition();
        BlockState feet = level.getBlockState(pos);

        // ---- MLG Water Bucket ----
        if (bot.getDeltaMovement().y < MLG_FALL_SPEED && !bot.onGround() && !mlgDeployed) {
            tryWaterBucketMLG(bot);
            mlgDeployed = true;
        }

        // ---- Escape: use A* pathfinding to find safe destination ----
        if (escapeTarget == null || escapeTicks % 40 == 0) {
            escapeTarget = findSafeEscapeTarget(bot);
        }

        if (escapeTarget != null) {
            // Use MovementController's A* pathfinding
            bot.getMovementController().navigateTo(escapeTarget);
        } else {
            // ---- No path: try bridging ----
            tryBridgeOut(bot);
        }

        // ---- Check if safe ----
        BlockState currentFeet = level.getBlockState(bot.blockPosition());
        boolean lavaSafe = currentFeet.getBlock() != Blocks.LAVA
                && !DangerZone.isLavaNearby(bot, 2);
        boolean fireSafe = !bot.isOnFire();
        boolean waterSafe = bot.getAirSupply() >= 60 || !bot.isInWater();
        boolean cliffSafe = bot.onGround() && !DangerZone.isCliffAhead(bot);
        boolean fellSafe = bot.onGround() || bot.getDeltaMovement().y >= -0.2; // not falling

        boolean isSafe = lavaSafe && fireSafe && waterSafe && cliffSafe && fellSafe;
        boolean timedOut = escapeTicks > MAX_ESCAPE;

        if (isSafe || timedOut) {
            active = false;
            cooldownTicks = COOLDOWN;
            bot.getMovementController().stop();
            bot.setDeltaMovement(0, bot.getDeltaMovement().y, 0);

            // ---- Auto-retry saved task ----
            if (isSafe && savedTaskCommand != null) {
                String cmd = savedTaskCommand;
                savedTaskCommand = null;
                // Delay 1 second then re-submit
                var server = bot.getServer();
                if (server != null) {
                    server.tell(new net.minecraft.server.TickTask(
                        server.getTickCount() + 20, () -> bot.assignTask(cmd)
                    ));
                }
            }
        }
    }

    // ---- A* Pathfinding Escape ----

    private BlockPos findSafeEscapeTarget(FakePlayer bot) {
        var level = bot.level();
        BlockPos pos = bot.blockPosition();
        int searchRadius = 16;

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (int dx = -searchRadius; dx <= searchRadius; dx += 4) {
            for (int dz = -searchRadius; dz <= searchRadius; dz += 4) {
                BlockPos candidate = pos.offset(dx, 0, dz);
                if (candidate.equals(pos)) continue;

                BlockState there = level.getBlockState(candidate);
                BlockState belowThere = level.getBlockState(candidate.below());

                // Must be safe: no lava, solid ground
                if (there.getBlock() == Blocks.LAVA || there.getFluidState().isSource()) continue;
                if (belowThere.getBlock() == Blocks.LAVA) continue;
                if (!belowThere.isSolid()) continue;

                // Prefer: further from lava, closer to water, away from current pos
                double distFromPos = pos.distSqr(candidate);
                double lavaPenalty = DangerZone.isLavaNearbyAt(bot, candidate, 3) ? -200 : 0;
                double score = distFromPos + lavaPenalty;

                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return best;
    }

    // ---- Bridge Building ----

    private void tryBridgeOut(FakePlayer bot) {
        BlockPos pos = bot.blockPosition();
        var level = bot.level();
        var inv = bot.getInventory();

        // Find a throwaway block
        int blockSlot = -1;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() instanceof net.minecraft.world.item.BlockItem) {
                blockSlot = i;
                break;
            }
        }
        if (blockSlot < 0) return;

        // Try to place block in front to bridge
        Direction facing = Direction.fromYRot(bot.getYRot());
        BlockPos target = pos.relative(facing);
        BlockState there = level.getBlockState(target);
        BlockState belowThere = level.getBlockState(target.below());

        // If ahead is lava, place block below to bridge
        if (there.getBlock() == Blocks.LAVA || there.getFluidState().isSource()) {
            // Need to place a block — swap to building block
            var stack = inv.getItem(blockSlot);
            if (blockSlot >= 9) {
                var tmp = inv.getItem(0);
                inv.setItem(0, stack);
                inv.setItem(blockSlot, tmp);
                inv.selected = 0;
            } else {
                inv.selected = blockSlot;
            }
            // Place at feet to pillar up, or at target to bridge
            BlockPos placePos = belowThere.isSolid() || belowThere.getBlock() == Blocks.LAVA
                    ? pos.relative(facing, 0) // try placing at target
                    : pos; // pillar at feet
            if (level.getBlockState(placePos).isAir() || level.getBlockState(placePos).getBlock() == Blocks.LAVA) {
                level.setBlock(placePos,
                    stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
                        ? bi.getBlock().defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
                stack.shrink(1);
                // Jump up
                if (placePos.equals(pos) && bot.onGround()) {
                    bot.setDeltaMovement(bot.getDeltaMovement().x, 0.42, bot.getDeltaMovement().z);
                }
            }
        }
    }

    // ---- Water Bucket MLG ----

    private void tryWaterBucketMLG(FakePlayer bot) {
        var inv = bot.getInventory();
        // Find water bucket
        int bucketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).getItem() == Items.WATER_BUCKET) { bucketSlot = i; break; }
        }
        if (bucketSlot < 0) return; // no water bucket, can't MLG

        inv.selected = bucketSlot;
        // Look straight down for placement
        bot.setXRot(90f);
        bot.startUsingItem(InteractionHand.MAIN_HAND);
        bot.stopUsingItem(); // place water
    }

    @Override public boolean isActive() { return active; }
    @Override public void stop() {
        active = false; escapeTicks = 0;
        cooldownTicks = COOLDOWN;
    }
    @Override public String name() { return "Danger"; }
}
