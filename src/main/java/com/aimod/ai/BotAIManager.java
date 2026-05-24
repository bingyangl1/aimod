package com.aimod.ai;

import com.aimod.ai.action.Action;
import com.aimod.ai.action.AttackAction;
import com.aimod.ai.action.BreakBlockAction;
import com.aimod.ai.action.CraftAction;
import com.aimod.ai.action.EquipItemAction;
import com.aimod.ai.action.FollowAction;
import com.aimod.ai.action.GatherResourceAction;
import com.aimod.ai.action.GiveItemAction;
import com.aimod.ai.action.InteractBlockAction;
import com.aimod.ai.action.MineBlockAction;
import com.aimod.ai.action.MoveToAction;
import com.aimod.ai.action.PlaceBlockAction;
import com.aimod.ai.action.RequireItemsAction;
import com.aimod.ai.action.SayAction;
import com.aimod.ai.action.WaitAction;
import com.aimod.ai.llm.LLMResponse;
import com.aimod.ai.llm.LLMService;
import com.aimod.fakeplayer.FakePlayer;
import com.aimod.util.DevLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

public class BotAIManager {
    private final FakePlayer bot;
    private final LLMService llmService;
    private final TaskFeedback feedback;
    private final WorldScanner worldScanner;
    private final com.aimod.ai.llm.BotAIStateMachine stateMachine;
    private final com.aimod.ai.llm.PlanCache planCache;
    private volatile String lastOwnerName = null;
    private volatile String lastCommand = null;
    private volatile boolean replanning = false;

    public BotAIManager(FakePlayer bot) {
        this.bot = bot;
        this.llmService = new LLMService();
        this.feedback = new TaskFeedback(bot);
        this.worldScanner = new WorldScanner(bot);
        this.stateMachine = new com.aimod.ai.llm.BotAIStateMachine();
        this.planCache = new com.aimod.ai.llm.PlanCache(
                bot.getServer() != null ? bot.getServer().getServerDirectory() : Path.of("."));
    }

    public com.aimod.ai.llm.BotAIStateMachine getStateMachine() { return stateMachine; }

    /**
     * 获取任务反馈系统
     */
    public TaskFeedback getFeedback() {
        return feedback;
    }

    /**
     * 获取世界扫描器
     */
    public WorldScanner getWorldScanner() {
        return worldScanner;
    }

    public Task parseCommand(String naturalLanguageCommand) {
        return parseCommand(naturalLanguageCommand, null);
    }

    public Task parseCommand(String naturalLanguageCommand, String ownerName) {
        this.lastOwnerName = ownerName;
        this.lastCommand = naturalLanguageCommand;
        DevLog.info("TASK_PARSE_START", "bot={}, owner={}, command={}",
                bot.getStringUUID(), ownerName, DevLog.compact(naturalLanguageCommand));
        try {
            // Check plan cache first (skip LLM for similar past commands)
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

            // Collect world context and call LLM
            String worldContext = collectWorldContext();
            DevLog.info("TASK_CONTEXT", "worldContext={}", DevLog.compact(worldContext));

            LLMResponse response = llmService.parseCommand(naturalLanguageCommand, worldContext);
            if (response.isSuccess()) {
                Task task = new Task(naturalLanguageCommand);
                List<Action> actions = convertResponseToActions(response, ownerName);
                if (actions.isEmpty()) {
                    DevLog.warn("TASK_PARSE_EMPTY", "llm returned no executable actions; trying fallback planner");
                    actions = createFallbackActions(naturalLanguageCommand, ownerName);
                }
                if (actions.isEmpty()) {
                    task.setStatus(Task.TaskStatus.FAILED);
                    DevLog.warn("TASK_PARSE_FAILED", "no actions available for command={}", DevLog.compact(naturalLanguageCommand));
                    return task;
                }
                task.setActions(actions);
                task.setStatus(Task.TaskStatus.IN_PROGRESS);
                // Cache successful LLM plans for future reuse
                planCache.store(naturalLanguageCommand, response.getActions(), true);
                DevLog.info("TASK_PARSE_DONE", "source=llm, actionCount={}, actions={}",
                        actions.size(), describeActions(actions));
                return task;
            }
            DevLog.warn("TASK_PARSE_LLM_FAILURE", "error={}", response.getError());
        } catch (Exception e) {
            DevLog.error("TASK_PARSE_EXCEPTION", "unexpected parse exception", e);
        }

        List<Action> fallbackActions = createFallbackActions(naturalLanguageCommand, ownerName);
        if (fallbackActions.isEmpty()) {
            DevLog.warn("TASK_PARSE_NO_FALLBACK", "command={}", DevLog.compact(naturalLanguageCommand));
            return null;
        }
        Task task = new Task(naturalLanguageCommand);
        task.setActions(fallbackActions);
        task.setStatus(Task.TaskStatus.IN_PROGRESS);
        DevLog.info("TASK_PARSE_DONE", "source=fallback, actionCount={}, actions={}",
                fallbackActions.size(), describeActions(fallbackActions));
        return task;
    }

