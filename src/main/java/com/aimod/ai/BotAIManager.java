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
    private volatile List<String> lastCachedActions = null; // for deferred planCache.store on success
    private volatile boolean replanning = false;

    /** LLM常用action type名称 → 标准名称映射 */
    private static final java.util.Map<String, String> ACTION_TYPE_ALIASES = java.util.Map.ofEntries(
            java.util.Map.entry("place", "place_block"),
            java.util.Map.entry("placeBlock", "place_block"),
            java.util.Map.entry("break", "break_block"),
            java.util.Map.entry("move", "move_to"),
            java.util.Map.entry("mineBlock", "mine"),
            java.util.Map.entry("gatherResource", "gather"),
            java.util.Map.entry("followPlayer", "follow"),
            java.util.Map.entry("give", "give_item"),
            java.util.Map.entry("speak", "say"),
            java.util.Map.entry("interactBlock", "interact"),
            java.util.Map.entry("equipItem", "equip"),
            java.util.Map.entry("craftItem", "craft"),
            java.util.Map.entry("attackEntity", "attack")
    );

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

    /** Get memory stats for /aimod status display. */
    public String getMemoryStats() {
        return bot.getMemoryStore().getStats();
    }

    /** Extract a more specific failure reason from an action if available. */
    private static String getActionFailReason(Action action, String defaultReason) {
        if (action instanceof com.aimod.ai.action.GatherResourceAction g) {
            return g.failReason != null ? g.failReason : defaultReason;
        }
        return defaultReason;
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

            // Assemble context via ContextAssembler (replaces collectWorldContext)
            String worldContext = com.aimod.ai.memory.ContextAssembler.assemble(
                    bot.getMemoryStore(), null,
                    com.aimod.config.ModConfig.getMaxContextTokens());
            DevLog.info("TASK_CONTEXT", "len={}, estTokens={}",
                    worldContext.length(), LLMService.estimateTokens(worldContext));

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
                lastCachedActions = new ArrayList<>(response.getActions()); // save for deferred cache on success
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
                String reason = getActionFailReason(currentAction, "Cannot execute");
                feedback.reportActionFailed(
                        task.getCurrentActionIndex() + 1,
                        task.getActionCount(),
                        currentAction.getDescription(),
                        reason);
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
                    // Cache validated plan (only if no replanning occurred)
                    if (lastCommand != null && !lastCommand.isBlank()
                            && incrReplanCount == 0 && lastCachedActions != null) {
                        planCache.store(lastCommand, lastCachedActions, true);
                        DevLog.info("PLAN_CACHE_STORE_DEFERRED", "command={}, actions={}",
                                lastCommand, lastCachedActions.size());
                    }
                    lastCachedActions = null;
                    checkDeficitsAndReplan(task);
                }
            } else {
                // Skip duplicate feedback if replan already in progress (LLM thread running)
                if (!replanning) {
                    // Action failed — but check if it was a GiveItemAction with partial success
                    boolean isGiveItemPartial = (currentAction instanceof GiveItemAction g && g.getGivenCount() > 0);
                    String reason = getActionFailReason(currentAction, "Action failed");
                    feedback.reportActionFailed(
                            task.getCurrentActionIndex() + 1,
                            task.getActionCount(),
                            currentAction.getDescription(),
                            reason);
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
    private int consecutiveUnknown = 0;
    private final java.util.List<String> recentReplanAttempts = new java.util.ArrayList<>();

    private void incrementalReplan(Task task, String failedActionDesc) {
        if (replanning) return;
        if (incrReplanCount >= MAX_INCR_REPLAN) {
            task.setStatus(Task.TaskStatus.FAILED);
            feedback.reportTaskFailed(task.getDescription(), "Exceeded retry limit after " + incrReplanCount + " failures");
            planCache.markFailed(lastCommand); // invalidate bad cached plan
            incrReplanCount = 0;
            consecutiveUnknown = 0;
            return;
        }
        incrReplanCount++;
        replanning = true;
        // Pause UnstuckDetector while waiting for LLM response
        bot.getMovementController().getUnstuckDetector().setPaused(true);

        // Track this attempt for context in future replans
        recentReplanAttempts.add(failedActionDesc);
        while (recentReplanAttempts.size() > 10) recentReplanAttempts.remove(0);

        // Assemble structured context via ContextAssembler (with replan history)
        int replanTokens = com.aimod.config.ModConfig.getCompactTriggerTokens();
        String ctx = com.aimod.ai.memory.ContextAssembler.assemble(
                bot.getMemoryStore(), task, replanTokens, recentReplanAttempts)
                + "\nFailed action: " + failedActionDesc
                + "\nTip: logs -> 4 planks in 2x2 grid. Use exact log type."
                + "\nRespond with ONE JSON action using these type names: "
                + "move_to, break_block, place_block, mine, gather, craft, give_item, interact, equip, attack, follow, say, wait.";

        Thread t = new Thread(() -> {
            try {
                LLMResponse resp = llmService.sendPrompt(ctx);
                if (resp.isSuccess()) {
                    var acts = convertResponseToActions(resp, lastOwnerName);
                    if (!acts.isEmpty()) {
                        var next = acts.get(0);
                        consecutiveUnknown = 0; // reset
                        // Skip duplicate: if same description as what just failed, advance instead
                        if (next.getDescription().equals(failedActionDesc)) {
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            stateMachine.startExecuting();
                        } else {
                            task.injectAction(next);
                            task.advanceToNextAction(); // skip the failed action, execute the injected one
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                            DevLog.info("REPLAN_INCR", "injected={}", next.getDescription());
                            stateMachine.startExecuting();
                        }
                    } else {
                        // LLM returned unrecognizable actions — escalate
                        consecutiveUnknown++;
                        DevLog.warn("REPLAN_UNKNOWN_CONSEQ", "count={}, failedAction={}",
                                consecutiveUnknown, failedActionDesc);
                        if (consecutiveUnknown >= 3) {
                            task.setStatus(Task.TaskStatus.FAILED);
                            feedback.reportTaskFailed(task.getDescription(),
                                    "LLM repeatedly generated unrecognized action types");
                            planCache.markFailed(lastCommand);
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                        } else if (consecutiveUnknown >= 2) {
                            // Skip stuck action after 2 unknown attempts
                            task.advanceToNextAction();
                            incrReplanCount = 0;
                            consecutiveUnknown = 0;
                            stateMachine.startExecuting();
                            DevLog.info("REPLAN_SKIP_UNKNOWN", "advanced past stuck action");
                        }
                    }
                }
            } catch (Exception e) {
                task.setStatus(Task.TaskStatus.FAILED);
                feedback.reportTaskFailed(task.getDescription(), "Replan failed: " + e.getMessage());
            } finally {
                replanning = false;
                bot.getMovementController().getUnstuckDetector().setPaused(false);
            }
        }, "AIMod-Incr-" + bot.getStringUUID().substring(0, 8));
        t.setDaemon(true); t.start();
    }

    public void updateTask(Task task) {
        if (task == null || task.isCompleted()) {
            return;
        }

        // Auto-compact memory when approaching token budget (every 100 ticks ≈ 5s)
        if (bot.getServer() != null && bot.getServer().getTickCount() % 100 == 0) {
            int estTokens = com.aimod.ai.memory.ContextAssembler.estimateTotalTokens(bot.getMemoryStore());
            int triggerTokens = com.aimod.config.ModConfig.getCompactTriggerTokens();
            if (estTokens > triggerTokens) {
                bot.getMemoryStore().compactToTokenTarget(triggerTokens);
            }
        }

        executeTask(task);
    }

    /** Convert an LLM response to action objects using the shared parser. */
    private List<Action> convertResponseToActions(LLMResponse response, String ownerName) {
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
     * Parse a single action JSON object into an Action instance.
     * Shared by both convertResponseToActions and convertCachedToActions.
     */
    private Action parseActionFromJson(JsonObject obj, String ownerName) {
        String type = getString(obj, "type", "");
        if (type.isEmpty()) type = getString(obj, "action", "");
        if (type.isEmpty()) return null;

        // Normalize LLM-generated aliases to standard names
        type = ACTION_TYPE_ALIASES.getOrDefault(type, type);

        // Flatten nested "parameters" object (LLM sometimes wraps params)
        if (obj.has("parameters") && obj.get("parameters").isJsonObject()) {
            JsonObject params = obj.getAsJsonObject("parameters");
            for (String key : params.keySet()) {
                if (!obj.has(key)) obj.add(key, params.get(key));
            }
        }

        // Normalize item key
        if (obj.has("item") && !obj.has("item_id")) {
            obj.addProperty("item_id", getString(obj, "item", ""));
        }

        try {
            return switch (type) {
                case "move_to" -> {
                    int x = getInt(obj, "x", 0);
                    int y = getInt(obj, "y", 0);
                    int z = getInt(obj, "z", 0);
                    double speed = getDouble(obj, "speed", 1.0);
                    yield new MoveToAction(new BlockPos(x, y, z), speed);
                }
                case "break_block" -> {
                    yield new BreakBlockAction(new BlockPos(
                            getInt(obj, "x", 0),
                            getInt(obj, "y", 0),
                            getInt(obj, "z", 0)));
                }
                case "place_block" -> {
                    String blockId = getString(obj, "block_id",
                            getString(obj, "block", getString(obj, "item", "minecraft:stone")));
                    BlockItem bi = getBlockItemFromString(blockId);
                    if (bi != null) {
                        int px, py, pz;
                        // Support position array: [x, y, z]
                        if (obj.has("position") && obj.get("position").isJsonArray()) {
                            var arr = obj.getAsJsonArray("position");
                            px = arr.size() >= 3 ? arr.get(0).getAsInt() : 0;
                            py = arr.size() >= 3 ? arr.get(1).getAsInt() : 0;
                            pz = arr.size() >= 3 ? arr.get(2).getAsInt() : 0;
                        } else {
                            px = getInt(obj, "x", 0);
                            py = getInt(obj, "y", 0);
                            pz = getInt(obj, "z", 0);
                        }
                        yield new PlaceBlockAction(new BlockPos(px, py, pz), bi);
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
                Action action = parseActionFromJson(obj, ownerName);
                if (action != null) actions.add(action);
            } catch (Exception e) {
                DevLog.warn("CACHE_ACTION_PARSE_FAIL", "json={}", DevLog.compact(json));
            }
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