# AI Mod v2 — 深度功能分析

> 基于 `analysis/9-reference-projects` 分支，101 源文件，对比 10 参考项目。

---

## 一、总体评价

AI Mod 是**功能完整的 Minecraft AI 机器人原型**，核心亮点是 **LLM + 本地双层规划器** 与 **Baritone 风格 Movement 系统** 的结合。

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★☆ | 双层实体 + 双层规划器 |
| 移动系统 | ★★★★☆ | 8 Movement + A\*，接近 Baritone 80% |
| AI/规划 | ★★★★★ | LLM+本地+缓存，最强规划能力 |
| 生存系统 | ★★★☆☆ | 4 链覆盖主要场景 |
| 命令系统 | ★★★★★ | 27 条 + Tab + i18n |
| 代码质量 | ★★★☆☆ | 部分文件过长，重复逻辑 |
| 测试覆盖 | ★★★☆☆ | 129 单元 + 18 GameTest |

---

## 二、各模块对比

### 2.1 移动系统 (8 Movement)

| 特性 | Baritone | AI Mod |
|------|----------|--------|
| Movement 类型 | 8 | 8 ✅ |
| 状态机 | PREPPING→WAITING→RUNNING→SUCCESS | PENDING→PREPPING→RUNNING→COMPLETE ✅ |
| 输入方式 | `Input.JUMP` 模拟 | `setDeltaMovement` + `move()` |
| 跳跃对齐 | `flatDistToNext≤1.2` + `lateral≤0.1` | `dy>0.3 && dy≤1.5` ⚠️ |
| 搭桥 | SNEAK + CLICK_RIGHT | PlaceBlockAction 直接 setBlock ⚠️ |
| 路径预计算 | next PathExecutor | 无 ❌ |
| 路径拼接 | trySplice | 无 ❌ |
| 世界缓存 | CachedWorld (512×512) | ChunkCache (2048) ✅ |

**优点**: 8 Movement 完整对齐。**缺点**: 跳跃对齐不如 Baritone 精确，搭桥无 SNEAK。

### 2.2 行为链 (4 Chains)

| Chain | Player2NPC | AI Mod |
|-------|-----------|--------|
| 用户任务 | UserTaskChain(P50) | Task直接执行 ✅ |
| 怪物防御 | MobDefenseChain(P65-80) | DefenseChain(P70) ✅ 简化版 |
| 玩家防御 | PlayerDefenseChain(P55) | 无 ❌ |
| 自动进食 | FoodChain(P55) | FoodChain(P55) ✅ |
| 水桶防摔 | MLGBucketFallChain(P100) | 集成DangerChain ✅ |
| 卡住自救 | UnstuckChain(P65) | UnstuckChain(P50) ✅ +PILLAR |
| 世界生存 | WorldSurvivalChain(P60-100) | 集成DangerChain ✅ |
| 预装备 | PreEquipItemChain | 无 ⚠️ |

**优点**: 4 链覆盖主要场景，PILLAR 策略超越原始实现。**缺点**: 缺少玩家防御、链间协同。

### 2.3 规划器 (LLM + Local)

**核心优势**: 双层规划器业界独有。PlanCache 复用。MaterialTree 反向链接。

**缺点**: 增量规划仅失败时触发。`findBlockForItem` 映射不完整。

### 2.4 命令系统 (27 条)

超越 SiliconeDolls（15 条）。Tab 补全、i18n、撤销、路径可视化均超越参考。

**缺点**: `BotCommand.java` 1077 行过长。

---

## 三、优缺点

### 核心优势
1. 双层规划器（业界独有）
2. 完整 Movement（8 种类型对齐 Baritone）
3. 行为链（4 链 + PILLAR）
4. 命令系统（27 条 + 双语）
5. 工具链（连锁/撤销/路径/测试）

### 主要不足
1. 跳跃对齐不如 Baritone 精确
2. 搭桥无 SNEAK 保护
3. 链系统缺 PlayerDefense
4. 增量规划非真正的"每步增量"
5. BotCommand 文件过长

---

## 四、改进优先级

