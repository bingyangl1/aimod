package com.example.aimod.entity;

import com.example.aimod.ai.BotAIManager;
import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.Task;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
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
import java.util.UUID;

/**
 * AI Bot entity - extends Mob, encapsulates FakePlayer (ServerPlayer).
 * The FakePlayer is lazily initialized and NOT added to the world;
 * it serves as an internal tool for player-like operations.
 */
public class AIBotEntity extends Mob {
    private volatile BotAIManager aiManager;
    private Task currentTask;
    private final SimpleContainer inventory;
    private volatile boolean parsingTask = false;

    /** Lazily-initialized FakePlayer for player-like operations. */
    @Nullable
    private FakePlayer fakePlayer;

    public AIBotEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
        this.currentTask = null;
        this.currentTask = null;
        this.inventory = new SimpleContainer(36);
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
        if (!this.level().isClientSide) {
            DevLog.info("BOT_INTERACT", "player={}, hand={}", player.getName().getString(), hand);
        }
        return InteractionResult.SUCCESS;
    }

    // ---------- FakePlayer lifecycle ----------

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

    // ---------- Task management ----------

    public void assignTask(String naturalLanguageCommand) {
        assignTask(naturalLanguageCommand, null);
    }

    public void assignTask(String naturalLanguageCommand, @Nullable Player owner) {
        if (parsingTask) {
            DevLog.warn("TASK_ALREADY_PARSING", "bot={}", this.getStringUUID());
            return;
        }

        // Re-init FakePlayer if needed
        if (!hasFakePlayer()) {
            getFakePlayer();
        }

        parsingTask = true;
        String ownerName = owner != null ? owner.getName().getString() : "console";
        DevLog.info("TASK_ASSIGN", "bot={}, owner={}, cmd={}", this.getStringUUID(), ownerName, naturalLanguageCommand);

        String botId = this.getStringUUID();
        try {
            Task task = aiManager.parseCommand(naturalLanguageCommand, ownerName);
            if (task != null) {
                this.currentTask = task;
                aiManager.executeTask(task);
                DevLog.info("TASK_STARTED", "bot={}, task={}", botId, task.getDescription());
            } else {
                DevLog.warn("TASK_FAILED", "bot={}, error=parse returned null", botId);
            }
        } catch (Exception e) {
            DevLog.warn("TASK_FAILED", "bot={}, error={}", botId, e.getMessage());
        }
        parsingTask = false;
    }

    // ---------- Tick ----------

    @Override
    public void tick() {
        super.tick();

        if (!this.level().isClientSide) {
            syncPositionToFakePlayer();

            if (currentTask != null && !parsingTask) {
                aiManager.updateTask(currentTask);
                if (currentTask.isCompleted()) {
                    DevLog.info("TASK_COMPLETE", "bot={}, task={}", this.getStringUUID(), currentTask.getDescription());
                    currentTask = null;
                }
            }

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

    // ---------- Getters ----------

    public BotAIManager getAiManager() {
        if (aiManager == null) {
            FakePlayer fp = getFakePlayer();
            if (fp != null) {
                aiManager = new BotAIManager(fp);
            }
        }
        return aiManager;
    }
    public Task getCurrentTask() { return currentTask; }
    public SimpleContainer getInventory() { return inventory; }

    public ItemStack getItemInHand(InteractionHand hand) {
        if (fakePlayer != null) {
            return fakePlayer.getItemInHand(hand);
        }
        return ItemStack.EMPTY;
    }

    public void setItemInHand(InteractionHand hand, ItemStack stack) {
        if (fakePlayer != null) {
            fakePlayer.setItemInHand(hand, stack);
        }
    }

    public boolean isCreative() { return false; }

    public boolean isSpectator() { return false; }
}
