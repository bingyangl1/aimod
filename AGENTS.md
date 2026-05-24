# AGENTS.md — AI Mod 开发指南

## 项目约定

1. **编译 jar 必递增版本号**：`gradle.properties` 中 `mod_version`
2. **变更必更新文档 + commit**
3. **中文文本通过 i18n 文件**：`zh_cn.json` / `en_us.json`
4. **构建**：`./gradlew compileJava` / `jar` / `test`

## 架构

```
AIBotEntity(Mob) → FakePlayer(ServerPlayer) → BotAIManager
                    ├─ MovementController (8 Movement + A*)
                    ├─ ChainManager (Danger>Defense>Food>Unstuck)
                    ├─ PlanCache (LLM规划缓存)
                    └─ UndoManager (10次撤销)

规划器: LLM云端(2-10s) → 失败时 → 本地NLP+MaterialTree(0ms)
       成功规划 → PlanCache 持久化 → 二次复用
```

## 关键源文件 (101 files)

| 目录 | 核心文件 |
|------|---------|
| ai/action/ | Action基类, GatherResource(700行), VeinMine, PlaceBlock, Follow |
| ai/movement/ | BotMovement, MovementTraverse/Pillar/Ascend/..., MovementController, UnstuckDetector |
| ai/pathing/ | Pathfinder(A*), AsyncPathfinder, CalculationContext, ToolSet |
| ai/chain/ | ChainManager, DangerChain(P90), DefenseChain(P70), FoodChain(P55), UnstuckChain(P50) |
| ai/llm/ | LLMService, PlanCache, BotAIStateMachine |
| ai/planner/ | CommandParser(NLP), SequencePlanner(配方树) |
| fakeplayer/ | FakePlayer, FakePlayerManager, BotInfo, BotPersistence |
| command/ | BotCommand(27条), DirectCommandHandler |
| mixin/ | ConnectionMixin, PlayerListMixin, PacketDistributorMixin, ServerConfigMixin×2 |

## 移动系统

- 8 Movement类型由A*自动选择
- `travel()`保留XZ速度(travel覆盖清零→保留修复)
- STEP_HEIGHT=1.0 自动上一格台阶
- PlaceBlock降级链: 目标→下方→附近→脚下+跳

## 常见问题

- 跳不上台阶: 检查travel()、STEP_HEIGHT
- 增量规划循环: MAX_INCR_REPLAN=5
- LLM_ACTIONS=0: parseActionsFromContent是否接受单对象
