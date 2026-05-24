package com.aimod.ai;

import com.aimod.ai.cache.ChunkCache;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Chunk-aware world scanner.
 *
 * <p>Replaces O(n³) {@code BlockPos.betweenClosed()} iteration with
 * chunk-ordered spiral scan: nearest chunks first, Y sorted within each
 * column. Early termination when result quota is met.</p>
 *
 * <p>For {@code scanEnvironment()} all 19 block types are counted in a
 * single pass instead of 19 independent O(n³) scans.</p>
 */
public class WorldScanner {

    private final Entity bot;
    private final int defaultScanRadius;
    @Nullable
    private ChunkCache chunkCache;

    public WorldScanner(Entity bot) {
        this(bot, 32);
    }

    public WorldScanner(Entity bot, int defaultScanRadius) {
        this.bot = bot;
        this.defaultScanRadius = defaultScanRadius;
    }

    /** Set a chunk cache for O(1) block lookups instead of level.getBlockState(). */
    public void setChunkCache(@Nullable ChunkCache cache) { this.chunkCache = cache; }

    private BlockState getState(BlockPos pos) {
        if (chunkCache != null) return chunkCache.getBlockState(pos);
        return bot.level().getBlockState(pos);
    }

    /**
     * Find nearby blocks of the given type. Uses chunk-ordered spiral scan.
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
     * Find nearby blocks of the given type. Uses chunk-ordered spiral scan.
     * Returns nearest matches, up to 16 results.
     */
    public List<BlockPos> findNearbyBlocks(Block targetBlock, int radius) {
        List<BlockPos> results = new ArrayList<>();
        scanChunks(targetBlock, radius, 16, results);
        DevLog.info("SCAN_BLOCKS", "block={}, radius={}, found={}",
                BuiltInRegistries.BLOCK.getKey(targetBlock), radius, results.size());
        return results;
    }

