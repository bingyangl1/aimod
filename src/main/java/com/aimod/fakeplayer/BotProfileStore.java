package com.aimod.fakeplayer;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists bot GameProfiles (name -> UUID) across server restarts.
 * Inspired by AI-Player's createFakePlayer pattern.
 *
 * Stores profiles in config/aimod/bots.json:
 * {
 *   "Steve": "uuid-string",
 *   "Alex": "uuid-string"
 * }
 */
public final class BotProfileStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>(){}.getType();

    private final Map<String, String> profiles = new ConcurrentHashMap<>();
    private final Path savePath;

    public BotProfileStore(MinecraftServer server) {
        this.savePath = server.getServerDirectory().resolve("config/aimod/bots.json");
        load();
    }

    /**
     * Get or create a UUID for the given bot name.
     * If the name already has a saved UUID, return it.
     * Otherwise, generate a new UUID and save it.
     */
    public UUID getOrCreateUUID(String name) {
        String existing = profiles.get(name);
        if (existing != null) {
            try {
                UUID uuid = UUID.fromString(existing);
                DevLog.info("BOT_PROFILE_REUSE", "name={}, uuid={}", name, uuid);
                return uuid;
            } catch (IllegalArgumentException e) {
                DevLog.warn("BOT_PROFILE_INVALID_UUID", "name={}, stored={}", name, existing);
            }
        }

        // Generate deterministic UUID from name (same as FakePlayer offline UUID)
        UUID uuid = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("AI:" + name);
        profiles.put(name, uuid.toString());
        save();
        DevLog.info("BOT_PROFILE_NEW", "name={}, uuid={}", name, uuid);
        return uuid;
    }

    /**
     * Check if a bot name already has a saved profile.
     */
    public boolean hasProfile(String name) {
        return profiles.containsKey(name);
    }

    /**
     * Remove a bot's saved profile.
     */
    public void removeProfile(String name) {
        profiles.remove(name);
        save();
        DevLog.info("BOT_PROFILE_REMOVE", "name={}", name);
    }

    /**
     * Get all saved bot names.
     */
    public Map<String, String> getAllProfiles() {
        return Map.copyOf(profiles);
    }

    /**
     * Load profiles from disk.
     */
    private void load() {
        try {
            if (Files.exists(savePath)) {
                String json = Files.readString(savePath, StandardCharsets.UTF_8);
                Map<String, String> loaded = GSON.fromJson(json, MAP_TYPE);
                if (loaded != null) {
                    profiles.putAll(loaded);
                    DevLog.info("BOT_PROFILE_LOAD", "count={}", profiles.size());
                }
            }
        } catch (Exception e) {
            DevLog.warn("BOT_PROFILE_LOAD_FAIL", "error={}", e.getMessage());
        }
    }

    /**
     * Save profiles to disk.
     */
    public void save() {
        try {
            Files.createDirectories(savePath.getParent());
            String json = GSON.toJson(profiles, MAP_TYPE);
            Files.writeString(savePath, json, StandardCharsets.UTF_8);
            DevLog.info("BOT_PROFILE_SAVE", "count={}", profiles.size());
        } catch (Exception e) {
            DevLog.warn("BOT_PROFILE_SAVE_FAIL", "error={}", e.getMessage());
        }
    }
}