package com.example.aimod.fakeplayer;

import com.example.aimod.util.DevLog;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 假人玩家管理器。
 * 负责假人的创建、销毁、复用和生命周期管理。
 */
public class FakePlayerManager {

    private static final String FAKE_PLAYER_PREFIX = "AI_Bot_";
    private static final int MAX_FAKE_PLAYERS = 10;

    private final ServerLevel level;
    private final Map<UUID, FakePlayer> activePlayers = new ConcurrentHashMap<>();
    private final Map<UUID, FakePlayer> pool = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public FakePlayerManager(ServerLevel level) {
        this.level = level;
    }

    /**
     * 创建一个新的假人玩家
     */
    public FakePlayer createFakePlayer() {
        return createFakePlayer(null);
    }

    /**
     * 创建一个指定名称的假人玩家
     */
    public FakePlayer createFakePlayer(@Nullable String name) {
        if (activePlayers.size() >= MAX_FAKE_PLAYERS) {
            DevLog.warn("FAKE_PLAYER_LIMIT", "已达到最大假人数量限制: {}", MAX_FAKE_PLAYERS);
            return null;
        }

        // 尝试从池中复用
        FakePlayer pooled = reuseFromPool();
        if (pooled != null) {
            DevLog.info("FAKE_PLAYER_REUSE", "从池中复用假人: {}", pooled.getName().getString());
            return pooled;
        }

        // 创建新的假人
        String playerName = name != null ? name : FAKE_PLAYER_PREFIX + nextId.getAndIncrement();
        GameProfile profile = FakePlayer.createDefaultProfile(playerName);

        FakePlayer fakePlayer = new FakePlayer(level, profile, this);

        // 设置假人的游戏模式为生存模式
        fakePlayer.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);

        // 添加到世界
        level.addFreshEntity(fakePlayer);

        // 记录
        activePlayers.put(fakePlayer.getUUID(), fakePlayer);
        DevLog.info("FAKE_PLAYER_CREATE", "创建假人: {}, UUID: {}", playerName, fakePlayer.getUUID());

        return fakePlayer;
    }

    /**
     * 从池中复用假人
     */
    @Nullable
    private FakePlayer reuseFromPool() {
        Iterator<Map.Entry<UUID, FakePlayer>> iterator = pool.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, FakePlayer> entry = iterator.next();
            FakePlayer player = entry.getValue();
            iterator.remove();

            if (player.isAlive() && player.isActive()) {
                player.setActive(true);
                activePlayers.put(player.getUUID(), player);
                return player;
            }
        }
        return null;
    }

    /**
     * 移除假人（不销毁，放入池中）
     */
    public void removeFakePlayer(FakePlayer player) {
        if (player == null) return;

        UUID uuid = player.getUUID();
        activePlayers.remove(uuid);

        // 如果假人还活着，放入池中
        if (player.isAlive()) {
            player.setActive(false);
            pool.put(uuid, player);
            DevLog.info("FAKE_PLAYER_POOL", "假人放入池中: {}", player.getName().getString());
        } else {
            DevLog.info("FAKE_PLAYER_REMOVE", "假人死亡移除: {}", player.getName().getString());
        }
    }

    /**
     * 销毁假人
     */
    public void destroyFakePlayer(FakePlayer player) {
        if (player == null) return;

        UUID uuid = player.getUUID();
        activePlayers.remove(uuid);
        pool.remove(uuid);

        // 从世界中移除
        player.discard();
        DevLog.info("FAKE_PLAYER_DESTROY", "销毁假人: {}", player.getName().getString());
    }

    /**
     * 销毁所有假人
     */
    public void destroyAll() {
        for (FakePlayer player : activePlayers.values()) {
            player.discard();
        }
        for (FakePlayer player : pool.values()) {
            player.discard();
        }
        activePlayers.clear();
        pool.clear();
        DevLog.info("FAKE_PLAYER_DESTROY_ALL", "销毁所有假人");
    }

    /**
     * 获取所有活跃的假人
     */
    public Collection<FakePlayer> getActivePlayers() {
        return Collections.unmodifiableCollection(activePlayers.values());
    }

    /**
     * 根据 UUID 获取假人
     */
    @Nullable
    public FakePlayer getFakePlayer(UUID uuid) {
        return activePlayers.get(uuid);
    }

    /**
     * 根据名称获取假人
     */
    @Nullable
    public FakePlayer getFakePlayerByName(String name) {
        return activePlayers.values().stream()
                .filter(p -> p.getName().getString().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取最近的假人
     */
    @Nullable
    public FakePlayer getNearestFakePlayer(double x, double y, double z, double maxDistance) {
        FakePlayer nearest = null;
        double nearestDistance = maxDistance * maxDistance;

        for (FakePlayer player : activePlayers.values()) {
            if (!player.isAlive() || !player.isActive()) continue;

            double distance = player.distanceToSqr(x, y, z);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }

        return nearest;
    }

    /**
     * 获取假人数量
     */
    public int getActiveCount() {
        return activePlayers.size();
    }

    /**
     * 获取池中假人数量
     */
    public int getPoolSize() {
        return pool.size();
    }

    /**
     * 清理无效的假人
     */
    public void cleanup() {
        // 清理活跃假人中的无效条目
        activePlayers.entrySet().removeIf(entry -> {
            FakePlayer player = entry.getValue();
            if (!player.isAlive() || !player.isActive()) {
                DevLog.info("FAKE_PLAYER_CLEANUP", "清理无效假人: {}", player.getName().getString());
                return true;
            }
            return false;
        });

        // 清理池中的无效条目
        pool.entrySet().removeIf(entry -> {
            FakePlayer player = entry.getValue();
            return !player.isAlive();
        });
    }

    /**
     * 检查是否可以创建更多假人
     */
    public boolean canCreateMore() {
        return activePlayers.size() < MAX_FAKE_PLAYERS;
    }

    /**
     * 获取假人状态信息
     */
    public String getStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("假人状态:\n");
        sb.append("  活跃数量: ").append(activePlayers.size()).append("/").append(MAX_FAKE_PLAYERS).append("\n");
        sb.append("  池中数量: ").append(pool.size()).append("\n");

        if (!activePlayers.isEmpty()) {
            sb.append("  活跃假人:\n");
            for (FakePlayer player : activePlayers.values()) {
                sb.append("    - ").append(player.getName().getString())
                        .append(" (").append(String.format("%.1f", player.getX()))
                        .append(", ").append(String.format("%.1f", player.getY()))
                        .append(", ").append(String.format("%.1f", player.getZ())).append(")")
                        .append(" HP: ").append(String.format("%.1f", player.getHealth()))
                        .append("\n");
            }
        }

        return sb.toString();
    }
}