    /**
     * 收集世界上下文信息，用于 LLM 提示
     */
    private String collectWorldContext() {
        StringBuilder ctx = new StringBuilder();

        // 假人位置
        ctx.append("Bot position: (").append(String.format("%.1f", bot.getX()))
           .append(", ").append(String.format("%.1f", bot.getY()))
           .append(", ").append(String.format("%.1f", bot.getZ())).append(")\n");

        // 假人生命值
        ctx.append("Bot health: ").append(String.format("%.1f", bot.getHealth())).append("/20\n");

        // 假人背包内容
        ctx.append("Bot inventory: ");
        java.util.Map<net.minecraft.world.item.Item, Integer> inventoryItems = new java.util.LinkedHashMap<>();
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            net.minecraft.world.item.ItemStack stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty()) {
                inventoryItems.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        if (inventoryItems.isEmpty()) {
            ctx.append("empty\n");
        } else {
            boolean first = true;
            for (java.util.Map.Entry<net.minecraft.world.item.Item, Integer> entry : inventoryItems.entrySet()) {
                if (!first) ctx.append(", ");
                ctx.append(entry.getKey().getDescriptionId()).append(" x").append(entry.getValue());
                first = false;
            }
            ctx.append("\n");
        }

        // 当前时间
        long time = bot.level().getDayTime() % 24000;
        ctx.append("Time: ").append(time < 12000 ? "day" : "night").append(" (tick ").append(time).append(")\n");

        // 生物群系
        ctx.append("Biome: ").append(bot.level().getBiome(bot.blockPosition()).unwrapKey()
                .map(key -> key.toString()).orElse("unknown")).append("\n");

        // 世界扫描信息
        ctx.append("\n").append(worldScanner.scanEnvironment(16));

        return ctx.toString();
    }

    /**
     * 执行任务
     */
    public void executeTask(Task task) {
        if (task == null) {
            return;
        }
        // If task already completed (via advanceToNextAction), still check deficits
        if (task.isCompleted()) {
            checkDeficitsAndReplan(task);
            return;
        }

        Action currentAction = task.getCurrentAction();
        if (currentAction == null) {
            task.setStatus(Task.TaskStatus.COMPLETED);
            feedback.reportTaskComplete(task.getDescription());
            // Task completed — check for deficits and replan if needed
            checkDeficitsAndReplan(task);
            return;
        }

        // 执行当前动作
        if (currentAction.getStatus() == Action.ActionStatus.PENDING) {
            if (currentAction.canExecute(bot)) {
                currentAction.execute(bot);
            } else {
                currentAction.setStatus(Action.ActionStatus.FAILED);
                feedback.reportActionFailed(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription(),
                        "Cannot execute");
            }
        } else if (currentAction.getStatus() == Action.ActionStatus.IN_PROGRESS) {
            currentAction.execute(bot);
        }

        // 检查动作是否完成
        if (currentAction.isComplete(bot)) {
            if (currentAction.getStatus() == Action.ActionStatus.COMPLETED) {
                feedback.reportActionComplete(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription());
                task.advanceToNextAction();
                // Check deficits right when task transitions to COMPLETED
                if (task.isCompleted()) {
                    checkDeficitsAndReplan(task);
                }
            } else {
                // Action failed — but check if it was a GiveItemAction with partial success
                boolean isGiveItemPartial = (currentAction instanceof GiveItemAction g && g.getGivenCount() > 0);
                feedback.reportActionFailed(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription(),
                        "Action failed");
                if (isGiveItemPartial) {
                    // Skip failed give_item (partial) and continue
                    DevLog.info("GIVE_ITEM_PARTIAL_SKIP", "continuing task after partial give");
                    task.advanceToNextAction();
                } else {
                    // Incremental replan: ask LLM for next step instead of failing
                    stateMachine.requestReplan();
                    incrementalReplan(task, currentAction.getDescription());
                }
            }
        }
    }

