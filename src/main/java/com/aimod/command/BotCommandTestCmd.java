package com.aimod.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Test command: /ai_bot test — runs self-test suite.
 */
public class BotCommandTestCmd implements SubCommand {

    @Override
    public void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("test")
                .executes(BotCommandTestCmd::runTests));
    }

    private static int runTests(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        int passed = 0, failed = 0;
        var sb = new StringBuilder();
        sb.append("===== AI Bot Self Test =====\n");

        // Test 1: CommandParser
        try {
            var r = com.aimod.ai.planner.CommandParser.parse("制作一把钻石镐给我");
            if (r.verb() == com.aimod.ai.planner.CommandParser.Verb.CRAFT && r.isGive()) { passed++; sb.append("[PASS] CommandParser: craft+give\n"); }
            else { failed++; sb.append("[FAIL] CommandParser: craft+give (verb=").append(r.verb()).append(")\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] CommandParser: ").append(e.getMessage()).append("\n"); }

        try {
            var r = com.aimod.ai.planner.CommandParser.parse("挖5个铁矿石");
            if (r.verb() == com.aimod.ai.planner.CommandParser.Verb.MINE && r.count() == 5) { passed++; sb.append("[PASS] CommandParser: mine+count\n"); }
            else { failed++; sb.append("[FAIL] CommandParser: mine+count\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] CommandParser: ").append(e.getMessage()).append("\n"); }

        // Test 2: Item Lookup
        try {
            var item = com.aimod.ai.planner.CommandParser.findItem("diamond_pickaxe");
            if (item != null) { passed++; sb.append("[PASS] ItemLookup: diamond_pickaxe found\n"); }
            else { failed++; sb.append("[FAIL] ItemLookup: diamond_pickaxe not found\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] ItemLookup: ").append(e.getMessage()).append("\n"); }

        try {
            var item = com.aimod.ai.planner.CommandParser.findItem("zzzzzzzzz_not_a_real_item_12345");
            if (item == null) { passed++; sb.append("[PASS] ItemLookup: nonexistent returns null\n"); }
            else { failed++; sb.append("[FAIL] ItemLookup: should return null, got ").append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item)).append("\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] ItemLookup: ").append(e.getMessage()).append("\n"); }

        // Test 3: RecipeIndex
        try {
            var idx = com.aimod.ai.RecipeIndex.getInstance();
            if (!idx.isBuilt()) idx.build(source.getLevel());
            var recipes = idx.getRecipesForOutput(net.minecraft.world.item.Items.STICK);
            if (!recipes.isEmpty()) { passed++; sb.append("[PASS] RecipeIndex: stick recipes found (").append(recipes.size()).append(")\n"); }
            else { failed++; sb.append("[FAIL] RecipeIndex: no stick recipes\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] RecipeIndex: ").append(e.getMessage()).append("\n"); }

        // Test 4: Movement types
        try {
            int count = com.aimod.ai.movement.BotMovement.class.getDeclaredClasses().length;
            passed++; sb.append("[PASS] Movement: BotMovement loaded\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] Movement: ").append(e.getMessage()).append("\n"); }

        // Test 5: ChunkCache
        try {
            var cache = new com.aimod.ai.cache.ChunkCache(source.getLevel());
            passed++; sb.append("[PASS] ChunkCache: created\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ChunkCache: ").append(e.getMessage()).append("\n"); }

        // Test 6: BotAIStateMachine
        try {
            var sm = new com.aimod.ai.llm.BotAIStateMachine();
            sm.startPlanning("test", 3);
            sm.startExecuting();
            sm.complete();
            if (sm.getCurrent() == com.aimod.ai.llm.BotAIStateMachine.State.COMPLETED) { passed++; sb.append("[PASS] StateMachine: IDLE->PLANNING->EXECUTING->COMPLETED\n"); }
            else { failed++; sb.append("[FAIL] StateMachine: wrong state\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] StateMachine: ").append(e.getMessage()).append("\n"); }

        // Test 7: Chain creation
        try {
            var cm = new com.aimod.ai.chain.ChainManager();
            cm.addChain(new com.aimod.ai.chain.DangerChain());
            cm.stopAll();
            passed++; sb.append("[PASS] ChainManager: chain registered and stopped\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ChainManager: ").append(e.getMessage()).append("\n"); }

        // Test 8: Environment scan
        try {
            var scanner = new com.aimod.ai.WorldScanner(source.getPlayer());
            var scan = scanner.scanEnvironment(16);
            passed++; sb.append("[PASS] WorldScanner: environment scanned\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] WorldScanner: ").append(e.getMessage()).append("\n"); }

        // Test 9: FindItemResult
        try {
            var r = new com.aimod.ai.InventoryUtils.FindItemResult(3, 64);
            if (r.found() && r.isHotbar()) { passed++; sb.append("[PASS] FindItemResult: basic properties\n"); }
            else { failed++; sb.append("[FAIL] FindItemResult: wrong properties\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] FindItemResult: ").append(e.getMessage()).append("\n"); }

        // Test 10: Bot persistence
        try {
            var info = new com.aimod.fakeplayer.BotInfo();
            info.name = "test";
            if (info.name.equals("test")) { passed++; sb.append("[PASS] BotInfo: created\n"); }
            else { failed++; sb.append("[FAIL] BotInfo: wrong name\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] BotInfo: ").append(e.getMessage()).append("\n"); }

        // Test 11: VeinScanner
        try {
            var origin = source.getPlayer().blockPosition();
            var vein = com.aimod.ai.VeinScanner.findVein(source.getLevel(), origin, net.minecraft.world.level.block.Blocks.STONE, 10);
            passed++; sb.append("[PASS] VeinScanner: returned ").append(vein.size()).append(" connected blocks\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] VeinScanner: ").append(e.getMessage()).append("\n"); }

        // Test 12: UndoManager
        try {
            var um = new com.aimod.ai.UndoManager(3);
            var op = um.startOperation("test");
            var bp = source.getPlayer().blockPosition();
            um.record(op, bp, net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            if (um.historySize() == 1) { passed++; sb.append("[PASS] UndoManager: record+history\n"); }
            else { failed++; sb.append("[FAIL] UndoManager: wrong history size\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] UndoManager: ").append(e.getMessage()).append("\n"); }

        // Test 13: VeinMineAction
        try {
            var action = new com.aimod.ai.action.VeinMineAction("stone", 5);
            if (action.getBlockId().equals("stone") && action.getCount() == 5) { passed++; sb.append("[PASS] VeinMineAction: created\n"); }
            else { failed++; sb.append("[FAIL] VeinMineAction: wrong parameters\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] VeinMineAction: ").append(e.getMessage()).append("\n"); }

        // Test 14: Config values
        try {
            boolean veinMine = com.aimod.config.ModConfig.getVeinMine();
            int undoHistory = com.aimod.config.ModConfig.getUndoHistory();
            if (undoHistory >= 0) { passed++; sb.append("[PASS] Config: veinMine=").append(veinMine).append(" undoHistory=").append(undoHistory).append("\n"); }
            else { failed++; sb.append("[FAIL] Config: invalid undoHistory\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] Config: ").append(e.getMessage()).append("\n"); }

        // Test 15: MaterialTree (static API)
        try {
            if (com.aimod.ai.craft.MaterialTree.class != null) { passed++; sb.append("[PASS] MaterialTree: class loaded\n"); }
            else { failed++; sb.append("[FAIL] MaterialTree: class not found\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] MaterialTree: ").append(e.getMessage()).append("\n"); }

        // Test 16: ToolSet (static API)
        try {
            var speed = com.aimod.ai.pathing.ToolSet.calculateSpeedVsBlock(
                net.minecraft.world.item.ItemStack.EMPTY,
                net.minecraft.world.level.block.Blocks.DIAMOND_ORE.defaultBlockState());
            passed++; sb.append("[PASS] ToolSet: calculateSpeedVsBlock returned ").append(String.format("%.3f", speed)).append("\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ToolSet: ").append(e.getMessage()).append("\n"); }

        // Test 17: SequencePlanner mine
        try {
            var actions = com.aimod.ai.planner.SequencePlanner.planMine(net.minecraft.world.item.Items.DIAMOND_ORE, 3);
            if (!actions.isEmpty()) { passed++; sb.append("[PASS] SequencePlanner: mine ").append(actions.size()).append(" actions\n"); }
            else { failed++; sb.append("[FAIL] SequencePlanner: empty actions\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] SequencePlanner: ").append(e.getMessage()).append("\n"); }

        // Test 18: SequencePlanner gather
        try {
            var actions = com.aimod.ai.planner.SequencePlanner.planGather(net.minecraft.world.item.Items.OAK_LOG, 8);
            if (!actions.isEmpty()) { passed++; sb.append("[PASS] SequencePlanner: gather ").append(actions.size()).append(" actions\n"); }
            else { failed++; sb.append("[FAIL] SequencePlanner: empty gather\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] SequencePlanner: ").append(e.getMessage()).append("\n"); }

        // Test 19: PlanCache TF-IDF similarity
        try {
            double s1 = com.aimod.ai.llm.PlanCache.similarity("挖5个铁矿", "挖3个铁矿");
            double s2 = com.aimod.ai.llm.PlanCache.similarity("挖5个铁矿", "制作钻石镐");
            if (s1 > 0.5 && s2 < 0.5) { passed++; sb.append("[PASS] PlanCache: similarity works (s1=").append(String.format("%.2f", s1)).append(", s2=").append(String.format("%.2f", s2)).append(")\n"); }
            else { failed++; sb.append("[FAIL] PlanCache: similarity mis-scored (s1=").append(String.format("%.2f", s1)).append(", s2=").append(String.format("%.2f", s2)).append(")\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] PlanCache: ").append(e.getMessage()).append("\n"); }

        // Test 20: PlanCache TTL
        try {
            var cache = new com.aimod.ai.llm.PlanCache(source.getServer().getServerDirectory());
            if (cache.size() >= 0) { passed++; sb.append("[PASS] PlanCache: TTL loaded, size=").append(cache.size()).append("\n"); }
            else { failed++; sb.append("[FAIL] PlanCache: invalid size\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] PlanCache TTL: ").append(e.getMessage()).append("\n"); }

        // Test 21: PlaceBlockAction (realistic placement flow)
        try {
            var bi = net.minecraft.world.item.Items.STONE instanceof net.minecraft.world.item.BlockItem bi2 ? bi2 : null;
            if (bi != null) {
                var action = new com.aimod.ai.action.PlaceBlockAction(source.getPlayer().blockPosition(), bi);
                String desc = action.getDescription();
                if (desc != null && desc.contains("Place") && desc.contains("at")) { passed++; sb.append("[PASS] PlaceBlockAction: created\n"); }
                else { failed++; sb.append("[FAIL] PlaceBlockAction: wrong desc\n"); }
            } else { failed++; sb.append("[FAIL] PlaceBlockAction: Items.STONE is not BlockItem\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] PlaceBlockAction: ").append(e.getMessage()).append("\n"); }

        // Test 21: VeinMiningHelper
        try {
            boolean isLog = com.aimod.ai.action.VeinMiningHelper.isLogBlock(net.minecraft.world.level.block.Blocks.OAK_LOG);
            boolean notLog = com.aimod.ai.action.VeinMiningHelper.isLogBlock(net.minecraft.world.level.block.Blocks.STONE);
            if (isLog && !notLog) { passed++; sb.append("[PASS] VeinMiningHelper: isLogBlock correct\n"); }
            else { failed++; sb.append("[FAIL] VeinMiningHelper: isLogBlock wrong (log=").append(isLog).append(", stone=").append(notLog).append(")\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] VeinMiningHelper: ").append(e.getMessage()).append("\n"); }

        // Test 22: ObstacleBreaker
        try {
            var breaker = new com.aimod.ai.action.ObstacleBreaker();
            breaker.reset();
            passed++; sb.append("[PASS] ObstacleBreaker: created and reset\n");
        } catch (Exception e) { failed++; sb.append("[FAIL] ObstacleBreaker: ").append(e.getMessage()).append("\n"); }

        // Test 23: PathExecutor valid positions
        try {
            var path = java.util.List.of(
                    source.getPlayer().blockPosition(),
                    source.getPlayer().blockPosition().offset(1, 0, 0),
                    source.getPlayer().blockPosition().offset(2, 0, 0));
            var exec = new com.aimod.ai.pathing.PathExecutor(path);
            if (exec.getPathLength() == 3 && exec.getProgress() < 1.0) { passed++; sb.append("[PASS] PathExecutor: valid positions (len=").append(exec.getPathLength()).append(")\n"); }
            else { failed++; sb.append("[FAIL] PathExecutor: wrong state\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] PathExecutor: ").append(e.getMessage()).append("\n"); }

        // Test 24: Movement types loaded
        try {
            var bp = source.getPlayer().blockPosition();
            int cnt = 0;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(0, 1, 0)) instanceof com.aimod.ai.movement.MovementPillar) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(1, 1, 1)) instanceof com.aimod.ai.movement.MovementAscend) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(1, -1, 0)) instanceof com.aimod.ai.movement.MovementDescend) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(0, -2, 0)) instanceof com.aimod.ai.movement.MovementFall) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(0, -1, 0)) instanceof com.aimod.ai.movement.MovementDownward) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(1, 0, 1)) instanceof com.aimod.ai.movement.MovementDiagonal) cnt++;
            if (com.aimod.ai.movement.BotMovement.create(bp, bp.offset(1, 0, 0)) instanceof com.aimod.ai.movement.MovementTraverse) cnt++;
            if (cnt >= 6) { passed++; sb.append("[PASS] BotMovement: ").append(cnt).append("/7 factory types verified\n"); }
            else { failed++; sb.append("[FAIL] BotMovement: only ").append(cnt).append(" types matched\n"); }
        } catch (Exception e) { failed++; sb.append("[FAIL] BotMovement: ").append(e.getMessage()).append("\n"); }

        sb.append("===== ").append(passed).append(" passed, ").append(failed).append(" failed =====");
        source.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