    /**
     * Find nearest block of given type.
     */
    @Nullable
    public BlockPos findNearestBlock(String blockId, int radius) {
        List<BlockPos> blocks = findNearbyBlocks(blockId, radius);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    /**
     * Find nearest block of given type.
     */
    @Nullable
    public BlockPos findNearestBlock(Block targetBlock, int radius) {
        List<BlockPos> blocks = findNearbyBlocks(targetBlock, radius);
        return blocks.isEmpty() ? null : blocks.get(0);
    }

    /**
     * Find nearby entities matching type. Uses AABB query (already efficient).
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
     * Find nearest entity of given type.
     */
    @Nullable
    public LivingEntity findNearestEntity(String entityType, int radius) {
        List<LivingEntity> entities = findNearbyEntities(entityType, radius);
        return entities.isEmpty() ? null : entities.get(0);
    }

    /**
     * Find nearby players. Uses AABB query (already efficient).
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
     * Find nearest player.
     */
    @Nullable
    public Player findNearestPlayer(int radius) {
        List<Player> players = findNearbyPlayers(radius);
        return players.isEmpty() ? null : players.get(0);
    }

    @Nullable
    public BlockPos findNearestCraftingTable(int radius) {
        return findNearestBlock("minecraft:crafting_table", radius);
    }

    @Nullable
    public BlockPos findNearestFurnace(int radius) {
        return findNearestBlock("minecraft:furnace", radius);
    }

    @Nullable
    public BlockPos findNearestChest(int radius) {
        return findNearestBlock("minecraft:chest", radius);
    }

    @Nullable
    public BlockPos findNearestAnvil(int radius) {
        return findNearestBlock("minecraft:anvil", radius);
    }

    /**
     * Single-pass environment scan: counts all 19 interesting block types,
     * nearby entities, and players in one efficient pass.
     */
    public String scanEnvironment(int radius) {
        StringBuilder sb = new StringBuilder();
        sb.append("Bot position: ").append(bot.blockPosition().toShortString()).append("\n");

        // Resolve interesting blocks once
        record BlockEntry(String id, Block block) {}
        List<BlockEntry> interesting = new ArrayList<>();
        for (String id : new String[]{
                "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
                "minecraft:diamond_ore", "minecraft:emerald_ore", "minecraft:redstone_ore",
                "minecraft:lapis_ore", "minecraft:copper_ore",
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest",
                "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
                "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt",
                "minecraft:water", "minecraft:lava"
        }) {
            Block b = resolveBlock(id);
            if (b != null) interesting.add(new BlockEntry(id, b));
        }

        // Single pass: count all 19 types at once
        Map<String, Integer> counts = new LinkedHashMap<>();
        scanBlocksMulti(interesting.stream().map(e -> e.block).toArray(Block[]::new),
                radius, (index) -> {
                    String id = interesting.get(index).id;
                    counts.merge(id, 1, Integer::sum);
                });

        if (!counts.isEmpty()) {
            sb.append("Nearby blocks:\n");
            counts.forEach((id, c) -> sb.append("  - ").append(id).append(": ").append(c).append("\n"));
        }

        // Entities
        List<LivingEntity> entities = bot.level().getEntitiesOfClass(LivingEntity.class,
                bot.getBoundingBox().inflate(radius),
                entity -> entity.isAlive() && !(entity instanceof FakePlayer));
        if (!entities.isEmpty()) {
            Map<String, Integer> entityCounts = new LinkedHashMap<>();
            for (LivingEntity entity : entities) {
                entityCounts.merge(entity.getType().toShortString(), 1, Integer::sum);
            }
            sb.append("Nearby entities:\n");
            entityCounts.forEach((name, c) -> sb.append("  - ").append(name).append(": ").append(c).append("\n"));
        }

        // Players
        List<Player> players = findNearbyPlayers(radius);
        if (!players.isEmpty()) {
            sb.append("Nearby players:\n");
            for (Player player : players) {
                sb.append("  - ").append(player.getName().getString())
                        .append(" (distance: ").append(String.format("%.1f", Math.sqrt(bot.distanceToSqr(player)))).append(")\n");
            }
        }
        return sb.toString();
    }

    /**
     * Count nearby blocks of given type (single-batch).
     */
    public int countNearbyBlocks(Block targetBlock, int radius) {
        int[] count = {0};
        scanBlocksMulti(new Block[]{targetBlock}, radius, (i) -> count[0]++);
        return count[0];
    }

    /**
     * Check if a block type exists nearby.
     */
    public boolean hasBlockNearby(String blockId, int radius) {
        return findNearestBlock(blockId, radius) != null;
    }

    // ========================
    //  Chunk-aware internals
    // ========================

    /**
     * Scan blocks in chunk-spiral order, collecting up to maxResults positions
     * that match the given target block. Results are nearest-first.
     */
    private void scanChunks(Block targetBlock, int radius, int maxResults, List<BlockPos> results) {
        BlockPos botPos = bot.blockPosition();
        int cx = botPos.getX() >> 4;
        int cz = botPos.getZ() >> 4;
        int chunkRadius = (radius >> 4) + 1;
        int radiusSq = radius * radius;
        var level = bot.level();

        for (int r = 0; r <= chunkRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                int dz = (dx == -r || dx == r) ? -r : (dx < r ? r : -r);
                // For each ring layer, iterate perimeter (or center)
                int startDz = -r, endDz = r, stepDz = 1;
                if (dx == -r || dx == r) { startDz = -r; endDz = r; }
                else { startDz = dz; endDz = dz; }

                for (int ddz = startDz; ddz <= endDz; ddz += (dx == -r || dx == r) ? 1 : 2*r) {
                    if (dx == 0 && ddz == 0) continue; // skip bot's own chunk (handled as r=0 center)
                    int chunkX = cx + dx;
                    int chunkZ = cz + ddz;
                    scanChunk(targetBlock, level, chunkX, chunkZ, botPos, radiusSq, results, maxResults);
                    if (results.size() >= maxResults) return;
                }
            }
        }
    }

