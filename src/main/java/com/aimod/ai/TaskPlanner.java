package com.aimod.ai;

import com.aimod.ai.action.*;
import com.aimod.ai.llm.LLMResponse;
import com.aimod.ai.llm.LLMService;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.nio.file.Path;
import java.util.*;

/**
 * Handles task parsing, LLM interaction, and action conversion.
 * Extracted from BotAIManager for single-responsibility.
 */
public class TaskPlanner {

    private final FakePlayer bot;
    private final LLMService llmService;
    private final com.aimod.ai.llm.PlanCache planCache;
    private final TaskFeedback feedback;
    private final BotMetrics metrics;

    /** LLM action type aliases → standard names */
    private static final Map<String, String> ACTION_TYPE_ALIASES = Map.ofEntries(
            Map.entry("place", "place_block"),
            Map.entry("placeBlock", "place_block"),
            Map.entry("break", "break_block"),
            Map.entry("move", "move_to"),
            Map.entry("mineBlock", "mine"),
            Map.entry("gatherResource", "gather"),
            Map.entry("followPlayer", "follow"),
            Map.entry("give", "give_item"),
            Map.entry("speak", "say"),
            Map.entry("interactBlock", "interact"),
            Map.entry("equipItem", "equip"),
            Map.entry("craftItem", "craft"),
            Map.entry("attackEntity", "attack")
    );

    /** Known action type strings — used to detect shorthand LLM output */
    private static final Set<String> KNOWN_ACTION_TYPES = Set.of(
            "move_to", "break_block", "place_block", "attack", "craft", "follow",
            "give_item", "require_items", "say", "wait", "mine", "gather", "interact", "equip",
            "vein_mine"
    );

    /** Cached actions for deferred planCache.store on success */
    private volatile List<String> lastCachedActions = null;
    private volatile String lastCommand = null;
    private volatile String lastOwnerName = null;

    public TaskPlanner(FakePlayer bot, TaskFeedback feedback, BotMetrics metrics) {
        this.bot = bot;
        this.llmService = new LLMService();
        this.planCache = new com.aimod.ai.llm.PlanCache(
                bot.getServer() != null ? bot.getServer().getServerDirectory() : Path.of("."));
        this.feedback = feedback;
        this.metrics = metrics;
    }

    public LLMService getLlmService() { return llmService; }
    public com.aimod.ai.llm.PlanCache getPlanCache() { return planCache; }
    public String getLastCommand() { return lastCommand; }
    public String getLastOwnerName() { return lastOwnerName; }
    public List<String> getLastCachedActions() { return lastCachedActions; }
    public void clearLastCachedActions() { lastCachedActions = null; }

