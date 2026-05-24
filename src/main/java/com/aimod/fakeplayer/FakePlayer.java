package com.aimod.fakeplayer;

import com.aimod.ai.BotAIManager;
import com.aimod.ai.InventoryUtils;
import com.aimod.ai.Task;
import com.aimod.ai.chain.ChainManager;
import com.aimod.ai.chain.DangerChain;
import com.aimod.ai.chain.DefenseChain;
import com.aimod.ai.chain.FoodChain;
import com.aimod.ai.chain.UnstuckChain;
import com.aimod.ai.movement.MovementController;
import com.aimod.ai.tool.AutoFish;
import com.aimod.ai.tool.AutoReplenish;
import com.aimod.ai.tool.AutoReplaceTool;
import com.aimod.util.DevLog;
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
import net.minecraft.world.entity.MoverType;
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
 *
 * Architecture: FakePlayer owns ALL AI logic — task management, movement, and execution.
 * AIBotEntity (the world-visible Mob) delegates to this FakePlayer.
 */
public class FakePlayer extends ServerPlayer {

    // ── AI State ────────────────────────────────────────────────────────
    private BotAIManager aiManager;
    private Task currentTask;
    private volatile boolean parsingTask = false;
    private volatile boolean paused = false;

    // ── Movement ────────────────────────────────────────────────────────
    private final MovementController movementController;

    // ── Behavior Chains ──────────────────────────────────────────────────
    private final ChainManager chainManager;

    // ── Construction ────────────────────────────────────────────────────

    public Runnable fixStartingPosition = () -> {};