    /**
     * 检查任务中 GiveItemAction 的缺口，并触发重新规划
     */
    private void checkDeficitsAndReplan(Task task) {
        if (replanning) {
            return; // 避免递归重规划
        }

        // 收集所有 GiveItemAction 的缺口
        Map<String, Integer> deficits = new LinkedHashMap<>();
        String targetPlayer = null;
        for (Action action : task.getActions()) {
            if (action instanceof GiveItemAction g) {
                if (g.getDeficit() > 0) {
                    deficits.merge(g.getItemId(), g.getDeficit(), Integer::sum);
                    targetPlayer = g.getTargetPlayerName();
                }
            }
        }

        if (deficits.isEmpty()) {
            return;
        }

        DevLog.info("REPLAN_DEFICITS", "deficits={}, targetPlayer={}", deficits, targetPlayer);

        // 构建重规划命令
        StringBuilder cmd = new StringBuilder("You previously tried to give items but didn't have enough. ");
        cmd.append("You still need to collect: ");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : deficits.entrySet()) {
            if (!first) cmd.append(", ");
            cmd.append(entry.getValue()).append(" more ").append(entry.getKey());
            first = false;
        }
        if (targetPlayer != null && !targetPlayer.isBlank()) {
            cmd.append(". Give them to ").append(targetPlayer);
        }
        cmd.append(". Go collect them now.");

        String replanCommand = cmd.toString();
        String ownerName = lastOwnerName;

        DevLog.info("REPLAN_SCHEDULED", "command={}", DevLog.compact(replanCommand));