    /**
     * Parse a natural language command into a Task.
     * Checks PlanCache first, then falls back to LLM, then to local planner.
     */
    public Task parseCommand(String naturalLanguageCommand, String ownerName,
                             com.aimod.ai.llm.BotAIStateMachine stateMachine) {
        this.lastOwnerName = ownerName;
        this.lastCommand = naturalLanguageCommand;
        stateMachine.startPlanning(naturalLanguageCommand, 0);
        DevLog.info("TASK_PARSE_START", "bot={}, owner={}, command={}",
                bot.getStringUUID(), ownerName, DevLog.compact(naturalLanguageCommand));
        try {
            // Check plan cache first
            var cachedActions = planCache.find(naturalLanguageCommand);
            if (cachedActions.isPresent()) {
                Task task = new Task(naturalLanguageCommand);
                List<Action> actions = convertCachedToActions(cachedActions.get(), ownerName);
                if (!actions.isEmpty()) {
                    task.setActions(actions);
                    task.setStatus(Task.TaskStatus.IN_PROGRESS);
                    DevLog.info("TASK_PARSE_DONE", "source=cache, actionCount={}", actions.size());
                    return task;
                }
            }

            // Assemble context via ContextAssembler
            String worldContext = com.aimod.ai.memory.ContextAssembler.assemble(
                    bot.getMemoryStore(), null,
                    com.aimod.config.ModConfig.getMaxContextTokens());
            DevLog.info("TASK_CONTEXT", "len={}, estTokens={}",
                    worldContext.length(), LLMService.estimateTokens(worldContext));

            long llmStart = System.currentTimeMillis();
            LLMResponse response = llmService.parseCommand(naturalLanguageCommand, worldContext);
            long llmElapsed = System.currentTimeMillis() - llmStart;
            metrics.recordLlmCall(response.isSuccess(), llmElapsed);
            if (response.isSuccess()) {
                Task task = new Task(naturalLanguageCommand);
                List<Action> actions = convertResponseToActions(response, ownerName);
                if (actions.isEmpty()) {
                    DevLog.warn("TASK_PARSE_EMPTY", "llm returned no executable actions; trying fallback planner");
                    actions = createFallbackActions(naturalLanguageCommand, ownerName);
                }
                if (actions.isEmpty()) {
                    task.setStatus(Task.TaskStatus.FAILED);
                    metrics.recordTaskFailed();
                    DevLog.warn("TASK_PARSE_FAILED", "no actions available for command={}", DevLog.compact(naturalLanguageCommand));
                    return task;
                }
                task.setActions(actions);
                task.setStatus(Task.TaskStatus.IN_PROGRESS);
                lastCachedActions = new ArrayList<>(response.getActions());
                DevLog.info("TASK_PARSE_DONE", "source=llm, actionCount={}, actions={}",
                        actions.size(), describeActions(actions));
                return task;
            }
            DevLog.warn("TASK_PARSE_LLM_FAILURE", "error={}", response.getError());
        } catch (Exception e) {
            DevLog.error("TASK_PARSE_EXCEPTION", "unexpected parse exception", e);
        }

        // Fallback to local planner
        List<Action> fallbackActions = createFallbackActions(naturalLanguageCommand, ownerName);
        if (fallbackActions.isEmpty()) {
            DevLog.warn("TASK_PARSE_NO_FALLBACK", "command={}", DevLog.compact(naturalLanguageCommand));
            Task failedTask = new Task(naturalLanguageCommand);
            failedTask.setStatus(Task.TaskStatus.FAILED);
            return failedTask;
        }
        Task task = new Task(naturalLanguageCommand);
        task.setActions(fallbackActions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        DevLog.info("TASK_PARSE_DONE", "source=fallback, actionCount={}, actions={}",
                fallbackActions.size(), describeActions(fallbackActions));
        return task;
    }

    /**
     * Convert an LLM response to action objects.
     */
    public List<Action> convertResponseToActions(LLMResponse response, String ownerName) {
        List<Action> actions = new ArrayList<>();
        for (String actionJson : response.getActions()) {
            try {
                JsonObject actionObj = JsonParser.parseString(actionJson).getAsJsonObject();
                Action action = parseActionFromJson(actionObj, ownerName);
                if (action != null) {
                    actions.add(action);
                    DevLog.info("PLAN_ACTION_ADD", "type={}, action={}",
                            actionObj.has("type") ? getString(actionObj, "type", "") : getString(actionObj, "action", ""),
                            action.getDescription());
                }
            } catch (Exception e) {
                DevLog.warn("PLAN_ACTION_PARSE_FAIL", "json={}", DevLog.compact(actionJson));
            }
        }
        return actions;
    }

    /**
     * Convert cached action JSON strings to Action objects.
     */
    public List<Action> convertCachedToActions(List<String> actionJsons, String ownerName) {
        List<Action> actions = new ArrayList<>();
        for (String json : actionJsons) {
            try {
                var obj = JsonParser.parseString(json).getAsJsonObject();
                Action action = parseActionFromJson(obj, ownerName);
                if (action != null) actions.add(action);
            } catch (Exception e) {
                DevLog.warn("CACHE_ACTION_PARSE_FAIL", "json={}", DevLog.compact(json));
            }
        }
        return actions;
    }

    /**
     * Parse a single action JSON object into an Action instance.
     */
    public Action parseActionFromJson(JsonObject obj, String ownerName) {
        String type = getString(obj, "type", "");
        if (type.isEmpty()) type = getString(obj, "action", "");

        // Fix shorthand format: {"mine": "iron_ore"} → {"type": "mine", "block_type": "iron_ore"}
        if (type.isEmpty()) {
            for (String knownType : KNOWN_ACTION_TYPES) {
                if (obj.has(knownType)) {
                    var value = obj.get(knownType);
                    obj.remove(knownType);
                    obj.addProperty("type", knownType);
                    if (value.isJsonPrimitive()) {
                        String valStr = value.getAsString();
                        if (knownType.equals("mine") || knownType.equals("break_block")) {
                            if (!obj.has("block_type") && !obj.has("block_id"))
                                obj.addProperty("block_type", valStr);
                        } else if (knownType.equals("equip")) {
                            if (!obj.has("item_id")) obj.addProperty("item_id", valStr);
                        } else if (knownType.equals("say")) {
                            if (!obj.has("message")) obj.addProperty("message", valStr);
                        } else if (knownType.equals("follow")) {
                            if (!obj.has("player")) obj.addProperty("player", valStr);
                        } else if (knownType.equals("attack")) {
                            if (!obj.has("target")) obj.addProperty("target", valStr);
                        }
                    } else if (value.isJsonObject()) {
                        for (var entry : value.getAsJsonObject().entrySet()) {
                            if (!obj.has(entry.getKey())) obj.add(entry.getKey(), entry.getValue());
                        }
                    }
                    type = knownType;
                    DevLog.info("PLAN_ACTION_FIX_FORMAT", "original={}", DevLog.compact(value.toString()));
                    break;
                }
            }
        }

        if (type.isEmpty()) return null;

        // Normalize aliases
        type = ACTION_TYPE_ALIASES.getOrDefault(type, type);

        // Flatten nested "parameters" object
        if (obj.has("parameters") && obj.get("parameters").isJsonObject()) {
            JsonObject params = obj.getAsJsonObject("parameters");
            for (String key : params.keySet()) {
                if (!obj.has(key)) obj.add(key, params.get(key));
            }
        }

        // Flatten "position" array/object to x/y/z
        if (obj.has("position") && !obj.has("x")) {
            var pos = obj.get("position");
            if (pos.isJsonArray() && pos.getAsJsonArray().size() >= 3) {
                var arr = pos.getAsJsonArray();
                obj.addProperty("x", arr.get(0).getAsInt());
                obj.addProperty("y", arr.get(1).getAsInt());
                obj.addProperty("z", arr.get(2).getAsInt());
            } else if (pos.isJsonObject()) {
                var posObj = pos.getAsJsonObject();
                if (posObj.has("x")) obj.add("x", posObj.get("x"));
                if (posObj.has("y")) obj.add("y", posObj.get("y"));
                if (posObj.has("z")) obj.add("z", posObj.get("z"));
            }
        }

        // Normalize item key
        if (obj.has("item") && !obj.has("item_id")) {
            obj.addProperty("item_id", getString(obj, "item", ""));
        }

        try {
            return switch (type) {
                case "move_to" -> new MoveToAction(new BlockPos(
                        getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0)),
                        getDouble(obj, "speed", 1.0));
                case "break_block" -> new BreakBlockAction(new BlockPos(
                        getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0)));
                case "place_block" -> {
                    String blockId = getString(obj, "block_id",
                            getString(obj, "block", getString(obj, "item", "minecraft:stone")));
                    BlockItem bi = getBlockItemFromString(blockId);
                    if (bi != null) {
                        yield new PlaceBlockAction(new BlockPos(
                                getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0)), bi);
                    }
                    yield null;
                }
                case "attack" -> new AttackAction(getString(obj, "target", ""));
                case "craft" -> new CraftAction(
                        getString(obj, "item_id", getString(obj, "item", "")),
                        getInt(obj, "count", 1));
                case "follow" -> new FollowAction(getString(obj, "player", ""));
                case "give_item" -> new GiveItemAction(
                        getString(obj, "item_id", ""),
                        getInt(obj, "count", 1),
                        getString(obj, "player", ownerName));
                case "require_items" -> new RequireItemsAction(parseRequiredItems(obj));
                case "say" -> new SayAction(getString(obj, "message", ""));
                case "wait" -> new WaitAction(getInt(obj, "ticks",
                        getInt(obj, "seconds", 1) * 20));
                case "mine" -> new MineBlockAction(
                        getString(obj, "block_id", ""),
                        getInt(obj, "count", 1),
                        getInt(obj, "radius", 32));
                case "gather" -> {
                    String res = getString(obj, "resource_type", "WOOD");
                    try {
                        yield new GatherResourceAction(
                                GatherResourceAction.ResourceType.valueOf(res.toUpperCase(Locale.ROOT)),
                                getInt(obj, "count", 1),
                                getInt(obj, "radius", 32));
                    } catch (IllegalArgumentException e) {
                        DevLog.warn("PLAN_INVALID_RESOURCE", "resource={}", res);
                        yield null;
                    }
                }
                case "interact" -> {
                    String interactType = getString(obj, "interact_type", "CRAFTING_TABLE");
                    try {
                        yield new InteractBlockAction(
                                InteractBlockAction.InteractType.valueOf(interactType.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException e) {
                        DevLog.warn("PLAN_INVALID_INTERACT", "type={}", interactType);
                        yield null;
                    }
                }
                case "equip" -> new EquipItemAction(
                        getString(obj, "item_id", ""),
                        parseSlot(getString(obj, "slot", "")));
                default -> {
                    DevLog.warn("PLAN_ACTION_UNKNOWN", "type={}, json={}", type, DevLog.compact(obj.toString()));
                    yield null;
                }
            };
        } catch (Exception e) {
            DevLog.warn("PLAN_ACTION_PARSE_FAIL", "type={}, err={}", type, e.getMessage());
            return null;
        }
    }

