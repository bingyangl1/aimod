package com.aimod.entity;

import com.aimod.ai.Task;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

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

    /** Whether this Mob is linked to a real FakePlayer (Dual mode) vs lazy placeholder. */
    private boolean dualLinked;

    public AIBotEntity(EntityType<? extends Mob> entityType, Level level) {
        super(entityType, level);
    }

    /**
     * Link to an existing FakePlayer (Dual mode).
     * After this call, getFakePlayer() returns the linked instance without lazy-init.
     */
    public void setFakePlayer(FakePlayer fp) {
        this.fakePlayer = fp;
        this.dualLinked = true;
    }

    public boolean isDualLinked() { return dualLinked; }

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

    // ── FakePlayer lifecycle ────────────────────────────────────────────

    @Nullable
    public FakePlayer getFakePlayer() {
        if (fakePlayer == null && !dualLinked && this.level() instanceof ServerLevel serverLevel) {
            // Legacy lazy-init — only for server operators who /summon the Mob directly
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

    /**
     * Sync position: FakePlayer is authoritative; AIBotEntity follows.
     * Previously this pushed AIBotEntity position TO FakePlayer, which
     * silently broke any FakePlayer movement.
     */
    private void syncPositionToFakePlayer() {
        if (fakePlayer != null) {
            // FakePlayer is the movement authority — follow its position
            this.moveTo(fakePlayer.getX(), fakePlayer.getY(), fakePlayer.getZ(),
                    fakePlayer.getYRot(), fakePlayer.getXRot());
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
            // Control name tag visibility based on config
            this.setCustomNameVisible(com.aimod.config.ModConfig.getShowTaskAboveHead());

            if (fakePlayer != null) {
                syncPositionToFakePlayer();
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

    // ── Name Tag Display ────────────────────────────────────────────────

    @Override
    public Component getDisplayName() {
        if (!com.aimod.config.ModConfig.getShowTaskAboveHead()) {
            return super.getDisplayName();
        }

        MutableComponent name = Component.literal(this.getName().getString())
                .withStyle(ChatFormatting.GREEN);

        FakePlayer fp = this.fakePlayer;
        if (fp == null) return name;

        var sm = fp.getAiManager().getStateMachine();
        var state = sm.getCurrent();

        if (state == com.aimod.ai.llm.BotAIStateMachine.State.IDLE) {
            return name;
        }

        MutableComponent statusLine = Component.empty();
        statusLine.append(formatState(state));

        String desc = sm.getTaskDescription();
        if (desc != null && !desc.isBlank()) {
            String truncated = desc.length() > 20 ? desc.substring(0, 20) + "…" : desc;
            statusLine.append(Component.literal(" " + truncated).withStyle(ChatFormatting.YELLOW));
        }

        if (sm.getActionsTotal() > 0) {
            statusLine.append(Component.literal(
                    String.format(" (%d/%d)", sm.getActionsDone(), sm.getActionsTotal()))
                    .withStyle(ChatFormatting.GRAY));
        }

        return name.append(Component.literal("\n")).append(statusLine);
    }

    private static MutableComponent formatState(com.aimod.ai.llm.BotAIStateMachine.State state) {
        return switch (state) {
            case PLANNING  -> Component.literal("[规划中]").withStyle(ChatFormatting.AQUA);
            case EXECUTING -> Component.literal("[执行中]").withStyle(ChatFormatting.WHITE);
            case PAUSED    -> Component.literal("[已暂停]").withStyle(ChatFormatting.GOLD);
            case REPLAN    -> Component.literal("[重新规划]").withStyle(ChatFormatting.LIGHT_PURPLE);
            case COMPLETED -> Component.literal("[完成]").withStyle(ChatFormatting.GREEN);
            case FAILED    -> Component.literal("[失败]").withStyle(ChatFormatting.RED);
            default        -> Component.empty();
        };
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
