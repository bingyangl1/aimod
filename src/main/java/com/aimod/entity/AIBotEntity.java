package com.aimod.entity;

import com.aimod.ai.BotAIManager;
import com.aimod.ai.InventoryUtils;
import com.aimod.ai.Task;
import com.aimod.client.BotStatusScreen;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;
import java.util.List;

/**
 * AI Bot world entity — extends Mob for world presence and visibility.
 *
 * <h3>Architecture</h3>
 * <ul>
 *   <li>AIBotEntity is the <b>world-visible</b> entity (Mob). Handles: mob AI goals,
 *       rendering, item pickup, position syncing to FakePlayer.</li>
 *   <li>FakePlayer (ServerPlayer) is the <b>logic owner</b>. Handles: ALL AI logic,
 *       task management, movement, pathfinding, inventory, interactions.</li>
 * </ul>
 *
 * <p>All task-related operations (assign, cancel, pause, resume) are delegated
 * to the FakePlayer. This class holds no duplicate state.</p>
 */
public class AIBotEntity extends Mob {

    /** Lazily-initialized FakePlayer for player-like operations. */
    @Nullable
    private FakePlayer fakePlayer;

    public AIBotEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ATTACK_DAMAGE, 1.0)
                .add(Attributes.ARMOR, 0.0);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.level().isClientSide && player instanceof ServerPlayer sp) {
            FakePlayer fp = getFakePlayer();
            if (fp != null) {
                BotStatusScreen.open(sp, fp);
                DevLog.info("BOT_INTERACT", "player={}, hand={}", player.getName().getString(), hand);
                return InteractionResult.CONSUME;
            }
            DevLog.warn("BOT_INTERACT_NO_FP", "bot has no FakePlayer");
        }
        return InteractionResult.CONSUME;
    }

    // ── FakePlayer lifecycle ────────────────────────────────────────────

    @Nullable
    public FakePlayer getFakePlayer() {
        if (fakePlayer == null && this.level() instanceof ServerLevel serverLevel) {
            fakePlayer = FakePlayer.createAndRegister(serverLevel.getServer(), serverLevel, "[AI Bot]",
                    new net.minecraft.world.phys.Vec3(this.getX(), this.getY(), this.getZ()));
            fakePlayer.moveTo(this.getX(), this.getY(), this.getZ());
            DevLog.info("FAKE_PLAYER_LAZY_INIT", "bot={}, fakePlayer={}",
                    this.getStringUUID(), fakePlayer.getStringUUID());
        }
        return fakePlayer;
    }

    public boolean hasFakePlayer() {
        return fakePlayer != null;
    }

    private void syncPositionToFakePlayer() {
        if (fakePlayer != null) {
            fakePlayer.moveTo(this.getX(), this.getY(), this.getZ(), this.getYRot(), this.getXRot());
        }
    }

    // ── Task management (delegated to FakePlayer) ───────────────────────

    /**
     * Assign a natural language task. Delegates to FakePlayer.
     */
    public void assignTask(String naturalLanguageCommand) {
        assignTask(naturalLanguageCommand, null);
    }

    /**
     * Assign a natural language task. Delegates to FakePlayer.
     */
    public void assignTask(String naturalLanguageCommand, @Nullable Player owner) {
        FakePlayer fp = getFakePlayer();
        if (fp == null) {
            DevLog.warn("BOT_NO_FAKEPLAYER", "bot={}", this.getStringUUID());
            return;
        }
        fp.assignTask(naturalLanguageCommand, owner);
    }

    /**
     * Cancel the current task. Delegates to FakePlayer.
     */
    public void cancelTask() {
        if (fakePlayer != null) {
            fakePlayer.cancelTask();
        }
    }

    // ── Tick ────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            syncPositionToFakePlayer();
            pickupNearbyItems();
        }
    }

    private void pickupNearbyItems() {
        if (this.level() instanceof ServerLevel) {
            AABB box = this.getBoundingBox().inflate(3.0);
            List<ItemEntity> items = this.level().getEntitiesOfClass(ItemEntity.class, box,
                    item -> item.isAlive() && !item.hasPickUpDelay());
            for (ItemEntity itemEntity : items) {
                ItemStack stack = itemEntity.getItem();
                FakePlayer fp = getFakePlayer();
                if (fp != null && InventoryUtils.addItem(fp, stack)) {
                    itemEntity.discard();
                    DevLog.info("PICKUP_ITEM", "item={}, count={}", stack.getHoverName().getString(), stack.getCount());
                }
            }
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (fakePlayer != null) {
            DevLog.info("FAKE_PLAYER_CLEANUP", "bot={}", this.getStringUUID());
            fakePlayer = null;
        }
        super.remove(reason);
    }

    // ── Getters ─────────────────────────────────────────────────────────

    @Nullable
    public Task getCurrentTask() {
        return fakePlayer != null ? fakePlayer.getCurrentTask() : null;
    }

    public boolean isPaused() {
        return fakePlayer != null && fakePlayer.isPaused();
    }

    public ItemStack getItemInHand(InteractionHand hand) {
        return fakePlayer != null ? fakePlayer.getItemInHand(hand) : ItemStack.EMPTY;
    }

    public void setItemInHand(InteractionHand hand, ItemStack stack) {
        if (fakePlayer != null) fakePlayer.setItemInHand(hand, stack);
    }

    public boolean isCreative() { return false; }
    public boolean isSpectator() { return false; }
}