    /**
     * Create fallback actions using the deterministic local planner.
     */
    public List<Action> createFallbackActions(String command, String ownerName) {
        var parsed = com.aimod.ai.planner.CommandParser.parse(command);
        if (parsed == null || parsed.verb() == com.aimod.ai.planner.CommandParser.Verb.UNKNOWN) {
            DevLog.warn("FALLBACK_PARSE_FAIL", "command={}", DevLog.compact(command));
            return List.of();
        }

        com.aimod.ai.planner.CommandParser.Verb verb = parsed.verb();
        String query = parsed.itemQuery();
        int count = parsed.count();
        String target = parsed.playerName();
        DevLog.info("FALLBACK_PARSE", "verb={}, item={}, count={}, target={}",
                verb, query, count, target);

        net.minecraft.world.item.Item item = com.aimod.ai.planner.CommandParser.findItem(query);
        if (item == null) {
            DevLog.warn("FALLBACK_ITEM_NOT_FOUND", "query={}", query);
            return List.of();
        }
        DevLog.info("FALLBACK_ITEM_FOUND", "query={}, item={}", query,
                BuiltInRegistries.ITEM.getKey(item));

        List<Action> actions = new ArrayList<>();

        switch (verb) {
            case CRAFT -> {
                actions.add(new SayAction("Starting: Craft " + count + "x " + query));
                actions.addAll(com.aimod.ai.planner.SequencePlanner.planCraftAndGive(bot, item, count, target));
                actions.add(new SayAction("Task complete: " + query + " crafted."));
            }
            case MINE -> {
                actions.add(new SayAction("Starting: Mine " + count + "x " + query));
                actions.addAll(com.aimod.ai.planner.SequencePlanner.planMine(item, count));
                actions.add(new SayAction("Task complete: Mining finished."));
            }
            case GATHER -> {
                actions.add(new SayAction("Starting: Gather " + count + "x " + query));
                actions.addAll(com.aimod.ai.planner.SequencePlanner.planGather(item, count));
                actions.add(new SayAction("Task complete: Gathering finished."));
            }
            case EQUIP -> {
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                actions.add(new SayAction("Equipping: " + itemId));
                actions.add(new EquipItemAction(itemId, null));
            }
            case GIVE -> {
                actions.add(new SayAction("Giving " + count + "x " + query + " to " + target));
                String itemId = BuiltInRegistries.ITEM.getKey(item).toString();
                actions.add(new GiveItemAction(itemId, count, target));
            }
            default -> {
                return List.of();
            }
        }
        return actions;
    }