| 优先级 | 改进 | 影响 |
|--------|------|------|
| P0 | 移动跳跃对齐检测 | bot 不原地跳 |
| P1 | GatherResource 集成 ToolSet | 采集效率 |
| P1 | BotCommand 拆分 | 可维护性 |
| P2 | PlayerDefenseChain | 完整防御 |
| P2 | PlanCache 相似度优化 | 缓存命中率 |
| P3 | 路径预计算 | 长距离导航 |
| P3 | 皮肤系统 | 视觉效果 |

---

## 五、参考项目相似度

| 参考项目 | 参考深度 | 实现完整度 |
|---------|---------|-----------|
| Baritone | ★★★★★ | ★★★★☆ (80%) |
| Player2NPC | ★★★★☆ | ★★★☆☆ (4/9链) |
| SiliconeDolls | ★★★★☆ | ★★★★☆ |
| AI-Player | ★★★☆☆ | ★★☆☆☆ |
| Meteor | ★★★☆☆ | ★★★☆☆ |
| EMI/JEI | ★★★★☆ | ★★★★☆ |

---

*分析日期: 2026-05-25 | 版本: 1.0.43-r54*

---

## P2 实施记录 (2026-05-29)

### p2a: 地下方块不可达 + LLM JSON 格式健壮性

**地下方块不可达修复:**
- `GatherResourceAction.findBestStandPos()` dy 范围: -2~+3 → -10~+3
- `findAdjacentStandPos()` 增加向下搜索 1-3 格
- 可达检查: dy >= -1.5 → -3.0 (允许站在上方挖矿)
- `MAX_FALL_BLOCKS`: 4 → 10 (支持更深的地下寻路)

**LLM JSON 格式健壮性:**
- 自动修正 shorthand 格式: `{"mine": "iron_ore"}` → `{"type": "mine", "block_type": "iron_ore"}`
- 支持所有已知 action type 的自动识别和修正
- 日志记录修正行为 (`PLAN_ACTION_FIX_FORMAT`)

### p2b: BotAIManager 职责分离

BotAIManager.java (800行) 拆分为 4 个文件:

| 类 | 行数 | 职责 |
|----|------|------|
| `TaskPlanner.java` | ~350 | LLM 调用、响应解析、PlanCache 交互、JSON 辅助方法 |
| `TaskExecutor.java` | ~120 | 任务执行循环、状态转换、内存压缩 |
| `TaskReplanner.java` | ~180 | 增量重规划、缺口重规划、failedAttempts 管理 |
| `BotAIManager.java` | ~100 | 协调器，保持原有公共 API |

### p2c: BotCommand 拆分

BotCommand.java (1257行) 拆分为 8 个文件:

| 类 | 职责 |
|----|------|
| `SubCommand.java` | 子命令注册接口 |
| `BotCommandTask.java` | task, task_all, stop, cancel, pause, resume, status |
| `BotCommandAction.java` | goto, mine, vein, follow, gather, craft, say, give, equip |
| `BotCommandAdmin.java` | spawn, select, remove, save, load, list, delete, inventory |
| `BotCommandConfig.java` | toggle, config, veinmine, showpath |
| `BotCommandTestCmd.java` | test (25 子系统测试) |
| `BotCommandHelp.java` | help |
| `BotCommand.java` | ~150 行协调器 + 共享辅助方法 |

*更新日期: 2026-05-29 | 版本: 1.0.63-p2c*

---

## P3 实施记录 (2026-05-29)

### p3a: 测试覆盖扩展

新增测试文件:

| 文件 | 覆盖目标 |
|------|----------|
| `ActionFactoryTest.java` | Action JSON 解析 (move_to, mine, craft, equip, say, aliases, position flattening) |
| `NameTagFormatterTest.java` | BotAIStateMachine 状态转换、计数器、重置 |
| `InventoryUtilsTest.java` | FindItemResult 属性 (found, isHotbar, slot, count) |

测试总计: 18 个文件, 150+ 测试方法。

*更新日期: 2026-05-29 | 版本: 1.0.64-p3a*

### p3b: 多模型路由 (CHEAP/PREMIUM)

**设计方案:**
- 新增 `CHEAP_MODEL_NAME` 配置 — 用于 incremental replan 等简单任务
- `PREMIUM_MODEL_NAME` 使用原有的 `MODEL_NAME` — 用于完整任务规划

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `ModConfig.java` | 新增 `CHEAP_MODEL_NAME` 配置项和 getter/setter |
| `LLMService.java` | 新增 `sendPromptWithModel()` 方法支持模型名覆盖 |
| `TaskReplanner.java` | incremental replan 使用 cheap model |
| `BotCommandConfig.java` | 配置命令支持 cheapModelName 显示和设置 |

