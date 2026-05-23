# AI Mod 参考项目文档

> 本文档记录项目中所有参考的开源项目及其具体参考部分，便于溯源和维护。

---

## 参考项目总览

| 参考项目 | 仓库地址 | 参考内容 | 对应文件 |
|---------|---------|---------|---------|
| SiliconeDolls | Anvil-Dev/SiliconeDolls | FakePlayer 创建/注册模式 | FakePlayer.java |
| Baritone | cabaletta/baritone (1.21.1) | A* 寻路、Goal 系统、命令模式 | ai/pathing/*.java, BotCommand.java |
| AI-Player | shasankp000/AI-Player | GameProfile 持久化 | BotProfileStore.java |
| EMI | EmilyPloszaj/emi | 合成配方索引策略 | RecipeIndex.java |
| Meteor Client | MeteorDevelopment/meteor-client | 背包工具、方块交互模式 | InventoryUtils.java |
| RollingGate | Anvil-Dev/RollingGate | 配置文件风格 | ModConfig.java |

---

## 详细参考说明

### 1. SiliconeDolls (Anvil-Dev/SiliconeDolls)

**仓库地址**：https://github.com/Anvil-Dev/SiliconeDolls

**参考内容**：FakePlayer 创建和注册模式

**具体参考**：
- FakePlayer 继承 ServerPlayer 的方式
- 通过 placeNewPlayer() 注册到服务器玩家列表
- FakeClientConnection 使用 EmbeddedChannel 模拟网络连接
- FakePlayerNetHandler 继承 ServerGamePacketListenerImpl 丢弃数据包
- ClientInformation.createDefault() 用于构造 FakePlayer
- setInvulnerable(true) 使假人无敌
- 死亡后自动重生机制

**对应文件**：
- akeplayer/FakePlayer.java — createAndRegister() 方法
- akeplayer/FakeClientConnection.java — EmbeddedChannel 连接
- akeplayer/FakePlayerNetHandler.java — 网络处理器

**关键代码片段**（FakePlayer.createAndRegister）：
`java
// 参考 SiliconeDolls 的注册模式
server.getPlayerList().placeNewPlayer(
    new FakeClientConnection(PacketFlow.SERVERBOUND),
    instance,
    new CommonListenerCookie(profile, 0, instance.clientInformation(), false)
);
`

---

### 2. Baritone (cabaletta/baritone)

**仓库地址**：https://github.com/cabaletta/baritone/tree/1.21.1

**参考内容**：A* 寻路算法、Goal 系统、命令模式、移动代价计算

**具体参考**：

#### 2.1 A* 寻路算法
- Pathfinder 类参考 Baritone 的 AStarPathFinder
- HashMap 存储 PathNode，O(1) 节点查找
- PriorityQueue（二叉堆）作为开放列表
- 超时搜索机制（默认 2 秒），返回最佳路径
- 欧几里得距离启发函数
- 16 种移动类型（4 基本 + 4 对角 + 4 上升 + 4 下降）

**对应文件**：
- i/pathing/Pathfinder.java — A* 核心算法
- i/pathing/PathNode.java — 路径节点（含 hash 和 heuristic）
- i/pathing/BinaryHeapOpenSet.java — 二叉堆开放列表

#### 2.2 Goal 系统
- Goal 基类抽象（isReached、heuristic）
- GoalBlock — 到达指定方块坐标
- GoalXZ — 到达 XZ 平面
- GoalYLevel — 到达指定 Y 高度
- GoalComposite — 组合多个目标

**对应文件**：
- i/pathing/goals/Goal.java
- i/pathing/goals/GoalBlock.java
- i/pathing/goals/GoalXZ.java
- i/pathing/goals/GoalYLevel.java
- i/pathing/goals/GoalComposite.java

#### 2.3 移动代价计算
- MoveCost 参考 Baritone 的 MovementHelper
- 多格跌落（1-4 格）+ 跌落伤害代价
- 对角穿墙检测（corner-cutting）
- 半砖/楼梯可行走（canWalkOn）
- 岩浆/虚空标记为 VOID_COST

**对应文件**：
- i/pathing/MoveCost.java — 移动代价常量和计算
- i/pathing/MovementHelper.java — 移动辅助（方块可通行性）

#### 2.4 工具选择
- ToolSet 参考 Baritone 的 ToolSet
- 计算挖掘速度，选择最佳工具
- 考虑工具附魔（效率、精准采集）

**对应文件**：
- i/pathing/ToolSet.java

#### 2.5 路径执行
- PathExecutor 参考 Baritone 的 PathExecutor
- 逐步移动 + 卡住检测
- 路径偏离检测 + 自动重新寻路

**对应文件**：
- i/pathing/PathExecutor.java

#### 2.6 命令模式
- BotCommand 的 stop/cancel/pause/resume 命令参考 Baritone 命令风格
- Brigadier 命令树结构

**对应文件**：
- command/BotCommand.java

---

### 3. AI-Player (shasankp000/AI-Player)

**仓库地址**：https://github.com/shasankp000/AI-Player

**参考内容**：GameProfile 持久化（UUID 跨重启保存）

**具体参考**：
- Bot name 到 UUID 的映射持久化到 JSON 文件
- 重启后使用相同 UUID，保持背包和状态一致性
- 使用 Gson 进行 JSON 序列化

**对应文件**：
- akeplayer/BotProfileStore.java — GameProfile 持久化
- akeplayer/FakePlayerManager.java — 集成 BotProfileStore

**存储格式**（config/aimod/bots.json）：
`json
{
  "Steve": "uuid-string",
  "Alex": "uuid-string"
}
`

---

### 4. EMI (EmilyPloszaj/emi)

**仓库地址**：https://github.com/EmilyPloszaj/emi

**参考内容**：合成配方索引策略

**具体参考**：
- RecipeIndex 参考 EMI 的配方索引设计
- byOutput 索引：输出物品 -> 配方列表（O(1) 查找）
- byInput 索引：输入物品 -> 配方列表（O(1) 查找）
- Tag 匹配支持（如 #minecraft:planks 匹配任意木板）
- 催化物/消耗物区分（工作台是催化物，木板是消耗物）
- 智能配方选择：根据库存选择最佳配方

**对应文件**：
- i/RecipeIndex.java — 合成配方索引
- i/action/CraftAction.java — 使用 RecipeIndex 的合成动作

---

### 5. Meteor Client (MeteorDevelopment/meteor-client)

**仓库地址**：https://github.com/MeteorDevelopment/meteor-client

**参考内容**：背包工具、方块交互模式

**具体参考**：

#### 5.1 InvUtils 背包工具
- Predicate 驱动的物品查找
- findFastestTool() — 查找最快挖掘工具
- 流式容器操作 API
- FindItemResult 统一返回格式 (slot, count)

**对应文件**：
- i/InventoryUtils.java — 背包操作工具（部分参考）

#### 5.2 BlockUtils 方块交互
- 智能放置面检测 getPlaceSide()
- 挖掘速度精确计算 getBreakDelta()
- 暴露检测 isExposed()

**对应文件**：
- i/action/BreakBlockAction.java — 破坏方块（部分参考）
- i/action/PlaceBlockAction.java — 放置方块（部分参考）

---

### 6. RollingGate (Anvil-Dev/RollingGate)

**仓库地址**：https://github.com/Anvil-Dev/RollingGate

**参考内容**：配置文件风格

**具体参考**：
- NeoForge ModConfigSpec 配置风格
- 注释风格和配置项组织方式

**对应文件**：
- config/ModConfig.java — 配置项定义

---

## 参考项目对比分析

### 本项目优势（相比参考项目）

| 优势 | 说明 | 参考项目对比 |
|------|------|------------|
| LLM 自然语言 | 核心差异化，其他项目无此功能 | AI-Player 无 LLM |
| 混合架构 | AIBotEntity 可见 + FakePlayer 完整能力 | SiliconeDolls 仅 FakePlayer |
| 流式 LLM 输出 | 实时显示 LLM 思考过程 | AI-Player 不支持 |
| Baritone 风格寻路 | A* + Goal 系统 + 多格跌落 | Meteor Client 无此深度 |
| 合成系统 | RecipeIndex O(1) + Tag 感知 | Baritone 无合成 |
| 战斗 AI | AttackAction + 目标追踪 | Baritone 无战斗 |
| 任务队列反馈 | Task + TaskFeedback 实时状态报告 | 其他项目无 |
| i18n 国际化 | 中英文支持 | 其他项目多为英文 |
| 给予/装备动作 | GiveItemAction / EquipItemAction | Baritone 无 |

### 待改进（参考项目已实现）

| 功能 | 参考项目 | 本项目状态 |
|------|---------|-----------|
| 持久化（背包/任务） | AI-Player | 仅 GameProfile 持久化 |
| 异步寻路 | Baritone | 同步寻路（2 秒超时） |
| 世界缓存 | Baritone (ChunkCache) | 暴力扫描 O(n^3) |
| 战斗 AI（盾牌/走位） | Meteor Client (KillAura) | 仅基础攻击 |
| 自动进食 | Meteor Client (AutoEat) | 未实现 |
| Movement 基类 | Baritone (Movement*) | 未实现 |

---

## 参考代码目录

本项目参考代码存储在 baritone-ref/ 目录（不参与编译）：

- aritone-ref/ — Baritone 1.21.1 参考代码
  - 用于对比 A* 寻路实现
  - 用于参考 Goal 系统设计
  - 用于参考 MoveCost 计算方式

---

## 如何添加新参考

1. 在本文档中添加新的参考项目条目
2. 记录仓库地址、参考内容、对应文件
3. 如果需要存储参考代码，放入 baritone-ref/ 或新建目录
4. 在 PROGRESS.md 中记录参考来源