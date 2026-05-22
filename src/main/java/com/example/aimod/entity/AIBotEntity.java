package com.example.aimod.entity;

import com.example.aimod.ai.BotAIManager;
import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.Task;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ClientInformation;
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

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * AI Bot entity - extends Mob, encapsulates FakePlayer (ServerPlayer).
 * The FakePlayer is lazily initialized and NOT added to the world;
 * it serves as an internal tool for player-like operations (block breaking,
 * combat, crafting context, etc.).
 */
public class AIBotEntity extends Mob {
    private BotAIManager aiManager;
    private Task currentTask;
    private final SimpleContainer inventory;
    private volatile boolean parsingTask = false;

    /** Lazily-initialized FakePlayer for player-like operations. */
    @Nullable
    private FakePlayer fakePlayer;

    public AIBotEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
        this.aiManager = new BotAIManager(this);
        this.currentTask = null;
        this.inventory = new SimpleContainer(36);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.3D)
                .add(Attributes.FOLLOW_RANGE, 32.0D)
                .add(Attributes.ATTACK_DAMAGE, 1.0D)
                .add(Attributes.ARMOR, 0.0D);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(2, new RandomLookAroundGoal(this));
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        return InteractionResult.PASS;
    }

    // ── FakePlayer lifecycle ────────────────────────────────────────────

    /**
     * 获取关联的 FakePlayer（懒加载，仅服务端）。
     * FakePlayer 不会被添加到世界中，仅作为内部工具使用。
     */
    @Nullable
    public FakePlayer getFakePlayer() {
        if (fakePlayer == null && this.level() instanceof ServerLevel serverLevel) {
            String name = this.getName().getString() + "_impl";
            UUID uuid = UUID.nameUUIDFromBytes(("AIMod:Bot:" + name).getBytes());
            GameProfile profile = new GameProfile(uuid, name);
            fakePlayer = new FakePlayer(serverLevel, profile);
            fakePlayer.moveTo(this.getX(), this.getY(), this.getZ());
            DevLog.info("FAKE_PLAYER_LAZY_INIT", "bot={}, fakePlayer={}",
                    this.getStringUUID(), fakePlayer.getStringUUID());
        }
        return fakePlayer;
    }

    /**
     * 检查 FakePlayer 是否可用
     */
    public boolean hasFakePlayer() {
        return fakePlayer != null && fakePlayer.isAlive();
    }

    /**
     * 同步位置到 FakePlayer
     */
    private void syncPositionToFakePlayer() {
        if (fakePlayer != null) {
            fakePlayer.moveTo(this.getX(), this.getY(), this.getZ());
            fakePlayer.lookAt(
                    this.getX() + this.getLookAngle().x,
                    this.getY() + this.getLookAngle().y,
                    this.getZ() + this.getLookAngle().z);
        }
    }

    // ── Task management ─────────────────────────────────────────────────

    public void assignTask(String naturalLanguageCommand) {
        assignTask(naturalLanguageCommand, null);
    }

    public void assignTask(String naturalLanguageCommand, Player owner) {
        if (parsingTask) {
            DevLog.warn("BOT_ASSIGN_SKIP", "bot={}, reason=already_parsing, command={}",
                    this.getStringUUID(), DevLog.compact(naturalLanguageCommand));
            return;
        }
        parsingTask = true;

        aiManager.getFeedback().setOwner(owner);

        String botName = this.getName().getString();
        String ownerName = owner != null ? owner.getName().getString() : null;
        DevLog.info("BOT_ASSIGN_START", "bot={}, owner={}, command={}",
                this.getStringUUID(), ownerName, DevLog.compact(naturalLanguageCommand));

        aiManager.getFeedback().reportTaskStart(naturalLanguageCommand);

        Thread llmThread = new Thread(() -> {
            try {
                DevLog.info("BOT_PARSE_THREAD_START", "bot={}, thread={}",
                        this.getStringUUID(), Thread.currentThread().getName());
                Task task = aiManager.parseCommand(naturalLanguageCommand, ownerName);
                if (task != null) {
                    if (this.level().getServer() != null) {
                        this.level().getServer().execute(() -> {
                            DevLog.info("BOT_TASK_ACCEPTED", "bot={}, status={}, actionCount={}, command={}",
                                    this.getStringUUID(), task.getStatus(), task.getActionCount(),
                                    DevLog.compact(naturalLanguageCommand));
                            this.currentTask = task;
                            aiManager.executeTask(this.currentTask);
                        });
                    }
                } else {
                    DevLog.warn("BOT_TASK_NULL", "bot={}, command={}",
                            this.getStringUUID(), DevLog.compact(naturalLanguageCommand));
                }
            } catch (Exception e) {
                DevLog.error("BOT_PARSE_THREAD_EXCEPTION", "failed to parse task", e);
            } finally {
                parsingTask = false;
                DevLog.info("BOT_PARSE_THREAD_DONE", "bot={}, thread={}",
                        this.getStringUUID(), Thread.currentThread().getName());
            }
        }, "AIMod-LLM-" + botName);
        llmThread.setDaemon(true);
        llmThread.start();
    }

    // ── Tick ────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        syncPositionToFakePlayer();

        if (this.currentTask != null && !this.currentTask.isCompleted()) {
            aiManager.updateTask(this.currentTask);
        }

        pickupNearbyItems();
    }

    /**
     * 自动拾取附近的掉落物到机器人背包。
     */
    private void pickupNearbyItems() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        AABB box = this.getBoundingBox().inflate(3.0);
        List<ItemEntity> items = serverLevel.getEntitiesOfClass(
                ItemEntity.class, box, e -> !e.getItem().isEmpty() && e.isAlive());
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            int originalCount = stack.getCount();
            if (InventoryUtils.addItem(this, stack)) {
                itemEntity.discard();
                DevLog.info("ITEM_PICKUP", "item={}, count={}",
                        stack.getDescriptionId(), originalCount);
            }
        }
    }

    // ── Cleanup ─────────────────────────────────────────────────────────

    @Override
    public void remove(RemovalReason reason) {
        if (fakePlayer != null) {
            fakePlayer.discard();
            fakePlayer = null;
        }
        super.remove(reason);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public BotAIManager getAiManager() {
        return aiManager;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public ItemStack getItemInHand(InteractionHand hand) {
        return inventory.getItem(0);
    }

    public void setItemInHand(InteractionHand hand, ItemStack stack) {
        inventory.setItem(0, stack);
    }

    public boolean isCreative() {
        return false;
    }

    public boolean isSpectator() {
        return false;
    }
}
