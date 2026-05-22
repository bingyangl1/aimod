package com.example.aimod.ai;

import com.example.aimod.fakeplayer.FakePlayer;
import com.example.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 世界扫描器，用于查找附近的方块、实体和资源。
 * 为 AI 决策提供世界感知能力。
 */
public class WorldScanner {

    private final FakePlayer bot;
    private final int defaultScanRadius;

    public WorldScanner(FakePlayer bot) {
        this(bot, 32);
    }

    public WorldScanner(FakePlayer bot, int defaultScanRadius) {
        this.bot = bot;
        this.defaultScanRadius = defaultScanRadius;
    }

    /**
     * 查找附近指定类型的方块
     */
    public List<BlockPos> findNearbyBlocks(String blockId, int radius) {
        Block targetBlock = resolveBlock(blockId);
        if (targetBlock == null) {
            DevLog.warn("SCAN_INVALID_BLOCK", "blockId={}", blockId);
            return Collections.emptyList();
        }
        return findNearbyBlocks(targetBlock, radius);
    }

    /**
     * 查找附近指定类型的方块
     */
    public List<BlockPos> findNearbyBlocks(Block targetBlock, int radius) {
        BlockPos botPos = bot.blockPosition();
        List<BlockPos> results = new ArrayList<>();
        final int MAX_RESULTS = 16;
        int radiusSq = radius * radius;

        for (BlockPos pos : BlockPos.betweenClosed(
                botPos.offset(-radius, -radius, -radius),
                botPos.offset(radius, radius, radius))) {
            BlockState state = bot.level().getBlockState(pos);
            if (state.is(targetBlock)) {
                results.add(pos.immutable());
                if (results.size() >= MAX_RESULTS) { break; }
            }
        }

        // 按距离排序
        results.sort(Comparator.comparingDouble(pos -> bot.distanceToSqr(
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)));

        DevLog.info("SCAN_BLOCKS", "block={}, radius={}, found={}",
                BuiltInRegistries.BLOCK.getKey(targetBlock), radius, results.size());
        return results;
    }

