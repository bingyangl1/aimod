package com.aimod.fakeplayer;

import com.aimod.config.BotMode;
import com.aimod.config.ModConfig;
import com.aimod.entity.AIBotEntity;
import com.aimod.entity.ModEntities;
import com.aimod.util.DevLog;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages FakePlayer lifecycle with GameProfile persistence.
 * Profiles (name -> UUID) are saved to disk and reused across restarts.
 * Inspired by AI-Player's createFakePlayer persistence pattern.
 */
public class FakePlayerManager {

    private final MinecraftServer server;
    private final Map<UUID, FakePlayer> activePlayers = new ConcurrentHashMap<>();
    /** Dual mode: tracks the Mob wrapper alongside each FakePlayer. */
    private final Map<UUID, AIBotEntity> botEntities = new ConcurrentHashMap<>();
    private final BotProfileStore profileStore;
    private final BotPersistence persistence;

    public FakePlayerManager(MinecraftServer server) {
        this.server = server;
        this.profileStore = new BotProfileStore(server);
        this.persistence = new BotPersistence(server);
    }

    /**
     * Create and register a new FakePlayer with persistent identity.
     * If a bot with this name was created before, it reuses the same UUID.
     * Respects the configured BotMode (FP or DUAL).
     */
    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos, GameType gamemode, BotMode mode) {
        if (activePlayers.size() >= ModConfig.getMaxBots()) {
            DevLog.warn("FAKE_PLAYER_LIMIT", "Max fake players reached: {}", ModConfig.getMaxBots());
            return null;
        }

        // Check if a bot with this name already exists
        FakePlayer existing = getByName(name);
        if (existing != null) {
            DevLog.warn("FAKE_PLAYER_DUPLICATE", "name={} already exists", name);
            return existing;
        }

        // Get persistent UUID (or create new one)
        UUID persistentUUID = profileStore.getOrCreateUUID(name);

        FakePlayer player = FakePlayer.createAndRegister(server, level, name, pos, gamemode, null, persistentUUID);
        if (player != null) {
            activePlayers.put(player.getUUID(), player);

            if (mode == BotMode.DUAL) {
                spawnDualMob(player, level, pos);
            }

            DevLog.info("FAKE_PLAYER_CREATED", "name={}, uuid={}, mode={}",
                    name, player.getStringUUID(), mode.getKey());
        }
        return player;
    }

    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos, GameType gamemode) {
        return createFakePlayer(name, level, pos, gamemode, ModConfig.getBotMode());
    }

    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos) {
        return createFakePlayer(name, level, pos, GameType.SURVIVAL, ModConfig.getBotMode());
    }

    /**
     * In Dual mode, spawn an AIBotEntity (Mob) that wraps the FakePlayer.
     * The Mob syncs its position from the FakePlayer and provides
     * Mob-specific rendering and AI goals.
     */
    private void spawnDualMob(FakePlayer player, ServerLevel level, Vec3 pos) {
        AIBotEntity mob = new AIBotEntity(ModEntities.AI_BOT.get(), level);
        mob.setFakePlayer(player);
        mob.setPos(pos.x, pos.y, pos.z);
        mob.setHealth(player.getHealth());
        level.addFreshEntity(mob);
        botEntities.put(player.getUUID(), mob);
        DevLog.info("DUAL_MOB_SPAWNED", "name={}, mobUuid={}", player.getName().getString(), mob.getStringUUID());
    }

    /**
     * Remove and disconnect a FakePlayer.
     * In Dual mode, also removes the associated Mob.
     */
    public void removeFakePlayer(FakePlayer player) {
        if (player == null) return;
        activePlayers.remove(player.getUUID());
        // Dual mode: remove Mob wrapper
        AIBotEntity mob = botEntities.remove(player.getUUID());
        if (mob != null) mob.remove(Entity.RemovalReason.DISCARDED);
        player.kill();
        DevLog.info("FAKE_PLAYER_REMOVE", "name={}", player.getName().getString());
    }

    /**
     * Remove all FakePlayers (and their Mob wrappers in Dual mode).
     */
    public void removeAll() {
        for (AIBotEntity mob : botEntities.values()) {
            mob.remove(Entity.RemovalReason.DISCARDED);
        }
        botEntities.clear();
        for (FakePlayer player : activePlayers.values()) {
            player.kill();
        }
        activePlayers.clear();
        DevLog.info("FAKE_PLAYER_REMOVE_ALL", "All fake players removed");
    }

    @Nullable
    public FakePlayer getByUUID(UUID uuid) {
        return activePlayers.get(uuid);
    }

    @Nullable
    public FakePlayer getByName(String name) {
        return activePlayers.values().stream()
                .filter(p -> p.getName().getString().equals(name))
                .findFirst()
                .orElse(null);
    }

    @Nullable
    public FakePlayer getNearest(double x, double y, double z, double maxDistance) {
        FakePlayer nearest = null;
        double bestDist = maxDistance * maxDistance;
        for (FakePlayer p : activePlayers.values()) {
            if (!p.isAlive()) continue;
            double d = p.distanceToSqr(x, y, z);
            if (d < bestDist) {
                bestDist = d;
                nearest = p;
            }
        }
        return nearest;
    }

    public Collection<FakePlayer> getActivePlayers() {
        return activePlayers.values();
    }

    public int getActiveCount() {
        return activePlayers.size();
    }

    public boolean canCreateMore() {
        return activePlayers.size() < ModConfig.getMaxBots();
    }

    /**
     * Remove dead players from tracking.
     */
    public void cleanup() {
        activePlayers.entrySet().removeIf(e -> !e.getValue().isAlive());
    }

    // ---- Persistence (save/load) ----

    /**
     * Save a bot's complete state to disk.
     */
    public boolean saveBot(FakePlayer player, String desc) {
        BotInfo info = BotInfo.from(player, desc, profileStore.getOrCreateUUID(player.getName().getString()));
        persistence.save(info);
        persistence.addToAutoLoad(info.name); // auto-restore on next server start
        return true;
    }

    /**
     * Load and restore a bot from disk.
     */
    @Nullable
    public FakePlayer loadBot(String name) {
        var opt = persistence.load(name);
        if (opt.isEmpty()) return null;

        BotInfo info = opt.get();
        if (activePlayers.size() >= ModConfig.getMaxBots()) {
            DevLog.warn("BOT_LOAD_LIMIT", "Max fake players reached: {}", ModConfig.getMaxBots());
            return null;
        }

        ServerLevel level = server.overworld();
        if (info.dimType != null) {
            try {
                var dimKey = net.minecraft.resources.ResourceLocation.parse(info.dimType);
                var levelKey = net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, dimKey);
                ServerLevel dimLevel = server.getLevel(levelKey);
                if (dimLevel != null) level = dimLevel;
            } catch (Exception ignored) {}
        }

        Vec3 pos = new Vec3(info.pos.x, info.pos.y, info.pos.z);
        GameType gamemode = GameType.valueOf(info.gamemode);

        UUID persistentUUID = null;
        try { persistentUUID = UUID.fromString(info.uuid); } catch (Exception ignored) {}

        FakePlayer player = FakePlayer.createAndRegister(server, level, info.name, pos, gamemode, null, persistentUUID);
        if (player == null) return null;

        // Dual mode: spawn Mob wrapper
        if (ModConfig.getBotMode() == BotMode.DUAL) {
            spawnDualMob(player, level, pos);
        }

        // Restore inventory
        if (info.inventory != null) {
            for (BotInfo.SerializedItem si : info.inventory) {
                if (si.slot >= 0 && si.slot < player.getInventory().getContainerSize()) {
                    player.getInventory().setItem(si.slot, si.toStack());
                }
            }
        }

        // Restore pause state
        if (info.paused) {
            player.pauseExecution();
        }

        activePlayers.put(player.getUUID(), player);
        DevLog.info("BOT_RESTORED", "name={}, items={}", info.name,
                info.inventory != null ? info.inventory.size() : 0);
        return player;
    }

    /**
     * Delete a saved bot from disk.
     */
    public boolean deleteBot(String name) {
        return persistence.delete(name);
    }

    /**
     * List saved bot names.
     */
    public List<String> listSavedBots() {
        return persistence.listAll();
    }

    /**
     * Get the persistence manager.
     */
    public BotPersistence getPersistence() { return persistence; }

    /**
     * Get the profile store for external access.
     */
    public BotProfileStore getProfileStore() {
        return profileStore;
    }
}