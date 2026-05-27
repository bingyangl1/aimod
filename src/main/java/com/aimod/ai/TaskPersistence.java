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

    /** Persisted task data — serialized to/from JSON. */
    public static class TaskData {
        public String botUuid;
        public String botName;
        public String command;
        public String ownerName;
        public String status;          // IN_PROGRESS, PENDING
        public int currentActionIndex;
        public List<String> actions;   // raw JSON action strings
        public long createdAt;
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
        data.botUuid = bot.getStringUUID();
        data.botName = bot.getName().getString();
        data.command = task.getDescription();
        data.ownerName = bot.getAiManager().getFeedback().getOwnerName();
        data.status = task.getStatus().name();
        data.currentActionIndex = task.getCurrentActionIndex();
        data.actions = actionsToJson(task.getActions());
        data.createdAt = System.currentTimeMillis();

        try {
            Files.createDirectories(tasksDir);
            Path file = tasksDir.resolve(bot.getStringUUID() + ".json");
            Files.writeString(file, GSON.toJson(data),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            DevLog.info("TASK_PERSIST_SAVE", "bot={}, actions={}, idx={}",
                    data.botName, data.actions.size(), data.currentActionIndex);
        } catch (IOException e) {
            DevLog.warn("TASK_PERSIST_SAVE_FAIL", "bot={}, err={}", data.botName, e.getMessage());
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
            TaskData data = GSON.fromJson(json, TaskData.class);
            if (data == null || data.actions == null || data.actions.isEmpty()) return null;

            // Skip if task was already completed/failed before shutdown
            if ("COMPLETED".equals(data.status) || "FAILED".equals(data.status)) {
                delete(bot);
                return null;
            }

            List<Action> actions = bot.getAiManager().convertCachedToActions(data.actions, data.ownerName);
            if (actions.isEmpty()) {
                DevLog.warn("TASK_PERSIST_NO_ACTIONS", "bot={}", data.botName);
                delete(bot);
                return null;
            }

            Task task = new Task(data.command);
            task.setActions(actions);
            task.setStatus(Task.TaskStatus.IN_PROGRESS);

            // Advance to the persisted action index
            for (int i = 0; i < data.currentActionIndex && i < actions.size(); i++) {
                task.advanceToNextAction();
            }

            DevLog.info("TASK_PERSIST_RESTORE", "bot={}, command={}, actions={}, idx={}",
                    data.botName, DevLog.compact(data.command), actions.size(), data.currentActionIndex);
            return task;
        } catch (Exception e) {
            DevLog.warn("TASK_PERSIST_RESTORE_FAIL", "bot={}, err={}",
                    bot.getName().getString(), e.getMessage());
            delete(bot);
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
