package com.example.aimod.command;

import com.example.aimod.ai.Task;
import com.example.aimod.ai.action.*;
import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates Task objects with specific Actions directly, bypassing the LLM.
 * Inspired by Baritone command patterns.
 */
public class DirectCommandHandler {

    public static Task createGotoTask(int x, int y, int z) {
        Task task = new Task("Go to " + x + " " + y + " " + z);
        List<Action> actions = new ArrayList<>();
        actions.add(new MoveToAction(new BlockPos(x, y, z), 1.0));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createMineTask(String blockId, int count) {
        String fullId = blockId.contains(":") ? blockId : "minecraft:" + blockId;
        Task task = new Task("Mine " + count + " " + fullId);
        List<Action> actions = new ArrayList<>();
        actions.add(new MineBlockAction(fullId, count));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createFollowTask(String playerName) {
        Task task = new Task("Follow " + playerName);
        List<Action> actions = new ArrayList<>();
        actions.add(new FollowAction(playerName));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createGatherTask(String resourceType, int count) {
        Task task = new Task("Gather " + count + " " + resourceType);
        List<Action> actions = new ArrayList<>();
        try {
            GatherResourceAction.ResourceType type =
                    GatherResourceAction.ResourceType.valueOf(resourceType.toUpperCase());
            actions.add(new GatherResourceAction(type, count));
        } catch (IllegalArgumentException e) {
            DevLog.warn("INVALID_RESOURCE_TYPE", "type={}", resourceType);
            task.setStatus(Task.TaskStatus.FAILED);
            return task;
        }
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createCraftTask(String itemId, int count) {
        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Task task = new Task("Craft " + count + " " + fullId);
        List<Action> actions = new ArrayList<>();
        actions.add(new CraftAction(fullId, count));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createSayTask(String message) {
        Task task = new Task("Say: " + message);
        List<Action> actions = new ArrayList<>();
        actions.add(new SayAction(message));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createGiveTask(String itemId, int count, String playerName) {
        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Task task = new Task("Give " + count + " " + fullId + " to " + playerName);
        List<Action> actions = new ArrayList<>();
        actions.add(new GiveItemAction(fullId, count, playerName));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }

    public static Task createEquipTask(String itemId) {
        String fullId = itemId.contains(":") ? itemId : "minecraft:" + itemId;
        Task task = new Task("Equip " + fullId);
        List<Action> actions = new ArrayList<>();
        actions.add(new EquipItemAction(fullId, EquipmentSlot.MAINHAND));
        task.setActions(actions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        return task;
    }
}