        // 在后台线程调用 LLM 重规划
        replanning = true;
        Thread replanThread = new Thread(() -> {
            try {
                Task newTask = parseCommand(replanCommand, ownerName);
                if (newTask != null && newTask.getActionCount() > 0) {
                    if (bot.level().getServer() != null) {
                        bot.level().getServer().execute(() -> {
                            replanning = false;
                            DevLog.info("REPLAN_TASK_ASSIGNED", "actionCount={}", newTask.getActionCount());
                            feedback.reportTaskStart(replanCommand);
                            bot.setCurrentTask(newTask);
                            executeTask(newTask);
                        });
                    } else {
                        replanning = false;
                    }
                } else {
                    replanning = false;
                    DevLog.warn("REPLAN_NO_ACTIONS", "LLM returned no actions for replanning");
                }
            } catch (Exception e) {
                replanning = false;
                DevLog.error("REPLAN_FAILED", "replanning exception", e);
            }
        }, "AIMod-Replan-" + bot.getStringUUID().substring(0, 8));
        replanThread.setDaemon(true);
        replanThread.start();
    }

    /**
     * 更新任务
     */
    /**
     * Incremental replan: ask LLM for next action after a failure.
     * Includes inventory context and limits retries to prevent infinite loops.
     */
    private int incrReplanCount = 0;
    private static final int MAX_INCR_REPLAN = 5;

    private void incrementalReplan(Task task, String failedActionDesc) {
        if (replanning) return;
        if (incrReplanCount >= MAX_INCR_REPLAN) {
            task.setStatus(Task.TaskStatus.FAILED);
            feedback.reportTaskFailed(task.getDescription(), "Exceeded retry limit after " + incrReplanCount + " failures");
            incrReplanCount = 0;
            return;
        }
        incrReplanCount++;
        replanning = true;

        // Build rich context with inventory
        StringBuilder invCtx = new StringBuilder();
        for (int i = 0; i < bot.getInventory().getContainerSize(); i++) {
            var stack = bot.getInventory().getItem(i);
            if (!stack.isEmpty()) invCtx.append(stack.getCount()).append("x ")
                .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem())).append(" ");
        }
        String ctx = "Task: " + task.getDescription() + ". Failed action[" + task.getCurrentActionIndex()
            + "/" + task.getActionCount() + "]: " + failedActionDesc
            + ". Position: " + bot.blockPosition().toShortString()
            + ". Inventory: " + (invCtx.isEmpty() ? "empty" : invCtx.toString())
            + ". Respond ONLY with a valid JSON action like {\"type\":\"gather\",\"resource_type\":\"WOOD\",\"count\":1}.";

        Thread t = new Thread(() -> {
            try {
                LLMResponse resp = llmService.sendPrompt(ctx);
                if (resp.isSuccess()) {
                    var acts = convertResponseToActions(resp, lastOwnerName);
                    if (!acts.isEmpty()) {
                        var next = acts.get(0);
                        // Skip duplicate: if same description as what just failed, advance instead
                        if (next.getDescription().equals(failedActionDesc)) {
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            stateMachine.startExecuting();
                        } else {
                            task.injectAction(next);
                            incrReplanCount = 0;
                            DevLog.info("REPLAN_INCR", "injected={}", next.getDescription());
                            stateMachine.startExecuting();
                        }
                    }
                }
                // If no actions found, will retry next tick (up to MAX_INCR_REPLAN)
            } catch (Exception e) {
                task.setStatus(Task.TaskStatus.FAILED);
                feedback.reportTaskFailed(task.getDescription(), "Replan failed: " + e.getMessage());
            } finally { replanning = false; }
        }, "AIMod-Incr-" + bot.getStringUUID().substring(0, 8));
        t.setDaemon(true); t.start();
    }

    public void updateTask(Task task) {
        if (task == null || task.isCompleted()) {
            return;
        }
        executeTask(task);
    }

    /**
     * 将 LLM 响应转换为动作列表
     */
    private List<Action> convertResponseToActions(LLMResponse response, String ownerName) {
        List<Action> actions = new ArrayList<>();
        for (String actionJson : response.getActions()) {
            try {
                JsonObject actionObj = JsonParser.parseString(actionJson).getAsJsonObject();
                String type = getString(actionObj, "type", "");
                if (type.isEmpty()) type = getString(actionObj, "action", ""); // LLM sometimes uses "action" key
                // Normalize item key: LLM may use "item" instead of "item_id"
                if (actionObj.has("item") && !actionObj.has("item_id")) {
                    actionObj.addProperty("item_id", getString(actionObj, "item", ""));
                }
                switch (type) {
                    case "move_to":
                        int x = getInt(actionObj, "x", 0);
                        int y = getInt(actionObj, "y", 0);
                        int z = getInt(actionObj, "z", 0);
                        double speed = getDouble(actionObj, "speed", 1.0);
                        actions.add(new MoveToAction(new BlockPos(x, y, z), speed));
                        DevLog.info("PLAN_ACTION_ADD", "type=move_to, pos=({},{},{})", x, y, z);
                        break;
                    case "break_block":
                        x = getInt(actionObj, "x", 0);
                        y = getInt(actionObj, "y", 0);
                        z = getInt(actionObj, "z", 0);
                        actions.add(new BreakBlockAction(new BlockPos(x, y, z)));
                        DevLog.info("PLAN_ACTION_ADD", "type=break_block, pos=({},{},{})", x, y, z);
                        break;
                    case "place_block":
                        x = getInt(actionObj, "x", 0);
                        y = getInt(actionObj, "y", 0);
                        z = getInt(actionObj, "z", 0);
                        String blockId = getString(actionObj, "block_id", "minecraft:stone");
                        BlockItem blockItem = getBlockItemFromString(blockId);
                        if (blockItem != null) {
                            actions.add(new PlaceBlockAction(new BlockPos(x, y, z), blockItem));
                            DevLog.info("PLAN_ACTION_ADD", "type=place_block, pos=({},{},{}), block={}", x, y, z, blockId);
                        }
                        break;
                    case "attack":
                        String target = getString(actionObj, "target", "");
                        actions.add(new AttackAction(target));
                        DevLog.info("PLAN_ACTION_ADD", "type=attack, target={}", target);
                        break;
                    case "craft":
                        String itemId = getString(actionObj, "item_id", "");
                        int count = getInt(actionObj, "count", 1);
                        actions.add(new CraftAction(itemId, count));
                        DevLog.info("PLAN_ACTION_ADD", "type=craft, item={}, count={}", itemId, count);
                        break;
                    case "follow":
                        String player = getString(actionObj, "player", "");
                        actions.add(new FollowAction(player));
                        DevLog.info("PLAN_ACTION_ADD", "type=follow, player={}", player);
                        break;
                    case "give_item":
                        itemId = getString(actionObj, "item_id", "");
                        count = getInt(actionObj, "count", 1);
                        player = getString(actionObj, "player", ownerName);
                        actions.add(new GiveItemAction(itemId, count, player));
                        DevLog.info("PLAN_ACTION_ADD", "type=give_item, item={}, count={}, player={}", itemId, count, player);
                        break;
                    case "require_items":
                        Map<String, Integer> requiredItems = parseRequiredItems(actionObj);
                        actions.add(new RequireItemsAction(requiredItems));
                        DevLog.info("PLAN_ACTION_ADD", "type=require_items, items={}", requiredItems);
                        break;
                    case "say":
                        String message = getString(actionObj, "message", "");
                        actions.add(new SayAction(message));
                        DevLog.info("PLAN_ACTION_ADD", "type=say, message={}", DevLog.compact(message));
                        break;
                    case "wait":
                        int ticks = getInt(actionObj, "ticks",
                                getInt(actionObj, "seconds", 1) * 20);
                        actions.add(new WaitAction(ticks));
                        DevLog.info("PLAN_ACTION_ADD", "type=wait, ticks={}", ticks);
                        break;
                    case "mine":
                        String mineBlockId = getString(actionObj, "block_id", "");
                        count = getInt(actionObj, "count", 1);
                        int radius = getInt(actionObj, "radius", 32);
                        actions.add(new MineBlockAction(mineBlockId, count, radius));
                        DevLog.info("PLAN_ACTION_ADD", "type=mine, block={}, count={}, radius={}", mineBlockId, count, radius);
                        break;
                    case "gather":
                        String resourceType = getString(actionObj, "resource_type", "WOOD");
                        count = getInt(actionObj, "count", 1);
                        radius = getInt(actionObj, "radius", 32);
                        try {
                            GatherResourceAction.ResourceType type2 = GatherResourceAction.ResourceType.valueOf(resourceType.toUpperCase(Locale.ROOT));
                            actions.add(new GatherResourceAction(type2, count, radius));
                            DevLog.info("PLAN_ACTION_ADD", "type=gather, resource={}, count={}, radius={}", resourceType, count, radius);
                        } catch (IllegalArgumentException e) {
                            DevLog.warn("PLAN_ACTION_INVALID_RESOURCE", "resource={}", resourceType);
                        }
                        break;
                    case "interact":
                        String interactType = getString(actionObj, "interact_type", "CRAFTING_TABLE");
                        try {
                            InteractBlockAction.InteractType type3 = InteractBlockAction.InteractType.valueOf(interactType.toUpperCase(Locale.ROOT));
                            actions.add(new InteractBlockAction(type3));
                            DevLog.info("PLAN_ACTION_ADD", "type=interact, interact_type={}", interactType);
                        } catch (IllegalArgumentException e) {
                            DevLog.warn("PLAN_ACTION_INVALID_INTERACT", "type={}", interactType);
                        }
                        break;
                    case "equip":
                        itemId = getString(actionObj, "item_id", "");
                        String slotStr = getString(actionObj, "slot", "");
                        EquipmentSlot slot = parseSlot(slotStr);
                        actions.add(new EquipItemAction(itemId, slot));
                        DevLog.info("PLAN_ACTION_ADD", "type=equip, item={}, slot={}", itemId, slot);
                        break;
                    default:
                        DevLog.warn("PLAN_ACTION_UNKNOWN", "type={}, json={}", type, DevLog.compact(actionJson));
                        break;
                }
            } catch (Exception e) {
                DevLog.error("PLAN_ACTION_PARSE_EXCEPTION", "failed action json=" + DevLog.compact(actionJson), e);
            }
        }

        return actions;
    }

    /**
     * 解析装备槽位
     */
    private EquipmentSlot parseSlot(String slot) {
        if (slot == null || slot.isBlank()) {
            return null;
        }
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

    /**
     * 安全获取整数值
     */
    private int getInt(JsonObject obj, String key, int defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /**
     * 安全获取浮点值
     */
    private double getDouble(JsonObject obj, String key, double defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsDouble();
            } catch (Exception e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private BlockItem getBlockItemFromString(String blockId) {
        ResourceLocation id = ResourceLocation.tryParse(blockId.contains(":") ? blockId : "minecraft:" + blockId);
        if (id == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item instanceof BlockItem blockItem) {
            return blockItem;
        }
        return null;
    }

    private List<Action> createFallbackActions(String command, String ownerName) {
        // Use the deterministic planner pipeline: NLP → Item lookup → MaterialTree → Actions
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

        // Fuzzy-find the item
        net.minecraft.world.item.Item item = com.aimod.ai.planner.CommandParser.findItem(query);
        if (item == null) {
            DevLog.warn("FALLBACK_ITEM_NOT_FOUND", "query={}", query);
            return List.of();
        }
        DevLog.info("FALLBACK_ITEM_FOUND", "query={}, item={}", query,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));

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
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
                actions.add(new SayAction("Equipping: " + itemId));
                actions.add(new EquipItemAction(itemId, null));
            }
            case GIVE -> {
                actions.add(new SayAction("Giving " + count + "x " + query + " to " + target));
                String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString();
                actions.add(new GiveItemAction(itemId, count, target));
            }
            default -> {
                return List.of();
            }
        }
        return actions;
    }

    private String getString(JsonObject object, String key, String fallback) {
        if (object.has(key) && !object.get(key).isJsonNull()) {
            return object.get(key).getAsString();
        }
        return fallback;
    }

    /** Convert cached action JSON strings to Action objects. */
    private List<Action> convertCachedToActions(List<String> actionJsons, String ownerName) {
        List<Action> actions = new ArrayList<>();
        for (String json : actionJsons) {
            try {
                var obj = JsonParser.parseString(json).getAsJsonObject();
                String type = getString(obj, "type", getString(obj, "action", ""));
                switch (type) {
                    case "say":
                        actions.add(new SayAction(getString(obj, "message", "")));
                        break;
                    case "gather":
                        String res = getString(obj, "resource_type", "WOOD");
                        try { actions.add(new GatherResourceAction(GatherResourceAction.ResourceType.valueOf(res.toUpperCase(Locale.ROOT)), getInt(obj, "count", 8))); }
                        catch (IllegalArgumentException ignored) {}
                        break;
                    case "mine":
                        actions.add(new MineBlockAction(getString(obj, "block_id", "stone"), getInt(obj, "count", 1)));
                        break;
                    case "craft":
                        String itemId = getString(obj, "item_id", getString(obj, "item", ""));
                        actions.add(new CraftAction(itemId, getInt(obj, "count", 1)));
                        break;
                    case "interact":
                        try { actions.add(new InteractBlockAction(InteractBlockAction.InteractType.valueOf(getString(obj, "interact_type", "CRAFTING_TABLE").toUpperCase(Locale.ROOT)))); }
                        catch (IllegalArgumentException ignored) {}
                        break;
                    case "equip":
                        actions.add(new EquipItemAction(getString(obj, "item_id", ""), null));
                        break;
                    case "give_item":
                        actions.add(new GiveItemAction(getString(obj, "item_id", ""), getInt(obj, "count", 1), getString(obj, "player", ownerName)));
                        break;
                    case "follow":
                        actions.add(new FollowAction(getString(obj, "player", "")));
                        break;
                    case "move_to":
                        actions.add(new MoveToAction(new net.minecraft.core.BlockPos(getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0)), getDouble(obj, "speed", 1.0)));
                        break;
                    case "place_block":
                        String blockId = getString(obj, "block_id", getString(obj, "block", ""));
                        var bi = getBlockItemFromString(blockId);
                        if (bi != null) actions.add(new PlaceBlockAction(new net.minecraft.core.BlockPos(getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0)), bi));
                        break;
                    case "break_block":
                        actions.add(new BreakBlockAction(new net.minecraft.core.BlockPos(getInt(obj, "x", 0), getInt(obj, "y", 0), getInt(obj, "z", 0))));
                        break;
                    case "attack":
                        actions.add(new AttackAction(getString(obj, "target", "")));
                        break;
                    case "wait":
                        actions.add(new WaitAction(getInt(obj, "ticks", 20)));
                        break;
                }
            } catch (Exception e) { DevLog.warn("CACHE_ACTION_PARSE_FAIL", "json={}", DevLog.compact(json)); }
        }
        return actions;
    }

    private String describeActions(List<Action> actions) {
        List<String> descriptions = new ArrayList<>();
        for (int i = 0; i < actions.size(); i++) {
            descriptions.add(i + ":" + actions.get(i).getDescription());
        }
        return DevLog.compact(descriptions.toString());
    }

    private Map<String, Integer> parseRequiredItems(JsonObject actionObj) {
        Map<String, Integer> requiredItems = new LinkedHashMap<>();
        if (!actionObj.has("items") || !actionObj.get("items").isJsonArray()) {
            return requiredItems;
        }
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
}