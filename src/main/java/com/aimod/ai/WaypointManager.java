package com.aimod.ai;

import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Waypoint system — save and load named positions.
 *
 * <p>Inspired by Baritone's Waypoint system. Allows saving important
 * locations (base, mines, farms, etc.) for later navigation.</p>
 *
 * <p>Persistence: saved to config/aimod/waypoints.json</p>
 */
public class WaypointManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path WAYPOINTS_FILE = Path.of("config", "aimod", "waypoints.json");

    private final Map<String, Waypoint> waypoints = new ConcurrentHashMap<>();

    public WaypointManager() {
        load();
    }

    /** Save a waypoint. */
    public void save(String name, BlockPos pos, String category) {
        waypoints.put(name.toLowerCase(), new Waypoint(name, pos, category, System.currentTimeMillis()));
        save();
        DevLog.info("WAYPOINT_SAVE", "name={}, pos={}, category={}", name, pos.toShortString(), category);
    }

    /** Get a waypoint by name. */
    public Waypoint get(String name) {
        return waypoints.get(name.toLowerCase());
    }

    /** Get all waypoints. */
    public Collection<Waypoint> getAll() {
        return waypoints.values();
    }

    /** Get waypoints by category. */
    public List<Waypoint> getByCategory(String category) {
        return waypoints.values().stream()
                .filter(w -> w.category().equals(category))
                .toList();
    }

    /** Delete a waypoint. */
    public boolean delete(String name) {
        Waypoint removed = waypoints.remove(name.toLowerCase());
        if (removed != null) {
            save();
            DevLog.info("WAYPOINT_DELETE", "name={}", name);
            return true;
        }
        return false;
    }

    /** Find nearest waypoint of a category. */
    public Waypoint findNearest(BlockPos pos, String category) {
        return waypoints.values().stream()
                .filter(w -> category == null || w.category().equals(category))
                .min(Comparator.comparingDouble(w -> w.pos().distSqr(pos)))
                .orElse(null);
    }

    /** Save waypoints to disk. */
    private void save() {
        try {
            Files.createDirectories(WAYPOINTS_FILE.getParent());
            JsonObject json = new JsonObject();
            for (var entry : waypoints.entrySet()) {
                JsonObject wp = new JsonObject();
                wp.addProperty("name", entry.getValue().name());
                wp.addProperty("x", entry.getValue().pos().getX());
                wp.addProperty("y", entry.getValue().pos().getY());
                wp.addProperty("z", entry.getValue().pos().getZ());
                wp.addProperty("category", entry.getValue().category());
                wp.addProperty("timestamp", entry.getValue().timestamp());
                json.add(entry.getKey(), wp);
            }
            Files.writeString(WAYPOINTS_FILE, GSON.toJson(json), StandardCharsets.UTF_8);
        } catch (IOException e) {
            DevLog.warn("WAYPOINT_SAVE_FAIL", "err={}", e.getMessage());
        }
    }

    /** Load waypoints from disk. */
    private void load() {
        if (!Files.exists(WAYPOINTS_FILE)) return;
        try {
            String content = Files.readString(WAYPOINTS_FILE, StandardCharsets.UTF_8);
            JsonObject json = GSON.fromJson(content, JsonObject.class);
            for (String key : json.keySet()) {
                JsonObject wp = json.getAsJsonObject(key);
                String name = wp.has("name") ? wp.get("name").getAsString() : key;
                int x = wp.get("x").getAsInt();
                int y = wp.get("y").getAsInt();
                int z = wp.get("z").getAsInt();
                String category = wp.has("category") ? wp.get("category").getAsString() : "general";
                long timestamp = wp.has("timestamp") ? wp.get("timestamp").getAsLong() : 0;
                waypoints.put(key, new Waypoint(name, new BlockPos(x, y, z), category, timestamp));
            }
            DevLog.info("WAYPOINT_LOAD", "count={}", waypoints.size());
        } catch (Exception e) {
            DevLog.warn("WAYPOINT_LOAD_FAIL", "err={}", e.getMessage());
        }
    }

    /** Waypoint record. */
    public record Waypoint(String name, BlockPos pos, String category, long timestamp) {}
}