**路由逻辑:**
- `TaskPlanner.parseCommand()` → 使用 PREMIUM 模型 (MODEL_NAME)
- `TaskReplanner.incrementalReplan()` → 使用 CHEAP 模型 (CHEAP_MODEL_NAME)
- 默认 CHEAP_MODEL_NAME 为空，回退到 MODEL_NAME

*更新日期: 2026-05-30 | 版本: 1.0.66-p3b*

### p3c: BotMetrics 可观测性

**设计方案:**

新建 `BotMetrics.java` — 线程安全的指标收集器：

| 指标类型 | 计数器 |
|----------|--------|
| LLM 调用 | llmCalls, llmSuccesses, llmFailures, llmTotalMs, llmMaxMs |
| 任务 | tasksStarted, tasksCompleted, tasksFailed |
| 动作 | actionsExecuted, actionsSucceeded, actionsFailed |
| 重规划 | replansTriggered, replansSucceeded |

**集成点:**
| 文件 | 记录内容 |
|------|----------|
| `TaskPlanner.java` | LLM 调用成功/失败 + 耗时 |
| `TaskExecutor.java` | 动作成功/失败、任务完成 |
| `TaskReplanner.java` | 重规划触发/成功、LLM 调用 |

**命令:**
- `/ai_bot metrics` — 显示当前 bot 的指标摘要
- `/ai_bot metrics <name>` — 显示指定 bot 的指标

*更新日期: 2026-05-30 | 版本: 1.0.67-p3c*

---

## P4 实施记录 (2026-05-30)

### p4a: 测试覆盖扩展（第二轮）

新增测试文件:

| 文件 | 覆盖目标 |
|------|----------|
| `BotMemoryStoreTest.java` | 工作内存、资源位置、chunk 探索、统计、清除、ResourceLocation 数据类 |
| `ContextAssemblerTest.java` | Token 转换（charsToTokens/tokensToChars）、estimateTotalTokens、assemble 方法 |
| `TaskPersistenceTest.java` | TaskData 序列化/反序列化、文件操作、JSON 读写 |

测试总计: 21 个文件, 170+ 测试方法。

*更新日期: 2026-05-30 | 版本: 1.0.68-p4a*

### p4b: TaskPersistence 健壮性

**改进内容:**

| 改进项 | 说明 |
|--------|------|
| 版本字段 | TaskData 新增 `version` 字段 (CURRENT_VERSION=2)，支持向前兼容 |
| 原子写入 | 写入临时文件 `.json.tmp`，然后原子重命名，防止写入中断导致损坏 |
| 损坏文件备份 | JSON 解析失败时，将损坏文件备份到 `backup/` 目录，然后删除原文件 |
| 空文件检测 | 检测空文件并清理 |
| 版本不兼容处理 | 文件版本 > 当前版本时，删除并返回 null |
| Action 索引验证 | 验证 `currentActionIndex` 在有效范围内，无效时重置为 0 |
| 空命令处理 | `data.command` 为 null 时使用默认值 |

*更新日期: 2026-05-30 | 版本: 1.0.69-p4b*

### p4c: MovementDigDown — 挖洞下探

**设计方案:**

新建 `MovementDigDown.java` — 多格垂直挖掘下降移动类。

| 特性 | 说明 |
|------|------|
| 触发条件 | `dy <= -2, adx+adz == 0`（垂直向下 2+ 格） |
| 工作流程 | 重复：挖脚下方块 → 等待下落 → 到达目标深度 |
| 安全检查 | 不破坏基岩、不在液体上方挖、不在虚空下方挖 |
| 成本计算 | 每格: BREAK_BASE + 硬度 * 1.5 + WALK_ONE_BLOCK |
| 超时保护 | 单格 40 tick，总超时 dy * 50 tick |

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `MovementDigDown.java` | 新建，多格挖掘下降类 |
| `BotMovement.java` | 工厂方法增加 `MovementDigDown` 分支 (dy<=-2, adx+adz=0) |