    /**
     * Scan a single chunk column for matching blocks. Checks all Y values.
     */
    private void scanChunk(Block targetBlock, net.minecraft.world.level.Level level,
                           int chunkX, int chunkZ, BlockPos botPos,
                           int radiusSq, List<BlockPos> results, int maxResults) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Prefer same Y level (bot Y ± 2), then expand
        int botY = botPos.getY();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int dx = wx - botPos.getX();
                int dz = wz - botPos.getZ();
                if (dx * dx > radiusSq || dz * dz > radiusSq) continue;

                // Y-ordered scan: bot Y level first, then expand outward
                for (int dy = 0; dy <= Math.max(botY - minY, maxY - botY); dy++) {
                    if (results.size() >= maxResults) return;
                    // Check y = botY ± dy, starting with botY
                    for (int sign : new int[]{1, -1}) {
                        int wy = botY + sign * dy;
                        if (wy < minY || wy > maxY) continue;
                        if (dy == 0 && sign == -1) continue; // skip duplicate center
                        int edx = wx - botPos.getX();
                        int edy = wy - botPos.getY();
                        int edz = wz - botPos.getZ();
                        if (edx * edx + edy * edy + edz * edz > radiusSq) continue;
                        BlockPos pos = new BlockPos(wx, wy, wz);
                        if (getState(pos).is(targetBlock)) {
                            results.add(pos);
                        }
                    }
                }
            }
        }
    }

    /**
     * Single-pass multi-block scan: for every block position in the scan radius,
     * check all target blocks and invoke visitor with the matching index.
     * Uses chunk-ordered iteration.
     */
    private void scanBlocksMulti(Block[] targets, int radius, java.util.function.IntConsumer onMatch) {
        BlockPos botPos = bot.blockPosition();
        int cx = botPos.getX() >> 4;
        int cz = botPos.getZ() >> 4;
        int chunkRadius = (radius >> 4) + 1;
        int radiusSq = radius * radius;
        var level = bot.level();

        for (int r = 0; r <= chunkRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.abs(dx) != r && Math.abs(dz) != r) continue;
                    scanChunkMulti(targets, level, cx + dx, cz + dz, botPos, radiusSq, onMatch);
                }
            }
        }
    }

    private void scanChunkMulti(Block[] targets, net.minecraft.world.level.Level level,
                                int chunkX, int chunkZ, BlockPos botPos,
                                int radiusSq, java.util.function.IntConsumer onMatch) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int botY = botPos.getY();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = baseX + x;
                int wz = baseZ + z;
                int dx = wx - botPos.getX();
                int dz = wz - botPos.getZ();
                if (dx * dx > radiusSq || dz * dz > radiusSq) continue;

                for (int dy = 0; dy <= Math.max(botY - minY, maxY - botY); dy++) {
                    for (int sign : new int[]{1, -1}) {
                        int wy = botY + sign * dy;
                        if (wy < minY || wy > maxY) continue;
                        if (dy == 0 && sign == -1) continue;
                        int edx = wx - botPos.getX();
                        int edy = wy - botPos.getY();
                        int edz = wz - botPos.getZ();
                        if (edx * edx + edy * edy + edz * edz > radiusSq) continue;
                        BlockState state = getState(new BlockPos(wx, wy, wz));
                        for (int t = 0; t < targets.length; t++) {
                            if (state.is(targets[t])) {
                                onMatch.accept(t);
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private Block resolveBlock(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null) return null;
        return BuiltInRegistries.BLOCK.get(id);
    }

    private boolean matchEntityType(LivingEntity entity, String targetType) {
        String typeName = entity.getType().toShortString().toLowerCase(Locale.ROOT);
        String target = targetType.toLowerCase(Locale.ROOT);
        if (typeName.contains(target)) return true;
        return switch (target) {
            case "zombie" -> typeName.contains("zombie");
            case "skeleton" -> typeName.contains("skeleton");
            case "creeper" -> typeName.contains("creeper");
            case "spider" -> typeName.contains("spider");
            case "cow" -> typeName.contains("cow");
            case "pig" -> typeName.contains("pig");
            case "sheep" -> typeName.contains("sheep");
            case "chicken" -> typeName.contains("chicken");
            case "villager" -> typeName.contains("villager");
            default -> false;
        };
    }

    public int getDefaultScanRadius() { return defaultScanRadius; }
}
