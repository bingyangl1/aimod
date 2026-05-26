package com.aimod.ai.memory;

import com.aimod.ai.WorldScanner;
import com.aimod.fakeplayer.FakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Structured world observation snapshot.
 * Replaces the ad-hoc string concatenation in collectWorldContext().
 */
public final class WorldObservation {

    public final BlockPos botPosition;
    public final float health;
    public final float food;
    public final String biome;
    public final String timeOfDay; // "day" or "night"
    public final Map<String, Integer> nearbyBlockCounts;
    public final List<String> nearbyThreats; // e.g. "zombie@(10,64,5)"
    public final Map<String, Integer> inventorySnapshot;
    public final long timestamp;

    private WorldObservation(BlockPos pos, float h, float f, String bio, String tod,
                             Map<String, Integer> blocks, List<String> threats,
                             Map<String, Integer> inv, long ts) {
        this.botPosition = pos;
        this.health = h;
        this.food = f;
        this.biome = bio;
        this.timeOfDay = tod;
        this.nearbyBlockCounts = Collections.unmodifiableMap(blocks);
        this.nearbyThreats = Collections.unmodifiableList(threats);
        this.inventorySnapshot = Collections.unmodifiableMap(inv);
        this.timestamp = ts;
    }

    /** Factory: build from current world state. */
    public static WorldObservation from(FakePlayer bot) {
        BlockPos pos = bot.blockPosition();

        float health = 0;
        try { health = bot.getHealth(); } catch (Exception ignored) {}
        float food = 0;
        try {
            var fd = bot.getFoodData();
            if (fd != null) food = fd.getFoodLevel();
        } catch (Exception ignored) {}

        String biome = "";
        try {
            var holder = bot.level().getBiome(pos);
            biome = holder.unwrapKey()
                    .map(k -> k.location().toString())
                    .orElse("unknown");
        } catch (Exception ignored) {}

        long dayTime = bot.level().getDayTime() % 24000;
        String tod = dayTime < 12000 ? "day" : "night";

        // Scan nearby blocks of interest
        Map<String, Integer> blockCounts = new LinkedHashMap<>();
        String[] interesting = {
                "minecraft:coal_ore", "minecraft:iron_ore", "minecraft:gold_ore",
                "minecraft:diamond_ore", "minecraft:emerald_ore", "minecraft:redstone_ore",
                "minecraft:lapis_ore", "minecraft:copper_ore",
                "minecraft:crafting_table", "minecraft:furnace", "minecraft:chest",
                "minecraft:oak_log", "minecraft:birch_log", "minecraft:spruce_log",
                "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt",
                "minecraft:water", "minecraft:lava"
        };
        for (String blockId : interesting) {
            int count = countNearby(bot, blockId, 16);
            if (count > 0) blockCounts.put(blockId, count);
        }

        // Scan threats (hostile mobs)
        List<String> threats = new ArrayList<>();
        try {
            var entities = bot.level().getEntitiesOfClass(
                    net.minecraft.world.entity.LivingEntity.class,
                    bot.getBoundingBox().inflate(16),
                    e -> e.isAlive() && !(e instanceof FakePlayer)
                            && (e instanceof net.minecraft.world.entity.monster.Monster
                                || e.getType().toString().contains("creeper")));
            for (var e : entities) {
                threats.add(e.getType().toShortString() + "@" + e.blockPosition().toShortString());
            }
        } catch (Exception ignored) {}

        // Snapshot inventory
        Map<String, Integer> inv = new LinkedHashMap<>();
        try {
            var playerInv = bot.getInventory();
            for (int i = 0; i < playerInv.getContainerSize(); i++) {
                ItemStack stack = playerInv.getItem(i);
                if (!stack.isEmpty()) {
                    String key = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString();
                    inv.merge(key, stack.getCount(), Integer::sum);
                }
            }
        } catch (Exception ignored) {}

        return new WorldObservation(pos, health, food, biome, tod, blockCounts, threats, inv,
                System.currentTimeMillis());
    }