**与现有移动类型的关系:**
- `MovementDownward`: dy=-1, 1格挖掘下降（保留不变）
- `MovementDigDown`: dy<=-2, 多格挖掘下降（新建）
- `MovementFall`: dy<-1, 需要现成空间的下落（保留不变）

*更新日期: 2026-05-30 | 版本: 1.0.70-p4c*

---

## P5 实施记录 (2026-05-30)

### p5a: GatherResourceAction 挖洞下探集成

**设计方案:**

在 `GatherResourceAction` 中增加 Strategy 4（tryDigDown），当目标在 bot 下方且无法通过走路到达时，自动挖洞下去。

**工作流程:**
1. 检查目标是否在 bot 下方 (dy < -1)
2. 检查脚下方块是否可破坏（非基岩、非液体、Y >= -64）
3. 调用 `level.destroyBlock()` 破坏脚下方块
4. 等待重力将 bot 拉下
5. 重复直到到达目标深度

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `GatherResourceAction.java` | 增加 Strategy 4 + `tryDigDown()` 方法 |

*更新日期: 2026-05-30 | 版本: 1.0.71-p5a*

### p5b: 失败任务详细反馈

**设计方案:**

在 `Action` 基类中增加 `failReason` 字段，各 Action 在 `canExecute()` 失败时设置具体原因。

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `Action.java` | 增加 `failReason` 字段 + getter/setter |
| `TaskPlanner.java` | `getActionFailReason()` 优先使用 `action.getFailReason()` |
| `EquipItemAction.java` | 设置具体原因："未知物品" / "背包中没有 xxx" |
| `CraftAction.java` | 设置具体原因："未知物品" / "没有合成配方" / "缺少材料: xxx" |
| `PlaceBlockAction.java` | 设置具体原因："目标位置被占用" / "背包中没有 xxx" |
| `BreakBlockAction.java` | 设置具体原因："目标位置已经是空气" / "方块不可破坏" |

**效果:** 任务失败时用户能看到具体原因，而不是笼统的 "Action failed"。

*更新日期: 2026-05-30 | 版本: 1.0.72-p5b*

### p5c: PlayerDefenseChain — PvP 防御

**设计方案:**

新建 `PlayerDefenseChain.java` — PvP 防御行为链。

| 特性 | 说明 |
|------|------|
| 优先级 | 65（低于 DefenseChain 的 70） |
| 检测范围 | 16 格内的非主人玩家 |
| 触发条件 | 玩家持有武器且距离 < 6 格，或距离 < 3 格 |
| 防御行为 | 逃跑（远离威胁）+ 举盾（如果副手有盾） |
| 最大活跃时间 | 100 tick（5 秒） |
| 冷却时间 | 60 tick（3 秒） |
| 默认禁用 | 通过 `enablePvpDefense` 配置启用 |

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `PlayerDefenseChain.java` | 新建，PvP 防御行为链 |
| `FakePlayer.java` | 注册 PlayerDefenseChain 到 ChainManager |
| `ModConfig.java` | 新增 `enablePvpDefense` 配置项 |

*更新日期: 2026-05-30 | 版本: 1.0.73-p5c*

---

## P6 实施记录 (2026-05-30)

### p6a: MovementDigDown 工具验证

**改进内容:**

| 改进项 | 说明 |
|--------|------|
| 镐检测 | `canExecute()` 检查背包中是否有镐，无镐时警告 |
| 工具效率 | `update()` 使用 `getToolSpeed()` 计算挖掘时间，镐比裸手快 20x |
| 挖掘时间 | `breakTime = hardness * 20 / toolSpeed`（原来固定 `hardness * 20`） |

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `MovementDigDown.java` | 增加 `getToolSpeed()`, `hasPickaxe()`, 工具效率计算 |

*更新日期: 2026-05-30 | 版本: 1.0.74-p6a*

### p6b: VeinMiningHelper 矿石连锁

**改进内容:**

| 改进项 | 说明 |
|--------|------|
| `veinMineOre()` | 新增矿石连锁方法，使用 `VeinScanner.findVein()` (6方向 BFS) |
| `isOreBlock()` | 新增矿石检测，支持 18 种矿石方块（含深板岩变种） |
| `breakTarget()` | 矿石类型触发连锁采集（当 `veinMine` 配置启用时） |

