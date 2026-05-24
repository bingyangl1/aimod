# AI Mod 参考项目文档

> 本文档记录项目中所有参考的开源项目及其具体参考部分，便于溯源和维护。
> 详细的对比分析请参阅 ANALYSIS.md。

---

## 参考项目总览

| 参考项目 | 仓库地址 | 本地路径 | 参考内容 | 对应文件 |
|---------|---------|---------|---------|---------|
| PlayerEngine | Goodbird-git/PlayerEngine | PlayerEngine-main/ | 服务端 Baritone（Automatone），实体抽象接口 | 待吸收 |
| Player2NPC | Goodbird-git/Player2NPC | Player2NPC-master/ | NPC 行为系统、AltoClef 任务链 | 待吸收 |
| Baritone | cabaletta/baritone (1.21.1) | baritone-ref/ | A* 寻路、Movement 系统、CalculationContext、ToolSet、世界缓存 | ai/pathing/*.java, ai/movement/*.java |
| SiliconeDolls | Anvil-Dev/SiliconeDolls | 无 | FakePlayer 创建/注册模式 | fakeplayer/FakePlayer.java |
| AI-Player | shasankp000/AI-Player | 无 | GameProfile 持久化 | fakeplayer/BotProfileStore.java |
| EMI | EmilyPloszaj/emi | 无 | 合成配方索引策略 | ai/RecipeIndex.java |
| Meteor Client | MeteorDevelopment/meteor-client | 无 | 背包工具、方块交互 | ai/InventoryUtils.java |
| RollingGate | Anvil-Dev/RollingGate | 无 | 配置文件风格 | config/ModConfig.java |

---

## 详细参考说明

### 1. PlayerEngine（Automatone）

**仓库**：https://github.com/Goodbird-git/PlayerEngine/tree/main
**本地路径**：PlayerEngine-main/
**类型**：Fabric 模组，Baritone 的服务端分叉

**核心创新**：将 Baritone 从客户端 LocalPlayer 扩展为服务端任意 LivingEntity。

**关键文件**：
- src/main/java/baritone/PlayerEngine.java — 主类，线程池初始化
- src/main/java/baritone/api/entity/IInventoryProvider.java — 背包接口
- src/main/java/baritone/api/entity/LivingEntityInventory.java — 实体背包（完整复制 Player.Inventory）
- src/main/java/baritone/api/entity/LivingEntityInteractionManager.java — 实体交互管理（挖掘/放置/使用）
- src/main/java/baritone/utils/player/EntityContext.java — 实体上下文（替代 PlayerContext）
- src/main/java/baritone/utils/player/EntityInteractionController.java — 实体交互控制器

**可吸收内容**：
- 接口抽象设计（IInventoryProvider、IInteractionManagerProvider）
- LivingEntityInteractionManager 方块挖掘逻辑
- 线程池配置（4 核心 + 无限制最大）
- EntityContext 实体上下文设计

**我们的现状**：使用 FakePlayer(ServerPlayer)，不需要 LivingEntityInventory，但可以吸收接口抽象思想。

---

### 2. Player2NPC

**仓库**：https://github.com/Goodbird-git/Player2NPC/tree/master
**本地路径**：Player2NPC-master/
**类型**：Fabric 模组，基于 Automatone + AltoClef

**核心设计**：将 LivingEntity 包装为可对话的 AI NPC。

**关键文件**：
- src/main/java/com/goodbird/player2npc/companion/AutomatoneEntity.java — NPC 实体（LivingEntity + 接口）
- src/main/java/com/goodbird/player2npc/companion/CompanionManager.java — NPC 生命周期管理
- src/main/java/com/goodbird/player2npc/client/util/SkinManager.java — 皮肤管理
- src/main/java/com/goodbird/player2npc/client/gui/CharacterSelectionScreen.java — 角色选择 GUI

**AltoClef 任务链系统**（位于 PlayerEngine-main/src/autoclef/）：
- AltoClefController.java — AI 控制器（TaskRunner + TrackerManager + Chain）
- chains/UserTaskChain.java — 用户任务链
- chains/FoodChain.java — 自动进食链
- chains/MobDefenseChain.java — 怪物防御链
- chains/MLGBucketFallChain.java — 水桶防摔链
- chains/UnstuckChain.java — 卡住自救链
- chains/PreEquipItemChain.java — 预装备链
- chains/WorldSurvivalChain.java — 世界生存链

**可吸收内容**：
- BehaviorChain 行为链系统（优先级执行）
- FoodChain 自动进食
- MobDefenseChain 怪物防御
- MLGBucketFallChain 水桶防摔
- UnstuckChain 卡住自救
- CompanionManager NPC 生命周期管理
- SkinManager 皮肤系统
- GUI 系统

**我们的现状**：无行为链系统，任务顺序执行。需要引入优先级机制。

---

### 3. Baritone

**仓库**：https://github.com/cabaletta/baritone/tree/1.21.1
**本地路径**：baritone-ref/
**类型**：Forge/NeoForge 模组，自动化路径规划

**关键文件**：
- src/main/java/baritone/pathing/movement/Movement.java — Movement 基类
- src/main/java/baritone/pathing/movement/movements/MovementTraverse.java — 平地行走 + 搭桥
- src/main/java/baritone/pathing/movement/movements/MovementPillar.java — 搭柱向上
- src/main/java/baritone/pathing/movement/movements/MovementFall.java — 跌落
- src/main/java/baritone/pathing/movement/CalculationContext.java — 代价快照
- src/main/java/baritone/behavior/PathingBehavior.java — 异步寻路
- src/main/java/baritone/cache/CachedWorld.java — 世界缓存
- src/main/java/baritone/utils/ToolSet.java — 工具选择（已吸收：ai/pathing/ToolSet.java）
- src/main/java/baritone/pathing/movement/CalculationContext.java — 代价快照（已吸收：ai/pathing/CalculationContext.java）
- src/main/java/baritone/utils/InputOverrideHandler.java — 输入覆盖
- src/main/java/baritone/utils/PlayerMovementInput.java — 移动输入模拟

**可吸收内容**：
- Movement 系统（8 种 Movement 类型）
- PathingBehavior 异步寻路（双缓冲）
- CalculationContext 代价快照
- CachedWorld 世界缓存
- ToolSet 工具选择（附魔、药水）
- InputOverrideHandler 输入模拟

**我们的现状**：
- ✅ A* 寻路算法 (Pathfinder)
- ✅ Goal 系统 (GoalBlock, GoalXZ, GoalYLevel)
- ✅ PathExecutor 逐步移动
- ✅ MoveCost 代价计算
- ✅ ToolSet 工具选择（含附魔效率和药水效果）
- ✅ AsyncPathfinder 异步寻路
- ✅ CalculationContext 代价快照（线程安全世界状态）
- ✅ BotMovement 基类 + MovementTraverse + MovementPillar
- ❌ MovementAscend/Descend/Diagonal/Fall/Parkour/Downward
- ❌ CachedWorld 世界缓存
- ❌ InputOverrideHandler（服务端不需要）

---

### 4. SiliconeDolls

**仓库**：https://github.com/Anvil-Dev/SiliconeDolls

**参考内容**：FakePlayer 创建和注册模式

**已吸收**：
- FakePlayer 继承 ServerPlayer
- 通过 placeNewPlayer() 注册到服务器
- FakeClientConnection 使用 EmbeddedChannel
- FakePlayerNetHandler 丢弃数据包

**对应文件**：
- akeplayer/FakePlayer.java
- akeplayer/FakeClientConnection.java
- akeplayer/FakePlayerNetHandler.java

---

### 5. AI-Player

**仓库**：https://github.com/shasankp000/AI-Player

**参考内容**：GameProfile 持久化

**已吸收**：
- Bot name -> UUID 映射持久化到 JSON
- 重启后使用相同 UUID

**对应文件**：
- akeplayer/BotProfileStore.java

---

### 6. EMI

**仓库**：https://github.com/EmilyPloszaj/emi

**参考内容**：合成配方索引策略

**已吸收**：
- byOutput/byInput 索引，O(1) 查找
- Tag 匹配支持
- 催化物/消耗物区分

**对应文件**：
- i/RecipeIndex.java

---

### 7. Meteor Client

**仓库**：https://github.com/MeteorDevelopment/meteor-client

**参考内容**：背包工具、方块交互

**已吸收（部分）**：
- InvUtils 背包工具模式
- BlockUtils 方块交互模式

**对应文件**：
- i/InventoryUtils.java

---

### 8. RollingGate

**仓库**：https://github.com/Anvil-Dev/RollingGate

**参考内容**：配置文件风格

**已吸收**：
- NeoForge ModConfigSpec 配置风格

**对应文件**：
- config/ModConfig.java

---

## 参考代码目录

- aritone-ref/ — Baritone 1.21.1 参考代码
- PlayerEngine-main/ — PlayerEngine（Automatone）参考代码
- Player2NPC-master/ — Player2NPC 参考代码

---

## 如何添加新参考

1. 在本文档中添加新的参考项目条目
2. 记录仓库地址、本地路径、参考内容、对应文件
3. 在 ANALYSIS.md 中添加详细对比分析
4. 在 PROGRESS.md 中记录参考来源