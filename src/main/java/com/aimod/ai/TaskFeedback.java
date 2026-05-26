package com.aimod.ai;

import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 任务反馈系统，向玩家报告任务执行状态。
 */
public class TaskFeedback {

    private final net.minecraft.world.entity.Entity bot;
    @Nullable
    private UUID ownerUUID;
    @Nullable
    private String ownerName;

    public TaskFeedback(net.minecraft.world.entity.Entity bot) {
        this.bot = bot;
    }

    /**
     * 设置任务所有者
     */
    public void setOwner(@Nullable Player owner) {
        if (owner != null) {
            this.ownerUUID = owner.getUUID();
            this.ownerName = owner.getName().getString();
        }
    }

    /** Build prefix string like §e[jocker → @nightfall]§r */
    private String prefix() {
        String botName = bot.getName().getString();
        String owner = ownerName != null ? ownerName : "?";
        return "§e[" + botName + " → @" + owner + "]§r ";
    }

    /**
     * 向任务所有者发送消息
     */
    public void sendToOwner(String message) {
        if (ownerUUID == null) return;

        ServerLevel level = (ServerLevel) bot.level();
        Player owner = level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(prefix() + message));
            DevLog.info("FEEDBACK_SENT", "to={}, message={}", ownerName, DevLog.compact(message));
        }
    }

    /**
     * Send a translatable message to the task owner.
     */
    public void sendToOwnerTranslatable(String key, Object... args) {
        if (ownerUUID == null) return;

        ServerLevel level = (ServerLevel) bot.level();
        Player owner = level.getServer().getPlayerList().getPlayer(ownerUUID);
        if (owner != null) {
            owner.sendSystemMessage(Component.literal(prefix()).append(Component.translatable(key, args)));
            DevLog.info("FEEDBACK_SENT", "to={}, key={}", ownerName, key);
        }
    }

    /**
     * 向所有玩家广播消息
     */
    public void broadcast(String message) {
        ServerLevel level = (ServerLevel) bot.level();
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal(prefix() + message), false);
        DevLog.info("FEEDBACK_BROADCAST", "message={}", DevLog.compact(message));
    }

    /**
     * 报告任务开始
     */
    public void reportTaskStart(String taskDescription) {
        sendToOwner("任务开始: " + taskDescription);
    }

    /**
     * 报告任务完成
     */
    public void reportTaskComplete(String taskDescription) {
        sendToOwner("§a任务完成:§r " + taskDescription);
    }

    /**
     * 报告任务失败
     */
    public void reportTaskFailed(String taskDescription, String reason) {
        sendToOwner("§c任务失败:§r " + taskDescription + " (原因: " + reason + ")");
    }

    /**
     * 报告动作完成
     */
    public void reportActionComplete(int actionIndex, int totalActions, String actionDescription) {
        sendToOwner("进度: [" + actionIndex + "/" + totalActions + "] " + actionDescription + " 完成");
    }

    /**
     * 报告动作失败
     */
    public void reportActionFailed(int actionIndex, int totalActions, String actionDescription, String reason) {
        sendToOwner("§c进度: [" + actionIndex + "/" + totalActions + "] " + actionDescription + " 失败: " + reason + "§r");
    }

    /**
     * 报告缺少资源
     */
    public void reportMissingResources(String description) {
        sendToOwner("§c缺少资源:§r " + description);
    }

    /** 报告资源未找到，给出配置命令提示 */
    public void reportResourceNotFound(String resource, int radius) {
        int nextRadius = Math.min(radius * 2, 128);
        sendToOwner("§c在" + radius + "格内找不到" + resource + "§r，可以扩大范围: §e/ai_bot config scanRadius " + nextRadius);
    }

    /** 报告资源在地下无法到达 */
    public void reportResourceUnderground(String resource, int y) {
        sendToOwner("§c" + resource + "都在地下 y=" + y + "§r，无法从地表到达。需要挖掘下去或找到洞穴入口。");
    }

    /**
     * 报告物品交付
     */
    public void reportItemDelivered(String itemName, int count) {
        sendToOwner("已交付: " + itemName + " x" + count);
    }

    /**
     * 报告位置信息
     */
    public void reportPosition() {
        sendToOwner("当前位置: (" +
                String.format("%.1f", bot.getX()) + ", " +
                String.format("%.1f", bot.getY()) + ", " +
                String.format("%.1f", bot.getZ()) + ")");
    }

    /**
     * 报告状态信息
     */
    public void reportStatus(String status) {
        sendToOwner("状态: " + status);
    }

    /**
     * 获取任务所有者 UUID
     */
    @Nullable
    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    /**
     * 获取任务所有者名称
     */
    @Nullable
    public String getOwnerName() {
        return ownerName;
    }
}