**支持的矿石:**
- 煤矿/深板层煤矿、铁矿/深板层铁矿、金矿/深板层金矿
- 钻石矿/深板层钻石矿、绿宝石矿/深板层绿宝石矿
- 红石矿/深板层红石矿、青金石矿/深板层青金石矿
- 铜矿/深板层铜矿、下界金矿、下界石英矿

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `VeinMiningHelper.java` | 增加 `veinMineOre()`, `isOreBlock()` |
| `GatherResourceAction.java` | `breakTarget()` 中矿石类型触发连锁 |

*更新日期: 2026-05-30 | 版本: 1.0.75-p6b*

### p6c: ChainManager 配置化注册

**改进内容:**

| 改进项 | 说明 |
|--------|------|
| 配置选项 | 新增 `enableDangerChain`, `enableDefenseChain`, `enableFoodChain`, `enableUnstuckChain` |
| 条件注册 | `FakePlayer` 构造函数根据配置条件注册链 |
| 默认值 | 所有链默认启用（true），PvP 防御链默认禁用（false） |

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `ModConfig.java` | 新增 4 个链启用配置 + getter/setter |
| `FakePlayer.java` | 条件注册链（`if (config.getEnable...())`） |

*更新日期: 2026-05-30 | 版本: 1.0.76-p6c*

---

## 代码审计修复 (2026-05-30)

### r77: 审计发现的 Critical/High 级别修复

| # | 严重度 | 文件 | 修复内容 |
|---|--------|------|----------|
| 1 | Critical | `LLMService.java` | `sendPromptWithModel()` 不再修改共享 `this.model`，改为传递参数到调用链 |
| 2 | High | `FakePlayer.java` | `parseCommand()` 返回 null 时重置状态机回 IDLE |
| 3 | High | `DefenseChain.java` | 战斗结束/超时/停止时调用 `stopUsingItem()` 放下盾牌 |

*更新日期: 2026-05-30 | 版本: 1.0.77-r77*

### r78: 审计 High 级别修复

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `TaskReplanner.java` | `incrReplanCount`/`consecutiveUnknown` 改为 AtomicInteger；`recentReplanAttempts` 改为 synchronizedList |
| 2 | `DangerChain.java` | 逃逸目标搜索从 Y+0 扩展到 Y+0~3，支持地下岩浆逃生 |
| 3 | `PlayerDefenseChain.java` | 武器检测从类名字符串匹配改为 instanceof 检查 |

*更新日期: 2026-05-30 | 版本: 1.0.78-r78*

### r79: 审计 High 级别修复（第二批）

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `MovementController.java` | `pathExecutor`/`nextPathExecutor`/`directMovement`/`activeMovement` 改为 volatile |
| 2 | `GatherResourceAction.java` | `breakTarget()` 从墙钟时间改为 tick 计数 |
| 3 | `MovementFall.java` | `update()` 增加运行时安全检查（岩浆/虚空） |
| 4 | `GatherResourceAction.java` | `tryPillarUp()` 增加窒息检查（头部固体方块检测） |

*更新日期: 2026-05-30 | 版本: 1.0.79-r79*

### r80: 审计 Medium 级别修复

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `TaskPlanner.java` | 返回失败 Task 替代 null |
| 2 | `GatherResourceAction.java` | 水中逃生 5 秒超时保护 |
| 3 | `PlaceBlockAction.java` | `findBlockSlot()` 物品在 armor/offhand 时交换到 hotbar |
| 4 | `VeinMiningHelper.java` | 传递 bot 实体到 `destroyBlock` 以获取正确战利品表 |
| 5 | `FakePlayer.java` | `tick()` 捕获所有 Exception（原来只捕获 NPE） |

*更新日期: 2026-05-30 | 版本: 1.0.80-r80*

### r81: 审计 Medium 级别修复（第二批）

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `ContextAssembler.java` | replan 历史追加时检查 token 预算，超出则截断 |
| 2 | `CraftAction.java` | 输出数量限制为最大堆叠大小 |
| 3 | `MovementDigDown.java` | `getToolSpeed()` 只考虑镐（原来任何 TieredItem） |
| 4 | `PlaceBlockAction.java` | `findNearbyAir()` 返回 bot 位置替代已占用的 targetPos |
| 5 | `PlanCache.java` | 所有公共方法加 synchronized 保证线程安全 |

