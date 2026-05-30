package com.aimod.ai;

import com.aimod.ai.action.*;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists in-progress tasks to disk so they survive server restarts.
 *
 * <p>Storage: {@code config/aimod/tasks/<bot_uuid>.json}
 * Each file contains the task description, action JSON strings, current index, and owner name.
 * Actions are stored as raw JSON (same format as LLM responses) and restored via
 * {@link BotAIManager#convertCachedToActions(List, String)}.</p>
 */
public class TaskPersistence {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int CURRENT_VERSION = 2;

    /** Persisted task data — serialized to/from JSON. */
    public static class TaskData {
        public int version = 0;        // 0 = legacy (no version field), 2 = current
        public String botUuid;
        public String botName;
        public String command;
        public String ownerName;
        public String status;          // IN_PROGRESS, PENDING
        public int currentActionIndex;
        public List<String> actions;   // raw JSON action strings
        public long createdAt;
        public long savedAt;           // last save timestamp
    }

    private final Path tasksDir;

    public TaskPersistence(Path serverDir) {
        this.tasksDir = serverDir.resolve("config/aimod/tasks");
    }

    /** Save a bot's current task to disk. */
    public void save(FakePlayer bot) {
        if (!com.aimod.config.ModConfig.getPersistTasks()) return;

        Task task = bot.getCurrentTask();
        if (task == null || task.isCompleted()) return;

        TaskData data = new TaskData();
        data.version = CURRENT_VERSION;
        data.botUuid = bot.getStringUUID();
        data.botName = bot.getName().getString();
        data.command = task.getDescription();
        data.ownerName = bot.getAiManager().getFeedback().getOwnerName();
        data.status = task.getStatus().name();
        data.currentActionIndex = task.getCurrentActionIndex();
        data.actions = actionsToJson(task.getActions());
        data.createdAt = System.currentTimeMillis();
        data.savedAt = System.currentTimeMillis();

        try {
            Files.createDirectories(tasksDir);
            Path file = tasksDir.resolve(bot.getStringUUID() + ".json");
            Path tmpFile = tasksDir.resolve(bot.getStringUUID() + ".json.tmp");

            // Atomic write: write to temp file first, then rename
            Files.writeString(tmpFile, GSON.toJson(data),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            DevLog.info("TASK_PERSIST_SAVE", "bot={}, actions={}, idx={}, version={}",
                    data.botName, data.actions.size(), data.currentActionIndex, data.version);
        } catch (IOException e) {
            DevLog.warn("TASK_PERSIST_SAVE_FAIL", "bot={}, err={}", data.botName, e.getMessage());
            // Clean up temp file on failure
            try { Files.deleteIfExists(tasksDir.resolve(bot.getStringUUID() + ".json.tmp")); } catch (IOException ignored) {}
        }
    }

    /**
     * Restore a bot's persisted task from disk.
     * @return restored Task, or null if no persisted task or persistence disabled
     */
    public Task restore(FakePlayer bot) {
        if (!com.aimod.config.ModConfig.getPersistTasks()) return null;

        Path file = tasksDir.resolve(bot.getStringUUID() + ".json");
        if (!Files.exists(file)) return null;

        try {
            String json = Files.readString(file);
            if (json == null || json.isBlank()) {
                DevLog.warn("TASK_PERSIST_EMPTY_FILE", "bot={}", bot.getName().getString());
                delete(bot);
                return null;
            }

            TaskData data;
            try {
                data = GSON.fromJson(json, TaskData.class);
            } catch (com.google.gson.JsonSyntaxException e) {
                DevLog.warn("TASK_PERSIST_CORRUPTED", "bot={}, err={}", bot.getName().getString(), e.getMessage());
                backupAndDelete(file, bot.getStringUUID());
                return null;
            }

            if (data == null || data.actions == null || data.actions.isEmpty()) {
                DevLog.warn("TASK_PERSIST_INVALID_DATA", "bot={}", bot.getName().getString());
                delete(bot);
                return null;
            }

            // Version migration: legacy files (version=0) are still valid
            if (data.version > CURRENT_VERSION) {
                DevLog.warn("TASK_PERSIST_VERSION_MISMATCH", "bot={}, fileVersion={}, currentVersion={}",
                        data.botName, data.version, CURRENT_VERSION);
                delete(bot);
                return null;
            }

            // Skip if task was already completed/failed before shutdown
            if ("COMPLETED".equals(data.status) || "FAILED".equals(data.status)) {
                delete(bot);
                return null;
            }

            // Validate action index
            if (data.currentActionIndex < 0 || data.currentActionIndex >= data.actions.size()) {
                DevLog.warn("TASK_PERSIST_INVALID_INDEX", "bot={}, idx={}, actions={}",
                        data.botName, data.currentActionIndex, data.actions.size());
                data.currentActionIndex = 0; // reset to start
            }

            List<Action> actions = bot.getAiManager().convertCachedToActions(data.actions, data.ownerName);
            if (actions.isEmpty()) {
                DevLog.warn("TASK_PERSIST_NO_ACTIONS", "bot={}", data.botName);
                delete(bot);
                return null;
            }

            Task task = new Task(data.command != null ? data.command : "restored task");
            task.setActions(actions);
            task.setStatus(Task.TaskStatus.IN_PROGRESS);

            // Advance to the persisted action index
            for (int i = 0; i < data.currentActionIndex && i < actions.size(); i++) {
                task.advanceToNextAction();
            }

            DevLog.info("TASK_PERSIST_RESTORE", "bot={}, command={}, actions={}, idx={}, version={}",
                    data.botName, DevLog.compact(data.command), actions.size(), data.currentActionIndex, data.version);
            return task;
        } catch (Exception e) {
            DevLog.warn("TASK_PERSIST_RESTORE_FAIL", "bot={}, err={}",
                    bot.getName().getString(), e.getMessage());
            backupAndDelete(file, bot.getStringUUID());
            return null;
        }
    }

    /** Delete persisted task file for a bot. */
    public void delete(FakePlayer bot) {
        try {
            Path file = tasksDir.resolve(bot.getStringUUID() + ".json");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            DevLog.warn("TASK_PERSIST_DELETE_FAIL", "err={}", e.getMessage());
        }
    }

    /** Check if a persisted task exists for a bot. */
    public boolean exists(FakePlayer bot) {
        return Files.exists(tasksDir.resolve(bot.getStringUUID() + ".json"));
    }

    /**
     * Backup a corrupted file and delete the original.
     * Preserves evidence for debugging while preventing repeated failures.
     */
    private void backupAndDelete(Path file, String botUuid) {
        try {
            Path backupDir = tasksDir.resolve("backup");
            Files.createDirectories(backupDir);
            Path backup = backupDir.resolve(botUuid + "." + System.currentTimeMillis() + ".json.bak");
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
            DevLog.info("TASK_PERSIST_BACKUP", "file={} -> {}", file.getFileName(), backup.getFileName());
        } catch (IOException e) {
            // If backup fails, just delete
            DevLog.warn("TASK_PERSIST_BACKUP_FAIL", "err={}", e.getMessage());
            delete(file);
        }
    }

    /** Delete a specific file. */
    private void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            DevLog.warn("TASK_PERSIST_DELETE_FAIL", "err={}", e.getMessage());
        }
    }

    /** Convert Action objects to JSON strings for persistence. */
    private List<String> actionsToJson(List<Action> actions) {
        List<String> result = new ArrayList<>();
        for (Action a : actions) {
            String json = actionToJson(a);
            if (json != null) result.add(json);
        }
        return result;
    }

    /** Convert a single Action to its JSON representation. */
    private String actionToJson(Action a) {
        com.google.gson.JsonObject obj = new com.google.gson.JsonObject();

        if (a instanceof MoveToAction m) {
            obj.addProperty("type", "move_to");
            obj.addProperty("x", m.getTargetPos().getX());
            obj.addProperty("y", m.getTargetPos().getY());
            obj.addProperty("z", m.getTargetPos().getZ());
        } else if (a instanceof BreakBlockAction b) {
            obj.addProperty("type", "break_block");
            obj.addProperty("x", b.getTargetPos().getX());
            obj.addProperty("y", b.getTargetPos().getY());
            obj.addProperty("z", b.getTargetPos().getZ());
        } else if (a instanceof PlaceBlockAction p) {
            obj.addProperty("type", "place_block");
            obj.addProperty("x", p.getTargetPos().getX());
            obj.addProperty("y", p.getTargetPos().getY());
            obj.addProperty("z", p.getTargetPos().getZ());
            // BlockItem registry name
            net.minecraft.resources.ResourceLocation key =
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(p.getBlockItem());
            obj.addProperty("block_id", key.toString());
        } else if (a instanceof CraftAction c) {
            obj.addProperty("type", "craft");
            obj.addProperty("item_id", c.getItemId());
            obj.addProperty("count", c.getCount());
        } else if (a instanceof GatherResourceAction g) {
            obj.addProperty("type", "gather");
            obj.addProperty("resource_type", g.getResourceType().name());
            obj.addProperty("count", g.getCount());
        } else if (a instanceof MineBlockAction m) {
            obj.addProperty("type", "mine");
            obj.addProperty("block_id", m.getBlockId());
            obj.addProperty("count", m.getCount());
        } else if (a instanceof FollowAction f) {
            obj.addProperty("type", "follow");
            obj.addProperty("player", f.getPlayerName());
        } else if (a instanceof GiveItemAction g) {
            obj.addProperty("type", "give_item");
            obj.addProperty("item_id", g.getItemId());
            obj.addProperty("count", g.getRequestedCount());
            obj.addProperty("player", g.getTargetPlayerName());
        } else if (a instanceof EquipItemAction e) {
            obj.addProperty("type", "equip");
            obj.addProperty("item_id", e.getItemId());
        } else if (a instanceof AttackAction at) {
            obj.addProperty("type", "attack");
            obj.addProperty("target", at.getTargetType());
        } else if (a instanceof SayAction s) {
            obj.addProperty("type", "say");
            obj.addProperty("message", s.getMessageText());
        } else if (a instanceof WaitAction w) {
            obj.addProperty("type", "wait");
            obj.addProperty("ticks", w.getTotalTicks());
        } else if (a instanceof InteractBlockAction ib) {
            obj.addProperty("type", "interact");
            obj.addProperty("interact_type", ib.getInteractType().name());
        } else {
            DevLog.warn("TASK_PERSIST_UNKNOWN_ACTION", "class={}", a.getClass().getSimpleName());
            return null;
        }

        return GSON.toJson(obj);
    }
}
