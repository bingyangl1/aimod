# AI Mod 项目进度文档

## 项目概况

**目标**：实现类似"Minecraft版Claude Code"的模组，通过自然语言下达任务，大模型解析、规划任务，假人玩家执行、实现。
**版本**：Minecraft 1.21.1 + NeoForge 21.1.176

**参考资源**：
- [SiliconeDolls](https://github.com/Anvil-Dev/SiliconeDolls)（假人实现参考）
- [Baritone](https://github.com/cabaletta/baritone/tree/1.21.1)（路径规划/挖矿）
- [RollingGate](https://github.com/Anvil-Dev/RollingGate)（配置风格）

---

## 当前进度总结

| 模块 | 状态 | 完成度 | 说明 |
|------|------|--------|------|
| 模组基础框架 | ✅ 完成 | 100% | 注册、配置、命令系统 |
| FakePlayer 系统 | ✅ 完成 | 95% | FakePlayer extends ServerPlayer，直接注册到服务器 |
| LLM 集成 | ✅ 功能完整 | 95% | OpenAI 兼容 API，流式支持，世界上下文，结构化提示词 |
| 动作系统 | ✅ 大部分完成 | 85% | 14 种动作，含挖矿/采集/交互/装备 |
| 世界感知 | ✅ 新增 | 80% | WorldScanner 支持方块/实体/玩家扫描 |
| 任务调度 | ✅ 改进 | 50% | 顺序执行 + 错误处理 + 状态反馈 |
| 合成系统 | ✅ 改进 | 60% | 使用 Minecraft 配方系统，支持工作台交互 |
| 任务反馈 | ✅ 完成 | 90% | 向玩家报告执行状态，支持进度报告 |
| 研发日志 | ✅ 完成 | 100% | DevLog 统一日志，关键 tag 全覆盖 |
| 路径寻找 | ✅ 重构完成 | 70% | Baritone 风格 A* 寻路 + Goal 系统 |
| 持久化 | ❌ 未实现 | 0% | 背包、任务不保存 |
| 测试 | ✅ 基础完成 | 20% | 17 个单元测试通过 |

**总体完成度：约 70%**

---

## 构建状态
**✅ BUILD SUCCESSFUL**（`.\gradlew.bat build --init-script init-local.gradle`）
最后验证：2026-05-23

### 构建环境备注
- 必须使用 `--init-script init-local.gradle`（本地依赖仓库，SSL 限制）
- Java 21 required（`gradle.properties` 中 `systemProp.java.home` 配置 JDK 路径）
- Gradle daemon 已禁用

---

## 最新更新 (2026-05-23)

### 本次会话：FakePlayer-first 架构重构 + Baritone 路径系统

**架构重大变更**：
- **FakePlayer IS the bot** — 直接继承 `ServerPlayer`，不再通过 `AIBotEntity` (PathfinderMob) 封装
- 移除 `entity/` 包（`AIBotEntity`, `ModEntities`）
- 移除客户端渲染器（FakePlayer 作为原版玩家渲染）
- 所有 14 个 Action 更新为直接使用 `FakePlayer`

**新增 Baritone 风格路径系统**：
- `BinaryHeapOpenSet` — 二叉堆优化 A* 开放列表
- `MovementHelper` — 移动辅助（方块可通行性检测）
- `PathExecutor` — 路径执行器（逐步移动）
- `ToolSet` — 工具选择优化（挖掘速度计算）
- `Goal` 系统 — `GoalBlock`, `GoalXZ`, `GoalYLevel`, `GoalComposite`

**构建修复**：
- MineBlockAction/GatherResourceAction 导航参数修复
- `isSolidRender()` 1.21.1 API 适配（需要 `BlockGetter, BlockPos`）
- `EnchantmentHelper.getItemEnchantmentLevel` 参数顺序修复
- UTF-8 编码问题修复
- `BlockPos` double-to-int 类型转换修复

**Git 提交记录**：
- `74210fe` — "refactor: FakePlayer-first architecture + Baritone pathing + build fixes"
- `cf29646` — "pre-refactor: checkpoint before FakePlayer-first architecture refactor"（可回退点）

---

## 架构图

```
┌──────────────────────────────────────────────────────────────┐
│                      用户交互层                                │
│   BotCommand │ ModConfig │ TaskFeedback │ DevLog              │
├──────────────────────────────────────────────────────────────┤
│                      业务逻辑层                                │
│   BotAIManager │ Task │ LLMService │ LLMResponse              │
│   WorldScanner │ InventoryUtils                               │
├──────────────────────────────────────────────────────────────┤
│                      核心功能层                                │
│   FakePlayer (ServerPlayer) │ FakePlayerManager               │
│   Actions (14 types) │ Pathing (A* + Goal)                    │
├──────────────────────────────────────────────────────────────┤
│                      基础设施层                                │
│   NeoForge API │ Minecraft Core │ FakePlayerNetHandler        │
│   FakeClientConnection │ FakeConnection                       │
└──────────────────────────────────────────────────────────────┘
```

---

## 文件清单

### 源文件（46 个）
**入口与配置**：
- `AIMod.java` — @Mod 入口，注册事件
- `config/ModConfig.java` — 配置定义（12 项）
- `command/BotCommand.java` — `/ai_bot` 命令树

**FakePlayer 系统**：
- `fakeplayer/FakePlayer.java` — 核心假人，extends ServerPlayer
- `fakeplayer/FakePlayerManager.java` — 假人生命周期管理
- `fakeplayer/FakePlayerNetHandler.java` — 伪网络处理器
- `fakeplayer/FakeClientConnection.java` — EmbeddedChannel 连接
- `fakeplayer/FakeConnection.java` — 兼容旧连接
- `fakeplayer/FakeNetHandler.java` — 兼容旧网络处理器

**AI 核心**：
- `ai/BotAIManager.java` — LLM 响应解析 → 动作序列
- `ai/Task.java` — 任务模型
- `ai/TaskFeedback.java` — 任务状态反馈
- `ai/WorldScanner.java` — 世界扫描
- `ai/InventoryUtils.java` — 库存操作

**动作系统（14 个）**：
- `ai/action/Action.java` — 基类
- `ai/action/MoveToAction.java` — 移动
- `ai/action/BreakBlockAction.java` — 破坏方块
- `ai/action/PlaceBlockAction.java` — 放置方块
- `ai/action/AttackAction.java` — 攻击实体
- `ai/action/CraftAction.java` — 合成
- `ai/action/FollowAction.java` — 跟随玩家
- `ai/action/GiveItemAction.java` — 给予物品
- `ai/action/RequireItemsAction.java` — 检查物品
- `ai/action/SayAction.java` — 聊天
- `ai/action/WaitAction.java` — 等待
- `ai/action/MineBlockAction.java` — 挖矿（含 A* 寻路）
- `ai/action/GatherResourceAction.java` — 采集资源（含 A* 寻路）
- `ai/action/InteractBlockAction.java` — 交互（工作台/箱子等）
- `ai/action/EquipItemAction.java` — 装备

**路径系统**：
- `ai/pathing/Pathfinder.java` — A* 核心算法
- `ai/pathing/PathNode.java` — 路径节点
- `ai/pathing/PathResult.java` — 路径结果
- `ai/pathing/PathExecutor.java` — 路径执行
- `ai/pathing/BinaryHeapOpenSet.java` — 二叉堆
- `ai/pathing/MovementHelper.java` — 移动辅助
- `ai/pathing/MoveCost.java` — 移动代价计算
- `ai/pathing/ToolSet.java` — 工具选择
- `ai/pathing/goals/Goal.java` — 目标接口
- `ai/pathing/goals/GoalBlock.java` — 方块目标
- `ai/pathing/goals/GoalXZ.java` — XZ 平面目标
- `ai/pathing/goals/GoalYLevel.java` — Y 层级目标
- `ai/pathing/goals/GoalComposite.java` — 组合目标

**LLM 服务**：
- `ai/llm/LLMService.java` — HTTP 调用（流式 + 预检）
- `ai/llm/LLMResponse.java` — 响应模型

**客户端**：
- `client/ClientModEvents.java` — 空（FakePlayer 渲染为原版玩家）

**工具**：
- `util/DevLog.java` — 开发日志

### 测试文件（3 个）
- `src/test/.../ai/TaskTest.java`
- `src/test/.../ai/llm/LLMResponseTest.java`
- `src/test/.../ai/pathing/PathNodeTest.java`

### 文档文件
- `PROGRESS.md`（本文档）
- `AGENTS.md`（Claude Code 指引）
- `CODE_REVIEW.md`（代码审查报告）
- `独立Minecraft Mod架构规划文档.md`（架构设计）

---

## 下一步方向

### 短期（可立即开始）
1. **运行时测试** — 启动游戏，验证 `/ai_bot spawn`、`/ai_bot task`
2. **FakeClientConnection 反射验证** — 确认 EmbeddedChannel 注入在 NeoForge 环境正常工作
3. **HttpClient 复用** — LLMService 共享 HttpClient 实例

### 中期（按架构文档）
4. **持久化系统** — 保存/加载假人状态和背包
5. **多假人协作** — 多个假人分工执行复杂任务
6. **任务队列优化** — 优先级、中断、恢复

### 长期
7. **Baritone 深度集成** — 挖矿策略、矿脉追踪
8. **GUI 界面** — 任务编辑器、假人状态面板
9. **Mod 发布** — CurseForge / Modrinth

---


---

## Baritone 1.21.1 对比分析

### 代码规模对比

| 维度 | aimod | Baritone 1.21.1 | 差距 |
|------|-------|-----------------|------|
| 源文件数 | ~46 | ~200+ | 4x |
| 代码行数 | ~2,855 | ~14,800 | 5x |
| 移动类型 | 1 (基础移动) | 7+ (Traverse/Ascend/Descend/Diagonal/Fall/Pillar/Jump) | 7x |
| 命令数 | 16 | 30+ (含相对坐标/航点/建筑) | 2x |
| 测试覆盖 | 3 文件 / 17 测试 | 6+ 测试文件 | 2x |

### Baritone 优于我们的关键点

#### 1. 移动系统（最大差距）
- **Baritone**: 使用 `PlayerMovementInput` + `InputOverrideHandler` 模拟真实玩家输入（前进/ strafing/跳跃/潜行），通过 `MovementState` 管理每帧的输入状态
- **我们**: 使用 `setDeltaMovement()` 设置速度向量，是物理滑动而非真实行走
- **影响**: 我们的机器人无法精确行走、无法攀爬梯子、无法游泳、无法使用鞘翅
- **优先级**: P0 — 这是最大的技术债

#### 2. 移动类型多样性
- **Baritone 7种移动类型**:
  - `MovementTraverse` — 平地行走（含搭桥）
  - `MovementAscend` — 斜坡上升（跳跃上方方块）
  - `MovementDescend` — 斜坡下降（含安全着陆检测）
  - `MovementDiagonal` — 对角移动（节省29%距离）
  - `MovementFall` — 下落（大高度差）
  - `MovementPillar` — 搭柱向上（放置+跳跃）
  - `MovementJump` — 跳跃（4格跳）
- **我们**: 只有基础 `navigateTo()` — 直线移动 + 简单跳跃

#### 3. 异步寻路
- **Baritone**: `PathingBehavior` 在独立线程运行寻路，时间限制（默认2秒），不阻塞服务器tick
- **我们**: `Pathfinder` 同步运行，可能阻塞服务器tick导致卡顿

#### 4. 世界缓存
- **Baritone**: `CachedWorld` + `CachedRegion` 缓存区块数据，O(1) 方块查询
- **我们**: `WorldScanner` 暴力扫描立方体区域 O(n³)

#### 5. 挖矿智能
- **Baritone `MineProcess`**: 矿脉追踪、矿石黑名单、分支采矿策略、高效扫描
- **我们 `MineBlockAction`**: 基础方块搜索 + A* 寻路

#### 6. 路径执行器
- **Baritone `PathExecutor`**: 冲刺控制、精确卡点检测（`MAX_DIST_FROM_PATH=2`）、`ticksAway` 超时重算、`MovementState` 输入状态管理
- **我们 `PathExecutor`**: 基础距离检测 + 简单卡住超时

#### 7. 命令系统
- **Baritone**: 自定义命令框架（`ICommand`/`Command`）、数据类型系统（`IDatatype`）、参数解析器、相对坐标、航点系统
- **我们**: Brigadier 命令（16个子命令），功能完整但缺少相对坐标和航点

### 我们优于 Baritone 的点

| 我们的优势 | 说明 |
|-----------|------|
| LLM 自然语言集成 | Baritone 无此功能 — 这是我们的核心差异化 |
| 服务端 FakePlayer | Baritone 控制本地客户端玩家；我们是服务端实体 |
| 战斗系统 `AttackAction` | Baritone 无战斗 AI |
| 合成系统 `CraftAction` | 使用 Minecraft 配方系统，Baritone 无此功能 |
| 任务队列 + 反馈 | Task + TaskFeedback 实时状态报告 |
| i18n 国际化 | 中英文支持，Baritone 无多语言 |
| 给予/装备动作 | `GiveItemAction` / `EquipItemAction`，Baritone 无此功能 |

---

## 研发路线图（基于 Baritone 对比）

### Phase 1: 移动精度升级（P0 — 最高优先级）
**目标**: 从 `setDeltaMovement()` 滑动升级为输入模拟行走

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 1.1 InputSimulation 系统 | 创建 `MovementInput` 类，模拟 forward/backward/strafe/jump/sneak 输入 | 3天 |
| 1.2 朝向控制 | 机器人自动面向移动方向（yaw/pitch 计算） | 1天 |
| 1.3 卡住检测升级 | 从简单距离检测升级为路径偏离检测 + 自动重寻路 | 1天 |
| 1.4 基础移动验证 | 验证机器人能正常走斜坡、跳1格、下楼梯 | 1天 |

### Phase 2: 高级移动类型（P1）
**目标**: 实现 Baritone 的核心移动类型

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 2.1 Movement 框架 | 创建 `Movement` 基类，含 src/dest/positionsToBreak/status | 2天 |
| 2.2 MovementTraverse | 平地行走 + 搭桥（放置脚下空位） | 2天 |
| 2.3 MovementAscend/Descend | 斜坡上下移动 | 2天 |
| 2.4 MovementDiagonal | 对角移动（节省距离） | 1天 |
| 2.5 MovementFall | 安全下落（检测落地点） | 1天 |
| 2.6 MovementPillar | 搭柱向上（放置+跳跃） | 2天 |

### Phase 3: 世界缓存与挖矿智能（P1）
**目标**: 替换暴力扫描，提升性能

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 3.1 ChunkCache | 缓存已加载区块的方块数据，O(1) 查询 | 2天 |
| 3.2 OreVeinTracker | 矿脉追踪：挖掘一个矿石后搜索相邻同类 | 1天 |
| 3.3 BranchMining | 分支采矿策略（鱼骨矿道） | 2天 |
| 3.4 MineBlockAction 重写 | 使用 ChunkCache + OreVeinTracker | 1天 |

### Phase 4: 异步寻路（P2）
**目标**: 寻路不阻塞服务器

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 4.1 AsyncPathfinder | 在独立线程运行寻路，超时返回最佳路径 | 2天 |
| 4.2 PathingBehavior | 管理寻路生命周期（请求/取消/重试） | 1天 |
| 4.3 路径缓存 | 缓存最近使用的路径，避免重复计算 | 1天 |

### Phase 5: 战斗 AI 增强（P2）
**目标**: 从简单攻击升级为智能战斗

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 5.1 武器选择 | 根据目标类型自动选择最佳武器 | 1天 |
| 5.2 盾牌格挡 | 检测远程攻击，自动举盾 | 1天 |
| 5.3 走位（Kiting） | 保持距离 + 绕圈攻击 | 2天 |
| 5.4 远程攻击 | 弓/弩/三叉戟瞄准 + 抛物线计算 | 2天 |

### Phase 6: 任务系统增强（P2）
**目标**: 更健壮的任务管理

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 6.1 优先级任务 | 紧急任务可中断低优先级任务 | 1天 |
| 6.2 持久化 | 保存/加载假人状态和背包 | 2天 |
| 6.3 多任务队列 | 支持顺序执行多个任务 | 1天 |
| 6.4 航点系统 | 保存/加载/导航到命名位置 | 1天 |

### Phase 7: 命令系统增强（P3）
**目标**: 对齐 Baritone 的命令体验

| 任务 | 说明 | 预计工时 |
|------|------|---------|
| 7.1 相对坐标 | goto ~ ~10 ~ 等相对坐标支持 | 1天 |
| 7.2 航点命令 | /ai_bot waypoint save/load/list/goto | 1天 |
| 7.3 建筑命令 | /ai_bot build <schematic> | 3天 |
| 7.4 重复任务 | /ai_bot repeat mine diamond_ore | 1天 |

### 总体时间估算

| Phase | 优先级 | 预计工时 | 依赖 |
|-------|--------|---------|------|
| Phase 1: 移动精度 | P0 | 6天 | 无 |
| Phase 2: 高级移动 | P1 | 10天 | Phase 1 |
| Phase 3: 世界缓存 | P1 | 6天 | 无 |
| Phase 4: 异步寻路 | P2 | 4天 | Phase 1 |
| Phase 5: 战斗 AI | P2 | 6天 | Phase 1 |
| Phase 6: 任务系统 | P2 | 5天 | 无 |
| Phase 7: 命令增强 | P3 | 6天 | 无 |

**关键路径**: Phase 1 → Phase 2 → Phase 4（移动精度是所有高级功能的基础）


## 已知问题

- `FakeClientConnection` 使用反射设置 `EmbeddedChannel`，NeoForge patched 字段名可能不同
- 路径执行基础（vanilla navigation + 简单移动控制）
- 无持久化（假人背包和任务不保存）
- WorldScanner 暴力扫描立方体区域（O(n³)）
- 语言文件有编码问题（zh_cn.json 部分乱码）
---

## 外部参考项目分析 (2026-05-23)

### AI-Player (shasankp000/AI-Player)
- **加载器**：Fabric | **版本**：1.21.1 | **Stars**：124
- **分析文档**：`docs/AI_PLAYER_ANALYSIS.md`

**可借鉴的高价值模式**：
1. **GameProfile 持久化** — 假人 UUID 跨重启保存（我们缺失）
2. **危险区域检测** — 岩浆/悬崖检测集成到路径规划（我们缺失）
3. **战斗 AI** — 投射物防御、威胁评估、盾牌格挡（我们 AttackAction 过于简单）
4. **工具智能选择** — 考虑附魔和耐久度（我们 ToolSet 基础）

**可借鉴的中价值模式**：
5. 聊天消息分割（长消息自动拆分）
6. 记忆/RAG 系统（SQLite + 向量嵌入）
7. 死亡/重生处理（自动恢复任务）
8. tick() 优化（每 10 tick 同步一次）

**我们已有的优势**：
- LLM 端到端任务规划（AI-Player 需多步 NLP 管线）
- 14 种高层动作系统（AI-Player 是命令式）
- Baritone 风格 A* 路径（AI-Player 基础寻路）
- 原版配方合成系统（AI-Player 无）
- 流式 LLM 输出（AI-Player 不支持）

### Meteor Client (MeteorDevelopment/meteor-client)
- **类型**：Fabric/NeoForge 外挂端（模块化自动化）
- **分析文档**：`docs/METEOR_CLIENT_ANALYSIS.md`

**高价值模块**：
1. **IPathManager 接口** — 路径管理抽象（moveTo/mine/follow/pause/resume），可插拔实现
2. **KillAura 战斗系统** — 目标筛选（类型/距离/血量/幼崽/好友）、TPS同步攻击间隔、盾牌破防（切斧）、多目标
3. **InvUtils 背包工具** — Predicate 驱动查找、findFastestTool()、流式容器操作 API
4. **BlockUtils 方块交互** — 智能放置面检测 getPlaceSide()、挖掘速度精确计算 getBreakDelta()、暴露检测 isExposed()
5. **AutoEat 自动进食** — 饥饿/血量阈值触发、食物优先级、黑名单

**核心设计哲学**：
- 工具类优先（静态 InvUtils/BlockUtils/EntityUtils）
- Predicate 驱动筛选（灵活可组合）
- FindItemResult 统一返回格式 (slot, count)
- 事件驱动 tick 处理