*更新日期: 2026-05-30 | 版本: 1.0.81-r81*

### r82: MineBlockAction 挖洞下探

**问题**：Bot 无法到达地下的矿石（铁矿等），反复卡在地表触发 UNSTUCK_ESCALATE。

**修复**：在 `MineBlockAction.followPath()` 中添加 `tryDigDown()` 策略：
- 当寻路失败且目标在下方 (dy < -2) 时，破坏脚下方块创建阶梯
- 包含安全检查（虚空 Y<-64、基岩、液体）
- 每 5 tick 冷却等待重力生效

*更新日期: 2026-06-05 | 版本: 1.0.82-r82*

### r83: P7 任务 — BotMetrics 完善 + 代码清理

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `FakePlayer.java` | `assignTask()` 调用 `metrics.recordTaskStarted()` |
| 2 | `FakePlayer.java` | `cancelTask()` 调用 `metrics.recordTaskFailed()` |
| 3 | `TaskPlanner.java` | 解析失败时调用 `metrics.recordTaskFailed()` |
| 4 | `TaskReplanner.java` | 3 个失败路径调用 `metrics.recordTaskFailed()` |
| 5 | `VeinMiningHelper.java` | 矿脉截断时输出警告日志 |
| 6 | `MovementFall.java` | `fallDistance` 在构造函数中计算 |
| 7 | `TaskReplanner.java` | 移除重复的 `consecutiveUnknown.set(0)` |

*更新日期: 2026-06-05 | 版本: 1.0.83-r83*

### r84: WorldScanner 性能优化 + ModConfig 交叉验证

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `FakePlayer.java` | `WorldObservation.from()` 调用频率从每秒降至每 3 秒 |
| 2 | `ModConfig.java` | `getCompactTriggerTokens()` 自动限制为 `maxContextTokens - 512` |

*更新日期: 2026-06-05 | 版本: 1.0.84-r84*

### r85: DangerChain 自动重试保护

**问题**：危险解除后，DangerChain 自动重新分配之前保存的任务，但不检查 bot 是否已经有新任务。

**修复**：在重新分配前检查 `bot.hasActiveTask()`，如果有则跳过重试。

*更新日期: 2026-06-05 | 版本: 1.0.85-r85*

### r86: BotMetrics 持久化

**功能**：指标数据保存到 JSON 文件，重启后不丢失。

**实现**:
- `BotMetrics.save(Path)` — 序列化为 JSON 保存
- `BotMetrics.load(Path)` — 从 JSON 文件加载
- `FakePlayer.saveMetrics()` — 暴露保存方法
- 每 5 分钟自动保存（6000 ticks）
- bot 被 kill 时自动保存
- 文件路径: `config/aimod/metrics/<uuid>.json`

*更新日期: 2026-06-11 | 版本: 1.0.86-r86*

### r87: PlaceBlockAction 优化

**问题**：当没有固体相邻方块时，`findPlaceableFace` 返回一个朝向 bot 的方向作为 fallback，导致 `useItemOn` 不必要地失败。

**修复**：`findPlaceableFace` 返回 `null` 时直接使用 `fallbackSetBlock`，跳过 `useItemOn` 调用。

*更新日期: 2026-06-11 | 版本: 1.0.87-r87*

### r88: MovementStepUp — 修复缺失的移动类型

**问题**：`BotMovement.create()` 缺少 `dy == 1, adx + adz == 1` 的情况（向上一步 + 水平一步）。这种情况会错误地创建 `MovementTraverse`（仅水平移动），导致 bot 无法正确跳上台阶。

**修复**：
- 新建 `MovementStepUp.java` — 处理 `dy=1, adx+adz=1` 的移动
- 更新 `BotMovement.create()` 工厂方法添加此分支
- 与 `MovementAscend`（对角线上升，dx=1,dz=1）区分

*更新日期: 2026-06-11 | 版本: 1.0.88-r88*

### r89: GatherResourceAction ToolSet 集成

**问题**：`breakTarget()` 使用 `hardness * 20` 计算挖掘时间，不考虑工具速度、附魔、药水效果。