    // === Helper methods ===

    private EquipmentSlot parseSlot(String slot) {
        if (slot == null || slot.isBlank()) return null;
        return switch (slot.toUpperCase(Locale.ROOT)) {
            case "HEAD", "HELMET" -> EquipmentSlot.HEAD;
            case "CHEST", "CHESTPLATE" -> EquipmentSlot.CHEST;
            case "LEGS", "LEGGINGS" -> EquipmentSlot.LEGS;
            case "FEET", "BOOTS" -> EquipmentSlot.FEET;
            case "MAINHAND", "MAIN" -> EquipmentSlot.MAINHAND;
            case "OFFHAND", "OFF" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsInt(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }

    private double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try { return obj.get(key).getAsDouble(); } catch (Exception e) { return defaultValue; }
        }
        return defaultValue;
    }

    private String getString(JsonObject object, String key, String fallback) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            return object.get(key).getAsString();
        }
        return fallback;
    }

    private BlockItem getBlockItemFromString(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null) return null;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item instanceof BlockItem blockItem ? blockItem : null;
    }

    private Map<String, Integer> parseRequiredItems(JsonObject actionObj) {
        Map<String, Integer> requiredItems = new LinkedHashMap<>();
        if (!actionObj.has("items") || !actionObj.get("items").isJsonArray()) return requiredItems;
        for (int i = 0; i < actionObj.getAsJsonArray("items").size(); i++) {
            JsonObject itemObj = actionObj.getAsJsonArray("items").get(i).getAsJsonObject();
            String itemId = getString(itemObj, "item_id", getString(itemObj, "item", ""));
            int count = itemObj.has("count") ? itemObj.get("count").getAsInt() : 1;
            if (!itemId.isBlank()) {
                requiredItems.put(itemId, requiredItems.getOrDefault(itemId, 0) + count);
            }
        }
        return requiredItems;
    }

    private String describeActions(List<Action> actions) {
        List<String> descriptions = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            descriptions.add(i + ":" + actions.get(i).getDescription());
        }
        return DevLog.compact(descriptions.toString());
    }

    /** Get failure reason from action if available. */
    public static String getActionFailReason(Action action, String defaultReason) {
        // Check base class failReason first
        if (action.getFailReason() != null) {
            return action.getFailReason();
        }
        // Legacy: GatherResourceAction has its own failReason field
        if (action instanceof GatherResourceAction g) {
            return g.failReason != null ? g.failReason : defaultReason;
        }
        return defaultReason;
    }
}
