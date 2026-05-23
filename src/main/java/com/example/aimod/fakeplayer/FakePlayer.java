package com.example.aimod.fakeplayer;

import com.example.aimod.ai.BotAIManager;
import com.example.aimod.ai.InventoryUtils;
import com.example.aimod.ai.Task;
import com.example.aimod.util.DevLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * AI Bot - a FakePlayer that extends ServerPlayer.
 * Registered with the server's player list, giving it full player capabilities.
 * AI-controlled via BotAIManager.
 *
 * Based on the SiliconeDolls pattern: extend ServerPlayer, register via placeNewPlayer.
 */
public class FakePlayer extends ServerPlayer {

    // ── AI State ────────────────────────────────────────────────────────
    private BotAIManager aiManager;
    private Task currentTask;
    private volatile boolean parsingTask = false;
    private volatile boolean paused = false;

    // ── Construction ────────────────────────────────────────────────────

    private FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation clientInfo) {
        super(server, level, profile, clientInfo);
        this.connection = new FakePlayerNetHandler(server, new FakeClientConnection(PacketFlow.SERVERBOUND), this,
                new CommonListenerCookie(profile, 0, clientInfo, false));
        this.aiManager = new BotAIManager(this);
    }

    /**
     * Create and register a FakePlayer with the server.
     * The player will appear in the tab list and be tracked like a real player.
     *
     * @param server  the Minecraft server
     * @param level   the server level to spawn in
     * @param name    bot name
     * @param pos     spawn position
     * @param gamemode initial game mode
     * @param callback called after the player is fully registered
     * @return the created FakePlayer, or null if creation failed
     */
    @Nullable
    public static FakePlayer createAndRegister(
            MinecraftServer server, ServerLevel level, String name,
            Vec3 pos, GameType gamemode, Consumer<FakePlayer> callback
    ) {
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID("AI:" + name), name);

        FakePlayer instance = new FakePlayer(server, level, profile, ClientInformation.createDefault());

        // Set spawn position
        instance.fixStartingPosition = () -> instance.moveTo(pos.x, pos.y, pos.z, 0, 0);

        // Register with server player list - THIS is the key step
        //noinspection deprecation
        server.getPlayerList().placeNewPlayer(
                new FakeClientConnection(PacketFlow.SERVERBOUND),
                instance,
                new CommonListenerCookie(profile, 0, instance.clientInformation(), false)
        );

        // Post-registration setup
        instance.teleportTo(level, pos.x, pos.y, pos.z, 0, 0);
        instance.setHealth(20.0F);
        instance.unsetRemoved();

        AttributeInstance stepAttr = instance.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr != null) stepAttr.setBaseValue(0.6F);

        instance.gameMode.changeGameModeForPlayer(gamemode);

        // Broadcast to clients
        server.getPlayerList().broadcastAll(
                new ClientboundRotateHeadPacket(instance, (byte) (instance.yHeadRot * 256 / 360)),
                level.dimension()
        );
        server.getPlayerList().broadcastAll(
                new ClientboundPlayerInfoUpdatePacket(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, instance
                )
        );

        if (callback != null) callback.accept(instance);

        DevLog.info("FAKE_PLAYER_CREATE", "name={}, uuid={}, pos={}, gamemode={}",
                name, instance.getStringUUID(), pos, gamemode);

        return instance;
    }

    /** Convenience overload with default survival mode */
    @Nullable
    public static FakePlayer createAndRegister(MinecraftServer server, ServerLevel level, String name, Vec3 pos) {
        return createAndRegister(server, level, name, pos, GameType.SURVIVAL, null);
    }

    // Placeholder for fixStartingPosition (called by super.tick via placeNewPlayer)
    private Runnable fixStartingPosition = () -> {};

    // ── ServerPlayer Overrides ──────────────────────────────────────────

    @Override
    public boolean isFakePlayer() {
        return true;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Override
    public @NotNull String getIpAddress() {
        return "127.0.0.1";
    }

    @Override
    public boolean allowsListing() {
        return false;
    }

    @Override
    public void tick() {
        MinecraftServer srv = this.getServer();
        if (srv == null) return;

        // Periodic position reset (same pattern as SiliconeDolls)
        if (srv.getTickCount() % 10 == 0) {
            this.connection.resetPosition();
            this.serverLevel().getChunkSource().move(this);
        }

        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) {
            // FakePlayer may NPE in some vanilla paths
        }

        // AI tick
        if (!paused && this.currentTask != null && !this.currentTask.isCompleted()) {
            aiManager.updateTask(this.currentTask);
        }

        // Auto-pickup nearby items
        pickupNearbyItems();
    }

    @Override
    public void die(@NotNull DamageSource cause) {
        // Reset state on death
        this.setExperiencePoints(0);
        this.setExperienceLevels(0);
        this.setDeltaMovement(Vec3.ZERO);
        this.setRemainingFireTicks(0);
        this.fallDistance = 0;
        this.removeAllEffects();
        super.die(cause);
        setHealth(20);
        this.foodData = new FoodData();
        DevLog.info("FAKE_PLAYER_DIE", "name={}", this.getName().getString());
    }

    @Override
    public Entity changeDimension(@NotNull DimensionTransition transition) {
        super.changeDimension(transition);
        if (wonGame) {
            var p = new net.minecraft.network.protocol.game.ServerboundClientCommandPacket(
                    net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action.PERFORM_RESPAWN);
            connection.handleClientCommand(p);
        }
        if (connection.player.isChangingDimension()) {
            connection.player.hasChangedDimension();
        }
        return connection.player;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, @NotNull BlockState state, @NotNull net.minecraft.core.BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    @Override
    public void onEquipItem(@NotNull EquipmentSlot slot, @NotNull ItemStack oldItem, @NotNull ItemStack newItem) {
        if (!this.isUsingItem()) super.onEquipItem(slot, oldItem, newItem);
    }

    // ── AI Task Management ──────────────────────────────────────────────

    public void assignTask(String naturalLanguageCommand) {
        assignTask(naturalLanguageCommand, null);
    }

    public void assignTask(String naturalLanguageCommand, @Nullable Player owner) {
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

    // ── Convenience Methods (from old FakePlayer) ───────────────────────

    public void lookAt(double x, double y, double z) {
        double dx = x - this.getX();
        double dy = y - this.getY();
        double dz = z - this.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
        float pitch = (float) -(Math.atan2(dy, distance) * 180.0 / Math.PI);
        this.setYRot(yaw);
        this.setXRot(pitch);
    }

    public void lookAtEntity(Entity entity) {
        this.lookAt(entity.getX(), entity.getY() + entity.getBbHeight() / 2, entity.getZ());
    }

    public boolean canDestroyBlock(net.minecraft.core.BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        return state.getDestroySpeed(this.level(), pos) >= 0;
    }

    public void attackEntity(Entity target) {
        this.attack(target);
        this.swing(InteractionHand.MAIN_HAND);
    }

    public void useItem(InteractionHand hand) {
        this.swing(hand);
    }

    public void interactWithBlock(net.minecraft.core.BlockPos pos, InteractionHand hand) {
        this.swing(hand);
    }

    public void dropItemInDirection(ItemStack stack, Vec3 direction) {
        ItemEntity itemEntity = new ItemEntity(this.level(),
                this.getX() + direction.x,
                this.getY() + 0.5,
                this.getZ() + direction.z,
                stack);
        itemEntity.setDeltaMovement(direction.scale(0.3));
        this.level().addFreshEntity(itemEntity);
    }

    public boolean canReach(net.minecraft.core.BlockPos pos, double distance) {
        return this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distance * distance;
    }

    // ── Kill / Removal ──────────────────────────────────────────────────

    public void kill() {
        kill(Component.literal("Killed"));
    }

    public void kill(@NotNull Component reason) {
        if (this.getVehicle() instanceof Player) stopRiding();
        for (Entity passenger : this.getIndirectPassengers()) {
            if (passenger instanceof Player) passenger.stopRiding();
        }
        //noinspection deprecation
        this.server.tell(new TickTask(this.server.getTickCount(), () -> this.connection.onDisconnect(new DisconnectionDetails(reason))));
    }

    // ── Auto Pickup ─────────────────────────────────────────────────────

    private void pickupNearbyItems() {
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        var box = this.getBoundingBox().inflate(3.0);
        var items = serverLevel.getEntitiesOfClass(
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

    // ── Accessors ───────────────────────────────────────────────────────

    public BotAIManager getAiManager() {
        return aiManager;
    }

    public Task getCurrentTask() {
        return currentTask;
    }

    /**
     * Assign a pre-built task directly, bypassing the LLM.
     */
    public void assignDirectTask(Task task, @Nullable Player owner) {
        if (parsingTask) {
            DevLog.warn("BOT_ALREADY_PARSING", "bot={}, task={}", this.getStringUUID(), task.getDescription());
            return;
        }
        if (this.currentTask != null && !this.currentTask.isCompleted()) {
                        this.currentTask.setStatus(Task.TaskStatus.FAILED);
        }
        this.currentTask = null;
        this.paused = false;
        if (owner != null) {
            aiManager.getFeedback().setOwner(owner);
        }
        DevLog.info("BOT_DIRECT_TASK", "bot={}, task={}, actions={}",
                this.getStringUUID(), task.getDescription(), task.getActionCount());
        aiManager.getFeedback().reportTaskStart(task.getDescription());
        this.currentTask = task;
        aiManager.executeTask(this.currentTask);
    }

    /**
     * Cancel the current task, stopping all bot activity.
     */
    public void cancelTask() {
        if (this.currentTask != null) {
                        String desc = this.currentTask.getDescription();
            this.currentTask.setStatus(Task.TaskStatus.FAILED);
            aiManager.getFeedback().sendToOwnerTranslatable("feedback.task.cancelled", desc);
            DevLog.info("BOT_TASK_CANCELLED", "bot={}, task={}", this.getStringUUID(), desc);
            this.currentTask = null;
        }
        this.paused = false;
    }

    /**
     * Pause task execution (bot stays in place but stops acting).
     */
    public void pauseExecution() {
        if (!this.paused) {
            this.paused = true;
                        aiManager.getFeedback().sendToOwnerTranslatable("feedback.bot_paused");
            DevLog.info("BOT_PAUSED", "bot={}", this.getStringUUID());
        }
    }

    /**
     * Resume task execution after a pause.
     */
    public void resumeExecution() {
        if (this.paused) {
            this.paused = false;
            aiManager.getFeedback().sendToOwnerTranslatable("feedback.bot_resumed");
            DevLog.info("BOT_RESUMED", "bot={}", this.getStringUUID());
        }
    }

    /** Check if the bot is paused. */
    public boolean isPaused() { return paused; }

    public boolean hasActiveTask() {
        return currentTask != null && !currentTask.isCompleted();
    }
}