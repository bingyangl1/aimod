package com.example.aimod.fakeplayer;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.ChatVisiblity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Fake player class extending ServerPlayer.
 * Simulates real player behavior, controlled by AI.
 */
public class FakePlayer extends ServerPlayer {

    private final FakePlayerManager manager;
    private volatile boolean active = true;

    public FakePlayer(ServerLevel level, GameProfile profile, FakePlayerManager manager) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        this.manager = manager;
        this.connection = new FakeNetHandler(this, level);
        this.setPos(level.getSharedSpawnPos().getX() + 0.5,
                level.getSharedSpawnPos().getY(),
                level.getSharedSpawnPos().getZ() + 0.5);
        this.getInventory().clearContent();
    }

    public FakePlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile, ClientInformation.createDefault());
        this.manager = null;
        this.connection = new FakeNetHandler(this, level);
        BlockPos spawnPos = level.getSharedSpawnPos();
        this.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
    }

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
    public void onItemPickup(ItemEntity itemEntity) {
        ItemStack stack = itemEntity.getItem();
        this.getInventory().add(stack);
        itemEntity.discard();
    }

    public boolean canDestroyBlock(BlockPos pos) {
        BlockState state = this.level().getBlockState(pos);
        return state.getDestroySpeed(this.level(), pos) >= 0;
    }

    public boolean canPlaceBlock(BlockPos pos, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
            return false;
        }
        BlockState currentState = this.level().getBlockState(pos);
        return currentState.isAir() || currentState.canBeReplaced();
    }

    @Nullable
    public FakePlayerManager getManager() {
        return manager;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

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

    public void moveTo(double x, double y, double z) {
        this.setPos(x, y, z);
    }

    public void moveToBlock(BlockPos pos) {
        this.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }

    public void jump() {
        if (this.onGround()) {
            this.setDeltaMovement(this.getDeltaMovement().add(0, 0.42, 0));
            this.hasImpulse = true;
        }
    }

    public void attackEntity(Entity target) {
        this.attack(target);
        this.swing(InteractionHand.MAIN_HAND);
    }

    public void useItem(InteractionHand hand) {
        this.swing(hand);
    }

    public void interactWithBlock(BlockPos pos, InteractionHand hand) {
        // Swing hand to simulate interaction
        this.swing(hand);
    }

    public void dropItem(ItemStack stack, boolean throwRandomly) {
        this.drop(stack, throwRandomly);
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

    public static GameProfile createDefaultProfile(String name) {
        UUID uuid = UUID.nameUUIDFromBytes(("FakePlayer:" + name).getBytes());
        return new GameProfile(uuid, name);
    }

    @Override
    public boolean isAlive() {
        return this.active && super.isAlive();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {
    }

    @Override
    public void giveExperiencePoints(int experience) {
    }

    @Override
    public void giveExperienceLevels(int levels) {
    }

    @Override
    public void die(net.minecraft.world.damagesource.DamageSource cause) {
        super.die(cause);
        if (manager != null) {
            manager.removeFakePlayer(this);
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.getCooldowns().tick();
    }

    @Override
    public void sendSystemMessage(net.minecraft.network.chat.Component component, boolean bypassHiddenChat) {
    }

    public boolean canInteractWith(BlockPos pos) {
        return this.canInteractWithBlock(pos, 6.0);
    }

    public boolean canReach(BlockPos pos, double distance) {
        return this.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= distance * distance;
    }
}