    /**
     * 查找最近的指定类型方块
     */
    @Nullable
    public BlockPos findNearestBlock(String blockId, int radius) {
        List<BlockPos> blocks = findNearbyBlocks(blockId, radius);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    /**
     * 查找最近的指定类型方块
     */
    @Nullable
    public BlockPos findNearestBlock(Block targetBlock, int radius) {
        List<BlockPos> blocks = findNearbyBlocks(targetBlock, radius);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    /**
     * 查找附近指定类型的实体
     */
    public List<LivingEntity> findNearbyEntities(String entityType, int radius) {
        return bot.level().getEntitiesOfClass(LivingEntity.class,
                bot.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && matchEntityType(entity, entityType))
                .stream()
                .sorted(Comparator.comparingDouble(e -> bot.distanceToSqr(e)))
                .collect(Collectors.toList());
    }

    /**
     * 查找最近的指定类型实体
     */
    @Nullable
    public LivingEntity findNearestEntity(String entityType, int radius) {
        List<LivingEntity> entities = findNearbyEntities(entityType, radius);
        return entities.isEmpty() ? null : entities.get(0);
    }

    /**
     * 查找附近的玩家
     */
    public List<Player> findNearbyPlayers(int radius) {
        if (!(bot.level() instanceof ServerLevel serverLevel)) {
            return Collections.emptyList();
        }
        return serverLevel.getPlayers(player -> bot.distanceToSqr(player) <= radius * radius)
                .stream()
                .sorted(Comparator.comparingDouble(p -> bot.distanceToSqr(p)))
                .collect(Collectors.toList());
    }

    /**
     * 查找最近的玩家
     */
    @Nullable
    public Player findNearestPlayer(int radius) {
        List<Player> players = findNearbyPlayers(radius);
        return players.isEmpty() ? null : players.get(0);
    }

    /**
     * 查找附近的工作台
     */
    @Nullable
    public BlockPos findNearestCraftingTable(int radius) {
        return findNearestBlock("minecraft:crafting_table", radius);
    }

    /**
     * 查找附近的熔炉
     */
    @Nullable
    public BlockPos findNearestFurnace(int radius) {
        return findNearestBlock("minecraft:furnace", radius);
    }

    /**
     * 查找附近的箱子
     */
    @Nullable
    public BlockPos findNearestChest(int radius) {
        return findNearestBlock("minecraft:chest", radius);
    }

    /**
     * 查找附近的铁砧
     */
    @Nullable
    public BlockPos findNearestAnvil(int radius) {
        return findNearestBlock("minecraft:anvil", radius);
    }

    /**
     * 扫描周围环境，返回描述文本
     */
    public String scanEnvironment(int radius) {
        StringBuilder sb = new StringBuilder();
        BlockPos botPos = bot.blockPosition();

        sb.append("Bot position: ").append(botPos.toShortString()).append("\n");

        // 扫描常见方块
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        String[] interestingBlocks = {
                "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
                "minecraft:diamond_ore", "minecraft:emerald_ore", "minecraft:redstone_ore",
                "minecraft:lapis_ore", "minecraft:copper_ore",
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest",
                "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
                "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt",
                "minecraft:water", "minecraft:lava"
        };

        for (String blockId : interestingBlocks) {
            Block block = resolveBlock(blockId);
            if (block != null) {
                int count = countNearbyBlocks(block, radius);
                if (count > 0) {
                    blockCounts.put(blockId, count);
                }
            }
        }

        if (!blockCounts.isEmpty()) {
            sb.append("Nearby blocks:\n");
            for (Map.Entry<String, Integer> entry : blockCounts.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        // 扫描实体
        List<LivingEntity> entities = bot.level().getEntitiesOfClass(LivingEntity.class,
                bot.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && !(entity instanceof FakePlayer));

        if (!entities.isEmpty()) {
            Map<String, Integer> entityCounts = new LinkedHashMap<>();
            for (LivingEntity entity : entities) {
                String name = entity.getType().toShortString();
                entityCounts.merge(name, 1, Integer::sum);
            }
            sb.append("Nearby entities:\n");
            for (Map.Entry<String, Integer> entry : entityCounts.entrySet()) {
                sb.append("  - ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }

        // 扫描玩家
        List<Player> players = findNearbyPlayers(radius);
        if (!players.isEmpty()) {
            sb.append("Nearby players:\n");
            for (Player player : players) {
                double distance = Math.sqrt(bot.distanceToSqr(player));
                sb.append("  - ").append(player.getName().getString())
                        .append(" (distance: ").append(String.format("%.1f", distance)).append(")\n");
            }
        }

        return sb.toString();
    }

    /**
     * 统计附近指定方块的数量
     */
    public int countNearbyBlocks(Block targetBlock, int radius) {
        BlockPos botPos = bot.blockPosition();
        int count = 0;

        for (BlockPos pos : BlockPos.betweenClosed(
                botPos.offset(-radius, -radius, -radius),
                botPos.offset(radius, radius, radius))) {
            if (bot.level().getBlockState(pos).is(targetBlock)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查指定位置附近是否有方块
     */
    public boolean hasBlockNearby(String blockId, int radius) {
        return findNearestBlock(blockId, radius) != null;
    }

    /**
     * 解析方块 ID
     */
    @Nullable
    private Block resolveBlock(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    /**
     * 匹配实体类型
     */
    private boolean matchEntityType(LivingEntity entity, String targetType) {
        String entityTypeName = entity.getType().toShortString().toLowerCase(Locale.ROOT);
        String target = targetType.toLowerCase(Locale.ROOT);

        // 直接匹配
        if (entityTypeName.contains(target)) {
            return true;
        }

        // 常见别名
        return switch (target) {
            case "zombie" -> entityTypeName.contains("zombie");
            case "skeleton" -> entityTypeName.contains("skeleton");
            case "creeper" -> entityTypeName.contains("creeper");
            case "spider" -> entityTypeName.contains("spider");
            case "cow" -> entityTypeName.contains("cow");
            case "pig" -> entityTypeName.contains("pig");
            case "sheep" -> entityTypeName.contains("sheep");
            case "chicken" -> entityTypeName.contains("chicken");
            case "villager" -> entityTypeName.contains("villager");
            default -> false;
        };
    }

    public int getDefaultScanRadius() {
        return defaultScanRadius;
    }
}
