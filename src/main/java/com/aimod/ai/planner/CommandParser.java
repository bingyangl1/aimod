package com.aimod.ai.planner;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pattern-based command parser. Extracts intent from natural language
 * without LLM, using regex + BuiltInRegistries fuzzy matching.
 */
public final class CommandParser {

    public enum Verb { CRAFT, MINE, GATHER, GIVE, EQUIP, UNKNOWN }

    public record ParsedCommand(Verb verb, String itemQuery, int count, String playerName) {
        public boolean isGive() { return playerName != null && !playerName.isBlank(); }
    }

    // Common verbs in Chinese and English
    private static final Pattern VERB_CRAFT = Pattern.compile("(制作|做|合成|craft|make)\\s*");
    private static final Pattern VERB_MINE  = Pattern.compile("(挖|采集|开采|mine|dig)\\s*");
    private static final Pattern VERB_GATHER = Pattern.compile("(砍|采集|收集|gather|chop|collect|get)\\s*");
    private static final Pattern VERB_EQUIP = Pattern.compile("(装备|穿戴|穿上|equip|wear|put on)\\s*");
    private static final Pattern COUNT_PAT = Pattern.compile("(\\d+)\\s*([个塊把件根组]|个|把|件|根|组)");
    private static final Pattern GIVE_PAT = Pattern.compile("(给|give|to|送给)\\s*(\\w+)?");

    // "一把钻石镐" → "钻石镐", count=1; "5个铁矿石" → "铁矿石", count=5
    private static final Pattern ITEM_COUNT_PREFIX = Pattern.compile("^([一二两三四五六七八九十百\\d]+)\\s*([个塊把件根组])?\\s*(.+)");

    public static ParsedCommand parse(String command) {
        String cmd = command.trim();
        Verb verb = Verb.UNKNOWN;
        String playerName = null;
        int count = 1;

        // Extract verb
        Matcher m;
        if ((m = VERB_CRAFT.matcher(cmd)).find()) { verb = Verb.CRAFT; cmd = cmd.substring(m.end()); }
        else if ((m = VERB_MINE.matcher(cmd)).find()) { verb = Verb.MINE; cmd = cmd.substring(m.end()); }
        else if ((m = VERB_GATHER.matcher(cmd)).find()) { verb = Verb.GATHER; cmd = cmd.substring(m.end()); }
        else if ((m = VERB_EQUIP.matcher(cmd)).find()) { verb = Verb.EQUIP; cmd = cmd.substring(m.end()); }

        // If no verb, infer from keywords
        if (verb == Verb.UNKNOWN) {
            if (cmd.contains("给") || cmd.contains("give")) verb = Verb.GIVE;
            else if (containsAny(cmd, "矿", "ore", "mine")) verb = Verb.MINE;
            else if (containsAny(cmd, "树", "木", "wood", "log", "chop")) verb = Verb.GATHER;
            else verb = Verb.CRAFT; // default: assume crafting
        }

        // Extract give target
        if ((m = GIVE_PAT.matcher(cmd)).find()) {
            String target = m.group(2);
            if (target == null || target.isBlank()) playerName = "me"; // "给我" → me
            else playerName = target;
            cmd = cmd.substring(0, m.start()) + cmd.substring(m.end());
        }

        // Extract count
        if ((m = COUNT_PAT.matcher(cmd)).find()) {
            count = Integer.parseInt(m.group(1));
            cmd = cmd.substring(0, m.start()) + " " + cmd.substring(m.end());
        } else {
            // "一把钻石镐" pattern
            m = ITEM_COUNT_PREFIX.matcher(cmd);
            if (m.matches()) {
                String cnt = m.group(1);
                count = parseChineseNumber(cnt);
                cmd = m.group(3);
            }
        }

        // Remaining text is the item query (strip filler words)
        String itemQuery = cmd.replaceAll("[一把个块件根组]", "").trim();
        if (itemQuery.isEmpty()) itemQuery = cmd.trim();

        return new ParsedCommand(verb, itemQuery, Math.max(1, count), playerName);
    }

    /** Fuzzy-find an item by name (Chinese, English, or resource path). */
    public static Item findItem(String query) {
        if (query == null || query.isBlank()) return null;
        String q = query.toLowerCase(Locale.ROOT).replace(" ", "_").replace("-", "_");

        // Try exact resource location first
        ResourceLocation rl = ResourceLocation.tryParse(q.contains(":") ? q : "minecraft:" + q);
        if (rl != null) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != Items.AIR) return item;
        }

        // Fuzzy search by description ID and resource path
        Item bestMatch = null;
        int bestScore = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) continue;
            int score = matchScore(item, q);
            if (score > bestScore) { bestScore = score; bestMatch = item; }
        }
        return bestScore >= 3 ? bestMatch : null;
    }

    /** Score how well an item matches a query. */
    private static int matchScore(Item item, String query) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        if (key == null) return 0;
        String path = key.getPath().toLowerCase(Locale.ROOT);
        String descId = item.getDescriptionId().toLowerCase(Locale.ROOT);

        int score = 0;
        // Exact match on path
        if (path.equals(query)) score += 10;
        else if (path.contains(query)) score += 5;
        else if (descId.contains(query)) score += 3;
        else {
            // Token-based fuzzy match
            for (String token : query.split("_")) {
                if (token.length() < 2) continue;
                if (path.contains(token)) score += 2;
                if (descId.contains(token)) score += 1;
            }
        }
        return score;
    }

    private static boolean containsAny(String s, String... keywords) {
        for (String kw : keywords) if (s.contains(kw)) return true;
        return false;
    }

    private static int parseChineseNumber(String s) {
        return switch (s) {
            case "一", "壹" -> 1; case "二", "两", "贰" -> 2;
            case "三", "叁" -> 3; case "四", "肆" -> 4; case "五", "伍" -> 5;
            case "六", "陆" -> 6; case "七", "柒" -> 7; case "八", "捌" -> 8;
            case "九", "玖" -> 9; case "十", "拾" -> 10;
            default -> {
                try { yield Integer.parseInt(s); } catch (NumberFormatException e) { yield 1; }
            }
        };
    }
}