    private FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile, ClientInformation clientInfo) {
        super(server, level, profile, clientInfo);
        // NetHandler is set by PlayerListMixin during placeNewPlayer()
        this.aiManager = new BotAIManager(this);
        this.movementController = new MovementController(this);
        this.chainManager = new ChainManager();
        this.chainManager.addChain(new DangerChain());
        this.chainManager.addChain(new DefenseChain());
        this.chainManager.addChain(new FoodChain());
        this.chainManager.addChain(new UnstuckChain());
    }

    /**
     * Create and register a FakePlayer with the server.
     * The player will appear in the tab list and be tracked like a real player.
     *
     * <p>Skin: attempts to use the profile from server cache (which may include
     * skin data from Mojang). Falls back to offline UUID if no cached profile.</p>
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
            Vec3 pos, GameType gamemode, Consumer<FakePlayer> callback,
            @Nullable UUID persistentUUID
    ) {
        // Try server profile cache first (may include Mojang skin data)
        GameProfile profile;
        net.minecraft.server.players.GameProfileCache profileCache = server.getProfileCache();
        if (profileCache != null) {
            profile = profileCache.get(name).orElse(null);
        } else {
            profile = null;
        }

        UUID botUUID;
        if (profile != null) {
            botUUID = profile.getId();
        } else {
            botUUID = (persistentUUID != null) ? persistentUUID : UUIDUtil.createOfflinePlayerUUID("AI:" + name);
            profile = new GameProfile(botUUID, name);
        }

        FakePlayer instance = new FakePlayer(server, level, profile, ClientInformation.createDefault());

        // Set spawn position
        instance.fixStartingPosition = () -> instance.moveTo(pos.x, pos.y, pos.z, 0, 0);

        // Register with server player list — FakePlayerNetHandler set in constructor
        //noinspection deprecation
        server.getPlayerList().placeNewPlayer(
                new FakeClientConnection(PacketFlow.SERVERBOUND),
                instance,
                new CommonListenerCookie(profile, 0, instance.clientInformation(), false)
        );

        // Run fixStartingPosition after placeNewPlayer loads the player
        instance.fixStartingPosition.run();

        // Post-registration setup
        instance.teleportTo(level, pos.x, pos.y, pos.z, 0, 0);
        instance.setHealth(20.0F);
        instance.setInvulnerable(true);
        instance.unsetRemoved();

        AttributeInstance stepAttr = instance.getAttribute(Attributes.STEP_HEIGHT);
        if (stepAttr != null) stepAttr.setBaseValue(1.0F);

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
        return createAndRegister(server, level, name, pos, GameType.SURVIVAL, null, null);
    }

    @Nullable
    public static FakePlayer createAndRegister(MinecraftServer server, ServerLevel level, String name, Vec3 pos, GameType gamemode, Consumer<FakePlayer> callback) {
        return createAndRegister(server, level, name, pos, gamemode, callback, null);
    }

    // ── ServerPlayer Overrides ──────────────────────────────────────────

    @Override
    public boolean isFakePlayer() { return true; }

    @Override
    public boolean isSpectator() { return false; }

    @Override
    public boolean isCreative() { return false; }

    @Override
    public @NotNull String getIpAddress() { return "127.0.0.1"; }

    @Override
    public boolean allowsListing() { return false; }

    @Override
    public void tick() {
        MinecraftServer srv = this.getServer();
        if (srv == null) return;

        try {
            super.tick();
            this.doTick();
        } catch (NullPointerException ignored) {
            // FakePlayer may NPE in some vanilla paths
        }

        // Tick the movement controller (handles async pathfinding delivery + movement execution)
        movementController.tick();

        // Behavior chains (survival: danger > defense > food > unstuck)
        boolean preempted = chainManager.tick(this);

        // AI tick — skip if survival chain is preempting
        if (!preempted && !paused && this.currentTask != null && !this.currentTask.isCompleted()) {
            aiManager.updateTask(this.currentTask);
        }

        // Periodic position sync (after AI tick so movement isn't overridden)
        if (srv.getTickCount() % 20 == 0) {
            this.connection.resetPosition();
            this.serverLevel().getChunkSource().move(this);
        }

        // Auto-pickup nearby items
        pickupNearbyItems();

        // Auto-tool maintenance
        if (com.aimod.config.ModConfig.getAutoReplenish()) AutoReplenish.tick(this);
        if (com.aimod.config.ModConfig.getAutoReplaceTool()) AutoReplaceTool.tick(this);
        if (com.aimod.config.ModConfig.getAutoFish()) AutoFish.of(this).tick(this);
    }

    /**
     * Override travel to prevent vanilla movement when AI is active.
     * When AI controls the bot, only gravity is applied here;
     * actual movement is handled by MovementController.
     */
    @Override
    public void travel(net.minecraft.world.phys.Vec3 travelVector) {
        if (currentTask != null && !currentTask.isCompleted() && !paused) {
            if (!onGround()) {
                double gravY = getDeltaMovement().y - 0.08;
                setDeltaMovement(0, gravY * 0.98, 0);
            } else {
                setDeltaMovement(0, 0, 0);
            }
            move(MoverType.SELF, getDeltaMovement());
            return;
        }
        super.travel(travelVector);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) { return false; }

    @Override
    public void die(@NotNull DamageSource cause) {
        String botName = this.getName().getString();
        DevLog.info("FAKE_PLAYER_DIE", "name={}, cause={}", botName, cause.getMsgId());

        this.setExperiencePoints(0);
        this.setExperienceLevels(0);
        this.setDeltaMovement(Vec3.ZERO);
        this.setRemainingFireTicks(0);
        this.fallDistance = 0;
        this.removeAllEffects();
        super.die(cause);
        setHealth(20);
        this.foodData = new FoodData();

        MinecraftServer srv = this.getServer();
        if (srv != null) {
            srv.tell(new TickTask(srv.getTickCount() + 40, () -> {
                DevLog.info("FAKE_PLAYER_RESPAWN", "name={}", botName);
                this.setHealth(20.0F);
                this.foodData = new FoodData();
                this.removeAllEffects();
                this.setDeltaMovement(Vec3.ZERO);
                this.fallDistance = 0;

                ServerLevel spawnLevel = srv.overworld();
                net.minecraft.core.BlockPos spawnPos = spawnLevel.getSharedSpawnPos();
                if (spawnPos != null) {
                    this.teleportTo(spawnLevel, spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5, 0, 0);
                }
                DevLog.info("FAKE_PLAYER_RESPAWNED", "name={}, pos={}", botName, this.position());
            }));
        }
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
                            aiManager.getStateMachine().startExecuting();
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
        movementController.stop();
        if (owner != null) {
            aiManager.getFeedback().setOwner(owner);
        }
        DevLog.info("BOT_DIRECT_TASK", "bot={}, task={}, actions={}",
                this.getStringUUID(), task.getDescription(), task.getActionCount());
        aiManager.getFeedback().reportTaskStart(task.getDescription());
        this.currentTask = task;
        aiManager.getStateMachine().startExecuting();
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
        movementController.stop();
    }

    public void pauseExecution() {
        if (!this.paused) {
            this.paused = true;
            aiManager.getFeedback().sendToOwnerTranslatable("feedback.bot_paused");
            DevLog.info("BOT_PAUSED", "bot={}", this.getStringUUID());
        }
    }

    public void resumeExecution() {
        if (this.paused) {
            this.paused = false;
            aiManager.getFeedback().sendToOwnerTranslatable("feedback.bot_resumed");
            DevLog.info("BOT_RESUMED", "bot={}", this.getStringUUID());
        }
    }

    // ── Convenience Methods ─────────────────────────────────────────────

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

    public void useItem(InteractionHand hand) { this.swing(hand); }

    public void interactWithBlock(net.minecraft.core.BlockPos pos, InteractionHand hand) { this.swing(hand); }

    public void dropItemInDirection(ItemStack stack, Vec3 direction) {
        ItemEntity itemEntity = new ItemEntity(this.level(),
                this.getX() + direction.x, this.getY() + 0.5, this.getZ() + direction.z, stack);
        itemEntity.setDeltaMovement(direction.scale(0.3));
        this.level().addFreshEntity(itemEntity);
    }

    public boolean canReach(net.minecraft.core.BlockPos pos, double distance) {
        return this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distance * distance;
    }

    // ── Kill / Removal ──────────────────────────────────────────────────

    public void kill() { kill(Component.literal("Killed")); }

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
        if (this.level().isClientSide || !(this.level() instanceof ServerLevel serverLevel)) return;
        var box = this.getBoundingBox().inflate(3.0);
        var items = serverLevel.getEntitiesOfClass(ItemEntity.class, box, e -> !e.getItem().isEmpty() && e.isAlive());
        for (ItemEntity itemEntity : items) {
            ItemStack stack = itemEntity.getItem();
            int originalCount = stack.getCount();
            if (InventoryUtils.addItem(this, stack)) {
                itemEntity.discard();
                DevLog.info("ITEM_PICKUP", "item={}, count={}", stack.getDescriptionId(), originalCount);
            }
        }
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public BotAIManager getAiManager() { return aiManager; }
    public Task getCurrentTask() { return currentTask; }
    public void setCurrentTask(Task task) { this.currentTask = task; }
    public boolean isPaused() { return paused; }
    public boolean hasActiveTask() { return currentTask != null && !currentTask.isCompleted(); }

    /** Get the centralized movement controller. */
    public MovementController getMovementController() { return movementController; }

    /** Get the behavior chain manager. */
    public ChainManager getChainManager() { return chainManager; }
}
