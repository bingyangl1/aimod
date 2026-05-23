package com.aimod.fakeplayer;

import com.aimod.util.DevLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages FakePlayer lifecycle with GameProfile persistence.
 * Profiles (name -> UUID) are saved to disk and reused across restarts.
 * Inspired by AI-Player's createFakePlayer persistence pattern.
 */
public class FakePlayerManager {

    private static final int MAX_FAKE_PLAYERS = 10;

    private final MinecraftServer server;
    private final Map<UUID, FakePlayer> activePlayers = new ConcurrentHashMap<>();
    private final BotProfileStore profileStore;

    public FakePlayerManager(MinecraftServer server) {
        this.server = server;
        this.profileStore = new BotProfileStore(server);
    }

    /**
     * Create and register a new FakePlayer with persistent identity.
     * If a bot with this name was created before, it reuses the same UUID.
     */
    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos, GameType gamemode) {
        if (activePlayers.size() >= MAX_FAKE_PLAYERS) {
            DevLog.warn("FAKE_PLAYER_LIMIT", "Max fake players reached: {}", MAX_FAKE_PLAYERS);
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
            DevLog.info("FAKE_PLAYER_CREATED", "name={}, uuid={}, persistent=true",
                    name, player.getStringUUID());
        }
        return player;
    }

    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos) {
        return createFakePlayer(name, level, pos, GameType.SURVIVAL);
    }

    /**
     * Remove and disconnect a FakePlayer.
     */
    public void removeFakePlayer(FakePlayer player) {
        if (player == null) return;
        activePlayers.remove(player.getUUID());
        player.kill();
        DevLog.info("FAKE_PLAYER_REMOVE", "name={}", player.getName().getString());
    }

    /**
     * Remove all FakePlayers.
     */
    public void removeAll() {
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
        return activePlayers.size() < MAX_FAKE_PLAYERS;
    }

    /**
     * Remove dead players from tracking.
     */
    public void cleanup() {
        activePlayers.entrySet().removeIf(e -> !e.getValue().isAlive());
    }

    /**
     * Get the profile store for external access.
     */
    public BotProfileStore getProfileStore() {
        return profileStore;
    }
}