    /** Single-line compact representation for memory compaction. */
    public String toCompactString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(timeOfDay).append("] ");
        sb.append("(").append(botPosition.toShortString()).append(") ");
        sb.append("HP:").append(String.format("%.0f", health))
          .append(" Food:").append(String.format("%.0f", food)).append(" ");
        sb.append("bio:").append(biome.contains(":") ? biome.substring(biome.lastIndexOf(':') + 1) : biome);
        if (!nearbyBlockCounts.isEmpty()) {
            sb.append(" blocks:{");
            int i = 0;
            for (var e : nearbyBlockCounts.entrySet()) {
                if (i++ > 0) sb.append(",");
                String shortName = e.getKey().contains(":") ? e.getKey().substring(e.getKey().lastIndexOf(':') + 1) : e.getKey();
                sb.append(shortName).append(":").append(e.getValue());
                if (i >= 8) { sb.append(",..."); break; }
            }
            sb.append("}");
        }
        if (!nearbyThreats.isEmpty()) {
            sb.append(" threats:").append(nearbyThreats.size());
        }
        return sb.toString();
    }

    /**
     * Format as a context block for LLM prompt, respecting maxChars.
     */
    public String toContextBlock(int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append("Position: ").append(botPosition.toShortString()).append("\n");
        sb.append("Health: ").append(String.format("%.0f", health)).append("/20\n");
        sb.append("Food: ").append(String.format("%.0f", food)).append("\n");
        sb.append("Time: ").append(timeOfDay).append("\n");
        if (!biome.isEmpty()) {
            sb.append("Biome: ").append(biome.contains(":") ? biome.substring(biome.lastIndexOf(':') + 1) : biome).append("\n");
        }

        if (!nearbyBlockCounts.isEmpty()) {
            sb.append("Nearby blocks: ");
            int i = 0;
            for (var e : nearbyBlockCounts.entrySet()) {
                if (i++ > 0) sb.append(", ");
                String shortName = e.getKey().contains(":") ? e.getKey().substring(e.getKey().lastIndexOf(':') + 1) : e.getKey();
                sb.append(shortName).append(" x").append(e.getValue());
            }
            sb.append("\n");
        }

        if (!nearbyThreats.isEmpty()) {
            sb.append("Threats: ");
            for (int i = 0; i < Math.min(nearbyThreats.size(), 5); i++) {
                if (i > 0) sb.append(", ");
                sb.append(nearbyThreats.get(i));
            }
            sb.append("\n");
        }

        if (!inventorySnapshot.isEmpty()) {
            sb.append("Inventory: ");
            int i = 0;
            for (var e : inventorySnapshot.entrySet()) {
                if (i++ > 0) sb.append(", ");
                String shortName = e.getKey().contains(":") ? e.getKey().substring(e.getKey().lastIndexOf(':') + 1) : e.getKey();
                sb.append(e.getValue()).append("x ").append(shortName);
            }
            sb.append("\n");
        }

        String result = sb.toString();
        if (result.length() > maxChars) {
            return result.substring(0, maxChars - 3) + "...";
        }
        return result;
    }

    /** Check if two observations are functionally identical for dedup purposes. */
    public boolean isDuplicateOf(WorldObservation other) {
        if (other == null) return false;
        return botPosition.equals(other.botPosition)
                && (int) health == (int) other.health
                && (int) food == (int) other.food
                && timeOfDay.equals(other.timeOfDay)
                && inventorySnapshot.equals(other.inventorySnapshot);
    }

    /** Rough token estimate: characters / 3.5 (typical for English text). */
    public int estimateTokens() {
        return Math.max(1, toContextBlock(Integer.MAX_VALUE).length() * 10 / 35);
    }

    private static int countNearby(FakePlayer bot, String blockId, int radius) {
        try {
            var rl = net.minecraft.resources.ResourceLocation.tryParse(blockId);
            if (rl == null) return 0;
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
            if (block == net.minecraft.world.level.block.Blocks.AIR) return 0;
            BlockPos bp = bot.blockPosition();
            int count = 0;
            for (BlockPos p : BlockPos.betweenClosed(
                    bp.offset(-radius, -radius, -radius),
                    bp.offset(radius, radius, radius))) {
                if (bot.level().getBlockState(p).is(block)) count++;
                if (count > 999) break;
            }
            return count;
        } catch (Exception e) { return 0; }
    }
}