**修复**：
- 使用 `ToolSet.getBestSlot()` 自动选择最佳工具
- 使用 `ToolSet.getBreakTicks()` 计算精确挖掘时间（含工具速度、效率附魔、急迫/挖掘疲劳、水下惩罚、离地惩罚）

*更新日期: 2026-06-11 | 版本: 1.0.89-r89*

### r90: 测试清理

**说明**：删除了依赖 Minecraft 运行时的测试文件（VeinMiningHelperTest、MovementDigDownTest、MovementStepUpTest）。这些测试无法在单元测试环境中运行，因为依赖 `Block`、`BlockPos` 等 Minecraft 类。

*更新日期: 2026-06-11 | 版本: 1.0.90-r90*

### r91: 审计 Medium 修复 — Task.injectAction 大小限制

**问题**：`Task.injectAction()` 无大小限制，反复 replan 时 action 列表可能无限增长。

**修复**：添加 `MAX_ACTIONS = 100` 限制，超出时记录警告并拒绝注入。

*更新日期: 2026-06-11 | 版本: 1.0.91-r91*

### r92: BreakBlockAction ToolSet 集成 + 路径可视化改进

**BreakBlockAction ToolSet 集成：**
- `BreakBlockAction.execute()` 现在使用 `ToolSet.getBestSlot()` 自动选择最佳工具
- 使用 `ToolSet.getBreakTicks()` 计算精确挖掘时间（含工具速度、附魔、药水效果）
- 与 `GatherResourceAction.breakTarget()` 保持一致

**showPath 可视化改进：**
- 目标位置：红色粒子标记
- 计算路径：紫色粒子 + 进度显示 (x/y, 百分比)
- 无路径时：黄色直线连接 bot 和目标 + 距离显示

*更新日期: 2026-06-11 | 版本: 1.0.92-r92*

### r93: LLMService 健康检查缓存竞态修复

**问题**：`HEALTH_CHECK_CACHE` 是单个 `AtomicReference<HealthCheckResult>`，被所有模型共享。两个线程同时检查不同模型时，后写入的会覆盖前一个的结果。

**修复**：
- `HEALTH_CHECK_CACHE` 从 `AtomicReference` 改为 `ConcurrentHashMap<String, HealthCheckResult>`
- Key 为 `apiUrl + "|" + model`，每个模型独立缓存
- 合并两个 `isModelAvailable()` 方法为一个

*更新日期: 2026-06-11 | 版本: 1.0.93-r93*

### r94: SSE 流式响应修复

**问题**：SSE 流式响应使用 `BodyHandlers.ofString()` 读取整个响应体到内存，无实际流式效果。

**修复**：
- 当 `streamResponses=true` 时，使用 `BodyHandlers.ofInputStream()` 流式读取
- 新增 `readSSEStreamFromInputStream()` 方法，逐行读取 InputStream
- 保持 `readSSEStream(String)` 用于回退场景

*更新日期: 2026-06-11 | 版本: 1.0.94-r94*

### r95: Bot 皮肤系统

**功能**：Bot 皮肤系统实现，`botSkinUrl` 配置现在生效。

**实现**：
- 新建 `BotSkinManager.java` — 皮肤下载和应用
- `applySkinFromUrl()` — 从直接 PNG URL 应用皮肤
- `applySkinFromUsername()` — 从 Mojang 用户名查找并应用皮肤
- `applyConfiguredSkin()` — 读取 `botSkinUrl` 配置
- 集成到 `FakePlayer.createAndRegister()` — 创建时自动应用皮肤

**使用方式**：
```
[aimod-common.toml]
botSkinUrl = "https://example.com/skin.png"
```

*更新日期: 2026-06-11 | 版本: 1.0.95-r95*

### r96: 配置 Bug 修复 + 字段遮蔽修复

| # | 文件 | 修复内容 |
|---|------|----------|
| 1 | `FakePlayerManager.java` | 使用 `ModConfig.getMaxBots()` 替代硬编码 `MAX_FAKE_PLAYERS=10` |
| 2 | `GatherResourceAction.java` | 移除遮蔽父类的 `failReason` 字段，使用 `setFailReason()` |
| 3 | `TaskPlanner.java` | 简化 `getActionFailReason()` — 移除 GatherResourceAction 特殊处理 |

*更新日期: 2026-06-11 | 版本: 1.0.96-r96*
