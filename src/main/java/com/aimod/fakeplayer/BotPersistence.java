package com.aimod.fakeplayer;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.*;
import java.util.*;

/**
 * Persistence manager for bot state. Saves and loads {@link BotInfo} records
 * as JSON files under {@code config/aimod/bots/}.
 *
 * <p>Inspired by SiliconeDolls' {@code FilesUtil.MapFile} pattern.
 */
public final class BotPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BOTS_DIR = "config/aimod/bots";
    private static final String AUTO_LOAD_FILE = "auto-load.json";
    private static final Type AUTO_LOAD_TYPE = new TypeToken<List<String>>() {}.getType();

    private final Path botsDir;

    public BotPersistence(Path serverDir) {
        this.botsDir = serverDir.resolve(BOTS_DIR);
        try { Files.createDirectories(botsDir); } catch (IOException ignored) {}
    }

    public BotPersistence(MinecraftServer server) {
        this(server.getServerDirectory());
    }

    // ---- CRUD ----

    public void save(BotInfo info) {
        Path file = botsDir.resolve(sanitize(info.name) + ".json");
        try {
            String json = GSON.toJson(info);
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            DevLog.info("BOT_SAVED", "name={}, file={}", info.name, file.getFileName());
        } catch (IOException e) {
            DevLog.error("BOT_SAVE_ERROR", "Failed to save bot " + info.name, e);
        }
    }

    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    public Optional<BotInfo> load(String name) {
        Path file = botsDir.resolve(sanitize(name) + ".json");
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file);
            BotInfo info = GSON.fromJson(json, BotInfo.class);
            DevLog.info("BOT_LOADED", "name={}, file={}", name, file.getFileName());
            return Optional.of(info);
        } catch (Exception e) {
            DevLog.error("BOT_LOAD_ERROR", "Failed to load bot " + name, e);
            return Optional.empty();
        }
    }

    public List<String> listAll() {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(botsDir, "*.json")) {
            for (Path path : stream) {
                String fileName = path.getFileName().toString();
                if (fileName.equals(AUTO_LOAD_FILE)) continue;
                names.add(fileName.replace(".json", ""));
            }
        } catch (IOException ignored) {}
        Collections.sort(names);
        return names;
    }

    public boolean delete(String name) {
        Path file = botsDir.resolve(sanitize(name) + ".json");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            DevLog.error("BOT_DELETE_ERROR", "Failed to delete bot " + name, e);
            return false;
        }
    }

    // ---- Auto-load (server restart persistence) ----

    public List<String> getAutoLoadList() {
        Path file = botsDir.resolve(AUTO_LOAD_FILE);
        if (!Files.exists(file)) return Collections.emptyList();
        try {
            String json = Files.readString(file);
            List<String> list = GSON.fromJson(json, AUTO_LOAD_TYPE);
            return list != null ? list : Collections.emptyList();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public void addToAutoLoad(String name) {
        List<String> list = new ArrayList<>(getAutoLoadList());
        if (!list.contains(name)) {
            list.add(name);
            writeAutoLoad(list);
        }
    }

    public void removeFromAutoLoad(String name) {
        List<String> list = new ArrayList<>(getAutoLoadList());
        if (list.remove(name)) {
            writeAutoLoad(list);
        }
    }

    private void writeAutoLoad(List<String> names) {
        Path file = botsDir.resolve(AUTO_LOAD_FILE);
        try {
            String json = GSON.toJson(names);
            Files.writeString(file, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            DevLog.error("AUTOLOAD_ERROR", "Failed to write auto-load list", e);
        }
    }

    // ---- helpers ----

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
