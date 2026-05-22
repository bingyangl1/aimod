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
| 测试 | ✅ 基础完成 | 20% | 16 个单元测试通过 |

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

## 已知问题

- `FakeClientConnection` 使用反射设置 `EmbeddedChannel`，NeoForge patched 字段名可能不同
- 路径执行基础（vanilla navigation + 简单移动控制）
- 无持久化（假人背包和任务不保存）
- WorldScanner 暴力扫描立方体区域（O(n³)）
- 语言文件有编码问题（zh_cn.json 部分乱码）