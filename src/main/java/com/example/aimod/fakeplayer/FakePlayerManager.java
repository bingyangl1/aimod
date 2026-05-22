package com.example.aimod.fakeplayer;

import com.example.aimod.util.DevLog;
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
 * Manages FakePlayer lifecycle.
 * Players are registered with the server and tracked here.
 */
public class FakePlayerManager {

    private static final int MAX_FAKE_PLAYERS = 10;

    private final MinecraftServer server;
    private final Map<UUID, FakePlayer> activePlayers = new ConcurrentHashMap<>();

    public FakePlayerManager(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Create and register a new FakePlayer.
     */
    @Nullable
    public FakePlayer createFakePlayer(String name, ServerLevel level, Vec3 pos, GameType gamemode) {
        if (activePlayers.size() >= MAX_FAKE_PLAYERS) {
            DevLog.warn("FAKE_PLAYER_LIMIT", "Max fake players reached: {}", MAX_FAKE_PLAYERS);
            return null;
        }

        FakePlayer player = FakePlayer.createAndRegister(server, level, name, pos, gamemode, null);
        if (player != null) {
            activePlayers.put(player.getUUID(), player);
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
}