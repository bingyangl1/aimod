package com.example.aimod.entity;

import com.example.aimod.ai.BotAIManager;
import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.Task;
import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.fakeplayer.FakePlayerManager;
import com.example.aimod.util.DevLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
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

public class AIBotEntity extends PathfinderMob {
    private BotAIManager aiManager;
    private Task currentTask;
    private final SimpleContainer inventory;
    private volatile boolean parsingTask = false;

    // FakePlayer 集成
    @Nullable
    private FakePlayer fakePlayer;
    @Nullable
    private FakePlayerManager fakePlayerManager;

    public AIBotEntity(EntityType<? extends PathfinderMob> entityType, Level level) {
        super(entityType, level);
        this.aiManager = new BotAIManager(this);
        this.currentTask = null;
        this.inventory = new SimpleContainer(36);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
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

    /**
     * 初始化 FakePlayer（仅在服务端调用）
     */
    public void initFakePlayer() {
        if (this.level() instanceof ServerLevel serverLevel) {
            this.fakePlayerManager = new FakePlayerManager(serverLevel);
            this.fakePlayer = fakePlayerManager.createFakePlayer(this.getName().getString() + "_fake");
            if (this.fakePlayer != null) {
                // 将 FakePlayer 设置到与 AIBotEntity 相同的位置
                this.fakePlayer.moveTo(this.getX(), this.getY(), this.getZ());
                DevLog.info("FAKE_PLAYER_INIT", "bot={}, fakePlayer={}",
                        this.getStringUUID(), this.fakePlayer.getStringUUID());
            }
        }
    }

    /**
     * 获取关联的 FakePlayer
     */
    @Nullable
    public FakePlayer getFakePlayer() {
        return fakePlayer;
    }

    /**
     * 检查是否有 FakePlayer
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
            fakePlayer.lookAt(this.getX() + this.getLookAngle().x, this.getY() + this.getLookAngle().y, this.getZ() + this.getLookAngle().z);
        }
    }

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

        // 设置任务所有者
        aiManager.getFeedback().setOwner(owner);
        
        // Run LLM call off the server thread to avoid freezing
        String botName = this.getName().getString();
        String ownerName = owner != null ? owner.getName().getString() : null;
        DevLog.info("BOT_ASSIGN_START", "bot={}, owner={}, command={}",
                this.getStringUUID(), ownerName, DevLog.compact(naturalLanguageCommand));

        // 通知任务开始
        aiManager.getFeedback().reportTaskStart(naturalLanguageCommand);

        Thread llmThread = new Thread(() -> {
            try {
                DevLog.info("BOT_PARSE_THREAD_START", "bot={}, thread={}", this.getStringUUID(), Thread.currentThread().getName());
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
                DevLog.info("BOT_PARSE_THREAD_DONE", "bot={}, thread={}", this.getStringUUID(), Thread.currentThread().getName());
            }
        }, "AIMod-LLM-" + botName);
        llmThread.setDaemon(true);
        llmThread.start();
    }

    @Override
    public void tick() {
        super.tick();

        // 同步位置到 FakePlayer
        syncPositionToFakePlayer();

        // 更新任务
        if (this.currentTask != null && !this.currentTask.isCompleted()) {
            aiManager.updateTask(this.currentTask);
        }

        // 自动拾取附近的掉落物到机器人背包
        pickupNearbyItems();
    }

    /**
     * 自动拾取附近的掉落物实体到机器人背包。
     * 解决 destroyBlock 生成的掉落物无法进入背包的问题。
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

    @Override
    public void remove(RemovalReason reason) {
        // 清理 FakePlayer
        if (fakePlayer != null) {
            if (fakePlayerManager != null) {
                fakePlayerManager.destroyFakePlayer(fakePlayer);
            } else {
                fakePlayer.discard();
            }
            fakePlayer = null;
        }
        super.remove(reason);
    }

    public BotAIManager getAiManager() {
        return aiManager;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public ItemStack getItemInHand(net.minecraft.world.InteractionHand hand) {
        return inventory.getItem(0);
    }

    public void setItemInHand(net.minecraft.world.InteractionHand hand, ItemStack stack) {
        inventory.setItem(0, stack);
    }

    public boolean isCreative() {
        return false;
    }

    public boolean isSpectator() {
        return false;
    }

    /**
     * 获取假人管理器
     */
    @Nullable
    public FakePlayerManager getFakePlayerManager() {
        return fakePlayerManager;
    }

    /**
     * 假人是否存活
     */
    public boolean isFakePlayerAlive() {
        return fakePlayer != null && fakePlayer.isAlive();
    }
}