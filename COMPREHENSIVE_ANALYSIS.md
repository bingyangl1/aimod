# Minecraft AI Mod — 全参考项目综合对比分析

> 本文综合分析 9 个优秀 Minecraft 模组项目，与 AI Mod 深度对比，提炼可学习、吸收和复用的功能与实现。

---

## 目录

1. [参考项目总览](#一参考项目总览)
2. [各项目核心分析](#二各项目核心分析)
3. [当前项目深度审查](#三当前项目深度审查)
4. [跨维度综合对比](#四跨维度综合对比)
5. [功能差距分析](#五功能差距分析)
6. [可吸收实现的详细方案](#六可吸收实现的详细方案)
7. [实施路线图](#七实施路线图)
8. [风险与注意事项](#八风险与注意事项)

---

## 一、参考项目总览

| # | 项目 | 仓库/来源 | MC 版本 | 类型 | 核心定位 |
|---|------|----------|---------|------|---------|
| 1 | **Baritone** | cabaletta/baritone (1.21.1) | 1.21.1 | Forge/NeoForge | 业界标杆 A* 寻路 + Movement 系统 + 世界缓存 |
| 2 | **PlayerEngine** | Goodbird-git/PlayerEngine | 1.21.1 | Fabric | 服务端 Baritone (Automatone)，实体抽象接口 |
| 3 | **Player2NPC** | Goodbird-git/Player2NPC | 1.21.1 | Fabric | NPC 行为系统 (AltoClef Task + Chain + Tracker) |
| 4 | **SiliconeDolls** | Anvil-Dev/SiliconeDolls | 1.21.1 | NeoForge | 成熟的 FakePlayer 创建/注册/控制模式 |
| 5 | **RollingGate** | Anvil-Dev/RollingGate | 1.21.1 | NeoForge | 注解驱动的规则引擎 + 箱子菜单 UI 框架 |
| 6 | **AI-Player** | shasankp000/AI-Player | 1.21.1 | Fabric | AI 驱动的玩家机器人 + RL 学习 + NLP 理解 |
| 7 | **Meteor Client** | MeteorDevelopment/meteor-client | 1.21.1 | Fabric | 工具类客户端，背包工具 + 方块交互模式 |
| 8 | **EMI** | EmilyPloszaj/emi | 1.21 | 多平台 | 合成配方索引 + 自动合成树 (MaterialTree) |
| 9 | **JEI** | mezz/JustEnoughItems | 1.21 | 多平台 | 业界标准配方索引 (O(1) 查找 + 插件系统) |

### 项目间依赖关系

```
RollingGate (规则引擎库)
  └── SiliconeDolls (FakePlayer 模组)

PlayerEngine (服务端 Baritone = Automatone)
  └── Player2NPC (NPC AI 系统，集成 AltoClef)

Baritone (原版客户端 A* 寻路)
  └── PlayerEngine (服务端分叉)
       └── Player2NPC (NPC 行为系统)

EMI (轻量级 JEI 替代品，内置自动合成树)
JEI (配方索引标准实现)
```

---

## 二、各项目核心分析

### 2.1 Baritone — 寻路和移动系统标杆

**关键创新**：
- **8 种 Movement 类型**：Traverse, Pillar, Fall, Ascend, Descend, Diagonal, Parkour, Downward
- **四状态机**：PREPPING → WAITING → RUNNING → SUCCESS/UNREACHABLE
- **CalculationContext 代价快照**：寻路开始时冻结世界状态，保证线程安全
- **PathingBehavior 双缓冲**：current 正在执行 + next 预计算
- **CachedWorld 区域级缓存**：512x512 区域，异步打包 Chunk，O(1) 方块查找，10 分钟自动保存
- **InputOverrideHandler**：模拟客户端输入，非直接设置位置

**8 种 Movement 详解**：

| Movement | 功能 | 关键行为 |
|----------|------|---------|
| MovementTraverse | 平地行走 + 搭桥 | 潜行放置方块，one-tick 中心对齐 |
| MovementPillar | 搭柱向上 | 放置方块 + 跳跃 |
| MovementFall | 跌落 | 可选水桶放置防摔 |
| MovementAscend | 斜坡上升 | 自动跳跃 |
| MovementDescend | 斜坡下降 | 阶梯式下降 |
| MovementDiagonal | 对角移动 | 处理角落碰撞 |
| MovementParkour | 跑跳 | 跳跃 + 空中搭桥 |
| MovementDownward | 向下挖掘 | 挖掘脚下方块 |

**每一 Movement 的执行流程**：
1. **PREPPING** — 准备阶段：检查前置条件（如需要放置方块、清障等）
2. **WAITING** — 等待阶段：等待前置操作完成
3. **RUNNING** — 执行阶段：逐 tick 控制输入/位置
4. **SUCCESS** 或 **UNREACHABLE** — 结果判断

**MovementState 是核心状态追踪器**，记录：
- `MOVEMENT_STATE_POS`（当前位置缓存，减少重复 getBlockState 调用）
- `MOVEMENT_STATE_TARGET`（当前 Movement 的目标位置）
- `MOVEMENT_STATE_STATUS`（当前 Movement 的状态）
- `MOVEMENT_STATE_IS_JUMPING`（是否正在跳跃）

**MovementHelper** 是核心工具类：
- `canWalkOn()` — 方块是否可站立
- `canWalkThrough()` — 方块是否可穿过
- `isTransparentBlock()` — 方块是否透明
- `avoidBreaking()` — 是否应避免破坏（如箱子、刷怪笼等）

**我们已吸收的内容**：
- A* 寻路算法 (Pathfinder) ✅
- Goal 系统 (GoalBlock, GoalXZ, GoalYLevel, GoalComposite) ✅
- PathExecutor 逐步移动 ✅
- MoveCost 代价计算 ✅
- ToolSet 工具选择 ✅
- AsyncPathfinder 异步寻路 ✅
- BotMovement 基类 + MovementTraverse + MovementPillar ✅

**仍需吸收的内容**：
- MovementAscend/Descend/Diagonal/Fall/Parkour/Downward
- CalculationContext 代价快照模式
- CachedWorld 世界缓存
- InputOverrideHandler（但我们需要服务端适配版）
- 完整的 MovementState 状态追踪器

---

### 2.2 PlayerEngine (Automatone) — 实体抽象接口

**核心创新**：将 Baritone 从 LocalPlayer 扩展为 LivingEntity

**接口体系**：
```
IAutomatone              — 标记接口
IInventoryProvider       — 背包接口
IInteractionManagerProvider — 交互管理接口
IHungerManagerProvider   — 饥饿管理接口
IEntityContext           — 实体上下文（替代 IPlayerContext）
IInteractionController   — 交互控制器（替代 IPlayerController）
```

**关键实现类**：
- `LivingEntityInventory` — 完整复制 Player.Inventory（36 主背包 + 4 盔甲 + 1 副手），NBT 序列化
- `LivingEntityInteractionManager` — 完整方块挖掘逻辑（开始/继续/停止挖掘）+ 方块放置 + 物品使用
- `EntityContext` — 替代 PlayerContext，支持 mob 避让系统
- `EntityInteractionController` — 服务端方块破坏/放置，射线追踪

**线程池设计**：
```java
threadPool = new ThreadPoolExecutor(
    4,                    // 核心线程
    Integer.MAX_VALUE,    // 最大线程（无限制）
    60L, TimeUnit.SECONDS, // 空闲超时
    new SynchronousQueue<>(), // 无缓冲、直接交接
    r -> new Thread(r, "Automatone Worker " + counter.incrementAndGet())
);
```

**与我们对比**：

| 维度 | PlayerEngine | AI Mod | 评价 |
|------|-------------|--------|------|
| 实体模型 | LivingEntity + 接口 | AIBotEntity(Mob) + FakePlayer(ServerPlayer) | 我们的更具体，有原生服务器支持 |
| 背包 | LivingEntityInventory | FakePlayer 的 ServerPlayer 自带背包 | 我们更简单 |
| 交互管理 | 完整重新实现 | 委托 FakePlayer | 我们复用原生 API |
| 寻路 | Baritone A* (异步) | 自研 A* (异步) ✅ | 已对齐 |
| 线程池 | 4 核心 + 无限制最大 | 无独立线程池 | 需要吸收 |

**可吸收**：
- 接口抽象思想（让代码更可测试）
- LivingEntityInteractionManager 的方块挖掘进度计算
- 线程池设计

---

### 2.3 Player2NPC — 行为链系统和 NPC 管理

**架构**：Automatone + AltoClef + Player2 API

**核心：Chain 系统（9 条优先级行为链）**：

| 优先级 | Chain | 触发条件 | 行为 |
|--------|-------|---------|------|
| 1 | UserTaskChain | 用户指定任务 | 执行用户命令 |
| 2 | MobDefenseChain | 怪物攻击 | 自动防御 |
| 3 | PlayerDefenseChain | 玩家攻击 | 自动防御 |
| 4 | FoodChain | 饥饿值 < 阈值 | 自动进食 |
| 5 | MLGBucketFallChain | 坠落中 | 水桶防摔 |
| 6 | UnstuckChain | 卡住 | 自动脱困 |
| 7 | PreEquipItemChain | 物品切换前 | 预装备 |
| 8 | WorldSurvivalChain | 环境危险 | 生存行为 |
| 9 | PlayerInteractionFixChain | 交互失败 | 交互修复 |

**AltoClefController 完整组件**：
- **TaskRunner** — 任务执行器
- **TrackerManager** — 追踪器（实体、方块、区块、背包、合成配方）
- **Chain 系统** — 9 条优先级链
- **CommandExecutor** — 命令执行器
- **InputControls** — 输入控制
- **SlotHandler** — 槽位管理
- **BotBehaviour** — 行为配置

**CompanionManager**：
- NPC 生命周期管理（创建/销毁/持久化）
- 异步角色获取 (CompletableFuture)
- NBT 持久化 (companion name → UUID)
- 每 tick 检查

**皮肤系统**：
- SkinManager — 管理皮肤纹理
- ImageDownloadAlt — 异步下载皮肤
- ResourceDownloader — 资源下载器
- 支持自定义皮肤 URL

**与我们对比**：

| 维度 | Player2NPC | AI Mod | 差距 |
|------|-----------|--------|------|
| AI 系统 | AltoClef (Task + Chain + Tracker) | LLM + Task + Action | P2N 更成熟，但我们的 LLM 更灵活 |
| 行为优先级 | 9 条链 | 无优先级 | 高 |
| 防御系统 | MobDefenseChain + PlayerDefenseChain | 无 | 高 |
| 自动进食 | FoodChain | 无 | 中 |
| 卡住自救 | UnstuckChain (智能) | UnstuckDetector (基本) ✅ | 已部分对齐 |
| 水桶防摔 | MLGBucketFallChain | 无 | 中 |
| 持久化 | NBT 完整 | 仅 GameProfile | 中 |
| 皮肤系统 | 自定义 URL | 固定 Steve | 低 |
| GUI | 角色选择界面 | 无 | 低 |

---

### 2.4 SiliconeDolls — FakePlayer 创建/注册/控制模式

**核心架构**：
- `FakePlayer extends ServerPlayer` — 与我们的架构相同
- 使用 `placeNewPlayer()` 注册 — 与我们的架构相同
- `FakeClientConnection` + `FakePlayerNetHandler` — 与我们的架构相同

**与我们对比的关键差异**：

| 维度 | SiliconeDolls | AI Mod | 建议 |
|------|-------------|--------|------|
| FakePlayerNetHandler 创建 | 通过 Mixin 在 placeNewPlayer 中替换 | 在构造函数中手动设置 | **需修复** |
| 网络防御层数 | 4 层 (Channel, Handler, Distributor, CommonListener) | 1 层 (FakePlayerNetHandler.send) | **需加强** |
| Config Phase 跳过 | ServerConfigurationPacketListenerImplMixin | 无 | **需增加** |
| Mixin 数量 | 15 个 | 0 个 | **需增加** |
| GameProfile 皮肤 | SkullBlockEntity.fetchGameProfile() | 无皮肤获取 | **需增加** |
| 玩家交互 | PlayerActionPack（定时动作系统） | BotAIManager（LLM 驱动） | 不同用途 |
| 持久化 | /bot save/load (JSON) | BotProfileStore (仅 UUID) | **需加强** |
| 背包 GUI | PlayerInventoryContainer + ChestMenu | 无 | 可选 |
| Auto Fish | FakePlayerAutoFish | 无 | 可选 |
| Auto Replace Tool | FakePlayerAutoReplaceTool | 无 | 可选 |
| Auto Replenishment | FakePlayerAutoReplenishment | 无 | 可选 |
| 死亡处理 | 立即重置生命值 + kill() | 40 tick 延迟重生 | SD 更简洁 |

**SiliconeDolls 的 4 层网络防御**：

| 层 | 类 | 机制 |
|----|-----|------|
| 1 | FakeClientConnection | EmbeddedChannel（非真实网络） |
| 2 | FakePlayerNetHandler | `send()` 空方法 |
| 3 | PacketDistributorMixin | 在分发层取消发往 FakePlayer 的包 |
| 4 | ServerCommonPacketListenerImplMixin | 在通用监听层取消 |

**关键 Mixin — PlayerListMixin**：
```java
@WrapOperation(method = "placeNewPlayer")
public ServerGamePacketListenerImpl wrapHandler(..., Operation<ServerGamePacketListenerImpl> original) {
    ServerGamePacketListenerImpl handler = original.call(...);
    if (player instanceof FakePlayer) {
        return new FakePlayerNetHandler(server, connection, player, cookie);
    }
    return handler;
}
```
这个 Mixin 在 `placeNewPlayer()` 创建 `ServerGamePacketListenerImpl` 后，将其替换为 `FakePlayerNetHandler`。正确的做法，而不是在构造函数中手动设置。

---

### 2.5 RollingGate — 规则引擎框架

**核心创新**：注解驱动的可扩展规则系统

**关键架构组件**：

1. **`@Rule` 注解** — 声明式规则定义：
   - 自动类型检测（boolean, int, double, String）
   - 自动创建验证器（min/max 范围）
   - 支持自定义 codec 和 validator

2. **注解扫描发现** — 启动时自动发现：
   ```java
   ModFileScanData.getAnnotations().stream()
       .filter(a -> a.annotationType().equals("Ldev/anvilcraft/rg/api/server/RGServerRules;"))
       .forEach(a -> registerRules(Class.forName(a.memberName())));
   ```

3. **双层配置** — 全局 + 世界级覆盖：
   - 全局：`config/{namespace}.json`
   - 世界：`{world_dir}/{namespace}.json`

4. **基于箱子菜单的 UI** — 无需自定义 GUI 代码：
   - `CustomChestMenu` — 抽象 Container
   - `Button` — 物品槽位的开关按钮
   - `CheckList` / `RadioList` — 多选/单选

5. **事件驱动规则变更**：
   - `RGRuleChangeEvent` — 可取消的变更事件
   - `RGValidatorNotPassedEvent` — 验证失败事件

6. **Brigadier 命令自动生成**：规则的命令树自动生成，含可点击的值选择器

**我们可吸收的**：
- 规则引擎模式对于 AI 行为配置（温度、最大 token、寻路参数等）
- 箱子菜单 UI 用于游戏中查看/控制 AI 状态
- `PlanFunction` 用于在特定游戏 tick 调度任务

---

### 2.6 AI-Player — AI 驱动的玩家机器人

**核心架构**：一个非常复杂的 AI 系统

**模块组成**：

1. **NLP 处理管道**：
   - BERT 模型 — 自然语言理解
   - LIDSNet 模型 — 意图检测
   - CART 分类器 — 动作分类
   - OpenNLP — 预处理（分词、句子检测）
   - RAG2 — 检索增强生成

2. **AI 提供者抽象**：
   - `EmbeddingProvider` / `EmbeddingProviderFactory` — 向量嵌入
   - `LLMClientFactory` — LLM 客户端工厂
   - `ollamaClient` — Ollama API 集成
   - `OllamaAPIHelper` — 辅助函数

3. **Function Calling (Tool) 系统**：
   - `Tool` — 工具接口
   - `ToolRegistry` — 工具注册表
   - `FunctionCallerV2` — LLM function calling
   - `OutputVerifier` — 输出验证
   - `ToolStateUpdater` — 工具状态更新

4. **规划系统**：
   - `HybridPlanner` — 混合规划器
   - `ActionGraph` — 动作图
   - `MarkovChain2` — 马尔可夫链
   - `SequenceRiskAnalyzer` — 序列风险分析
   - `Planner` — 基础规划器

5. **强化学习**：
   - `RLAgent` — 强化学习代理
   - `QTable` / `QTableStorage` — Q 表存储
   - `State` / `StateActions` / `StateTransition` — 状态-动作系统
   - `LookaheadLearning` — 前瞻学习
   - SQLite 数据库持久化

6. **FakePlayer 创建** (与我们的模式类似)：
   - `createFakePlayer` — FakePlayer 创建
   - `AutoFaceEntity` / `FaceClosestEntity` — 面部朝向
   - `RayCasting` — 射线追踪
   - `RespawnHandler` — 重生处理

7. **DangerZone 检测**：
   - `CliffDetector` — 悬崖检测
   - `LavaDetector` — 熔岩检测

**AI-Player 的关键差异**：
- 使用**本地 NLP 模型** (BERT, LIDSNet) 而非云端 LLM
- 有完整的**强化学习系统** (Q-learning)
- 有**规划系统** (ActionGraph, MarkovChain)
- 集成**嵌入式数据库** (SQLite)
- 使用 Ollama 作为本地 LLM 提供者

**我们可以学习的**：
- DangerZone 检测系统（CliffDetector + LavaDetector → 融入 WorldScanner）
- 序列风险分析（SequenceRiskAnalyzer → 动作执行前的安全检查）
- 工具注册表模式（ToolRegistry → 让 LLM 能调用更多工具）
- 输出验证器（OutputVerifier → 对 LLM 输出进行二次验证）

---

### 2.7 Meteor Client — 工具类客户端

**关键工具模式**：

**1. InvUtils — 背包操作模式**：

```java
// FindItemResult 记录类型 — 轻量级查找结果
public record FindItemResult(int slot, int count) {
    boolean found()              // slot != -1
    InteractionHand getHand()     // 判断是 OFF_HAND / MAIN_HAND / null
    boolean isHotbar()            // slot < 9
    boolean isMain()               // 9 <= slot < 36
}

// 优先级查找：副手 > 主手 > 快捷栏 > 主背包
FindItemResult find(Item... items)

// 最佳工具查找
FindItemResult findFastestTool(BlockState state)

// swap-back 跟踪
swap(int slot, true)   // 记录当前槽位
swapBack()              // 恢复之前槽位
```

**2. InvUtils.Action — 流畅的建造器模式**：
```java
InvUtils.move().from(srcSlot).toHotbar(3);
InvUtils.shiftClick().slot(slotIndex);
InvUtils.click().fromId(containerId, slot).toHotbar(5);
```

**3. Rotations — 优先级旋转队列**：
- 多个模块可在同一 tick 排队旋转
- 高优先级先执行
- 自动在包发送前应用旋转，发送后恢复

**4. BlockIterator — 共享世界扫描**：
- 每 tick 扫描一次世界，分发给所有已注册回调
- 使用对象池减少 GC 压力

**5. 完整的方块破坏速度计算**：
```java
getDestroySpeed(item, state) {
    speed = item.getDestroySpeed(state);
    if (efficiency > 0) speed += efficiency² + 1;
    if (haste) speed *= (1 + 0.2 * hasteLevel);
    if (miningFatigue) speed *= (0.3^fatigueLevel);
    if (water) speed *= SUBMERGED_MINING_SPEED;
    if (!onGround) speed /= 5;
}
```

**我们可以学习的**：
- `FindItemResult` 记录类型 → 改进 InventoryUtils 返回值
- 优先级旋转队列 → 我们的 lookAt 缺乏优先级管理
- `InvUtils.Action` 流畅建造器 → 改进 GiveItemAction 的容器操作
- 完整的工具速度计算 → 改进 ToolSet
- BlockIterator 共享扫描 → 改进 WorldScanner

---

### 2.8 EMI — 合成配方索引和自动合成树

**两阶段索引构建**：
1. **同步阶段**（立即响应 UI）：创建基础索引，无排序
2. **后台工作线程**：完整排序后原子交换

**核心数据结构**：

```
EmiRecipes.Manager
  ├── byInput  — Map<EmiStack, List<EmiRecipe>> (O(1) 查找)
  ├── byOutput — Map<EmiStack, List<EmiRecipe>> (O(1) 查找)
  ├── byCategory — Map<EmiRecipeCategory, List<EmiRecipe>>
  ├── byId — Map<Identifier, EmiRecipe>
  └── byWorkstation — Map<EmiStack, List<EmiRecipe>> (独立的工作站查找)
```

**两个哈希策略**：
- `StrictHashStrategy` — 用于去重（严格的 NBT 比较）
- `ComparisonHashStrategy` — 用于配方查找（可配置的比较方式）

**标签折叠优化**：将匹配的栈列表折叠回标签引用，节省内存。

**自动合成树 (MaterialTree)**：
```
MaterialTree
  └── MaterialNode (目标物品)
        ├── recipe: EmiRecipe
        ├── children: List<MaterialNode>
        ├── amount / divisor（数量计算）
        ├── consumeChance / produceChance（概率）
        ├── catalyst（催化剂，不消耗）
        └── remainder（剩余物，如空桶）
```

**TreeCost 计算**：
- 递归遍历合成树
- 跟踪剩余物（例如水桶配方的空桶可被其他配方消耗）
- 理想批次计算（LCM 法最小化浪费）
- 进度追踪（已有 vs 需要）

**我们可以学习的**：
- 两阶段索引构建 → AsyncRecipeIndex
- 分离输入/输出索引 → 改进 RecipeIndex 的 byOutput/byInput
- 标签折叠内存优化
- MaterialTree 自动合成树 → 可让 LLM 使用复杂合成链

---

### 2.9 JEI — 配方索引标准实现

**核心索引结构**：

```
RecipeManagerInternal
  ├── RecipeTypeDataMap — 按配方类型分组
  ├── RecipeIngredientRoleMap (per INPUT/OUTPUT/CRAFTING_STATION)
  │     ├── RecipeIngredientTable — 按配方类型 + 原料 O(1) 查找
  │     │     └── IngredientToRecipesMap — HashMap<uid, ArrayList<Recipe>>
  │     └── ingredientUidToCategoryMap — Multimap（原料 → 配方类型）
  ├── PluginManager — 插件链（internal + external）
  └── hiddenRecipeTypes — 隐藏类型集合
```

**UID 系统**（最核心的设计）：
```
物品 → Item 作为基础 key
  + ISubtypeInterpreter.getSubtypeData() — 自定义子类型数据
  + UidContext.Ingredient (更具体) vs UidContext.Recipe (更宽松)
  = 稳定的 HashMap key → O(1) 配方查找
```

**插件系统**：
- `IRecipeManagerPlugin` — 自定义配方查找
- 安全执行（超时 10ms 自动禁用）
- 异常隔离

**我们可以学习的**：
- UID/子类型系统 → 改进 RecipeIndex 的 NBT 感知匹配
- 插件系统 → 让模组集成添加自定义配方类型
- `UidContext.Recipe` 宽松匹配 → 忽略损坏值等非关键属性
- 后注册压缩 (ArrayList.trimToSize())

---

## 三、当前项目深度审查

### 3.1 架构概览

```
AIMod 项目 (Minecraft 1.21.1 NeoForge)

┌──────────────────────────────────────────────────────────┐
│  AIBotEntity (extends Mob)                               │
│  ├─ 世界中可见实体，渲染为玩家外观                          │
│  ├─ 懒加载 FakePlayer                                     │
│  ├─ 每 tick 同步位置到 FakePlayer                          │
│  └─ 3 格范围自动拾取物品                                   │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  FakePlayer (extends ServerPlayer)               │   │
│  │  ├─ BotAIManager — AI 管理器                      │   │
│  │  ├─ MovementController — 集中化移动控制             │   │
│  │  ├─ 注册到服务器玩家列表 (placeNewPlayer)           │   │
│  │  ├─ 无敌 (hurt() 返回 false)                       │   │
│  │  └─ 死亡后 40 tick 自动重生                         │   │
│  └──────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### 3.2 模块分析

#### AI 系统 (com.aimod.ai)

| 类 | 功能 | 评价 |
|----|------|------|
| `BotAIManager` | LLM 响应解析 → 动作序列 | 核心枢纽，处理 fallback 和 replan |
| `Task` | 任务数据模型 | 完整的生命周期管理 |
| `TaskFeedback` | 任务状态反馈 (i18n) | 中文/英文双语 |
| `WorldScanner` | 附近方块/实体/玩家扫描 | O(n³) 暴力扫描，无缓存 |
| `InventoryUtils` | 背包操作工具 | 基础功能，缺乏 FindItemResult |
| `RecipeIndex` | 合成配方索引 O(1) | 基础实现，缺乏 NBT 感知 |

#### LLM 服务 (com.aimod.ai.llm)

| 类 | 功能 | 评价 |
|----|------|------|
| `LLMService` | HTTP 调用 OpenAI 兼容 API | 完善的 rate limiting + retry |
| `LLMResponse` | 响应数据模型 | 支持成功/失败两种状态 |
| `LLMResponseParser` | 响应解析器 | 宽松解析 + 正则回退 |
| `ActionDescriptor` | 动作描述符 | JSON 参数封装 |

**LLMService 亮点**：
- ✅ 滑动窗口限流器 (RateLimiter)
- ✅ 指数退避 + 抖动重试
- ✅ HTTP/2 连接复用
- ✅ 健康检查缓存 (AtomicReference)
- ✅ SSE 流式响应支持
- ✅ 状态码 429/5xx 重试

#### 寻路系统 (com.aimod.ai.pathing)

| 类 | 功能 | 评价 |
|----|------|------|
| `Pathfinder` | A* 核心算法 | 基本实现 |
| `PathNode` | 路径节点 | 标准 A* 节点 |
| `PathResult` | 路径结果 | 包含长度和探索节点数 |
| `PathExecutor` | 路径执行器 | 逐步移动 + 卡住检测 |
| `BinaryHeapOpenSet` | 二叉堆开放列表 | 效率优化 |
| `MoveCost` | 移动代价计算 | 多格跌落、穿墙检测 |
| `MovementHelper` | 移动辅助 | 方块可通行性判断 |
| `ToolSet` | 工具选择 | 基础实现，缺乏附魔/药水 |
| `AsyncPathfinder` | 异步寻路 | ✅ 新实现，后台线程非阻塞 |

#### 移动系统 (com.aimod.ai.movement)

| 类 | 功能 | 评价 |
|----|------|------|
| `BotMovement` | Movement 基类 | PENDING→PREPPING→RUNNING→COMPLETE/FAILED |
| `MovementTraverse` | 平地行走 + 搭桥 | ✅ 含搭桥放置 + 卡住检测 |
| `MovementPillar` | 搭柱向上 | ✅ 含方块放置 |
| `MovementController` | 集中化移动控制 | ✅ A* 寻路 + 直接移动 fallback |
| `UnstuckDetector` | 卡住检测 | ✅ 5 级升级恢复策略 |

**MovementController 设计**：
```
navigateTo(target)
  └── asyncPathfinder.requestPath() → onPathComputed()
        ├── PathExecutor (有路径时)
        └── directMovement (无路径时 fallback)
  └── tick()
        ├── asyncPathfinder.tick() → 交付结果
        ├── PathExecutor.tick() → moveToward(next)
        ├── UnstuckDetector.tick() → executeRecovery()
        └── directMovement → moveToward(target)
```

#### 动作系统 (com.aimod.ai.action)

共 14 种动作：

| 动作 | 功能 | 寻路 | 复杂度 |
|------|------|------|--------|
| `MoveToAction` | 寻路到坐标 | Async A* | 低 |
| `BreakBlockAction` | 破坏方块 | — | 低 |
| `PlaceBlockAction` | 放置方块 | — | 低 |
| `AttackAction` | 攻击实体 | 追踪移动 | 中 |
| `CraftAction` | 合成物品 | — | 中 |
| `FollowAction` | 跟随玩家 | 实时追踪 | 中 |
| `GiveItemAction` | 给予物品 | — | 中 |
| `RequireItemsAction` | 门控检查 | — | 低 |
| `SayAction` | 聊天消息 | — | 低 |
| `WaitAction` | 等待 tick | — | 低 |
| `MineBlockAction` | 挖掘矿石 | A* | 高 |
| `GatherResourceAction` | 采集资源 | A* + 障碍破坏 | 很高 |
| `InteractBlockAction` | 与方块交互 | — | 低 |
| `EquipItemAction` | 装备物品 | — | 低 |

**GatherResourceAction 的复杂特性**（展示项目成熟度）：
- ✅ 3 种导航策略（邻近位置、A* 路径、直接移动）
- ✅ 卡住时的障碍物破坏
- ✅ 动态搜索半径扩展（32→64→96→128）
- ✅ 水中脱困
- ✅ 搭柱上升
- ✅ 挖掘附近方块获取搭柱材料
- ✅ 目标筛选（偏好同 Y 高度）
- ✅ 可配置的垃圾方块判定

#### FakePlayer 系统 (com.aimod.fakeplayer)

| 类 | 功能 | 评价 |
|----|------|------|
| `FakePlayer` | 核心假人 | ✅ placeNewPlayer 注册 |
| `FakePlayerManager` | 生命周期管理 | ✅ |
| `FakePlayerNetHandler` | 网络处理 | ✅ 丢弃数据包 |
| `FakeClientConnection` | EmbeddedChannel | ✅ |
| `FakeConnection` | 旧版连接 | ⚠️ 未使用 |
| `FakeNetHandler` | 旧版处理器 | ⚠️ 未使用 |
| `BotProfileStore` | UUID 持久化 | ✅ |

**与 SiliconeDolls 对比发现的问题**：
- ❌ 缺少 Mixin 层网络防御
- ❌ 在构造函数中手动设置 NetHandler（而非通过 Mixin 替换）
- ❌ 无 GameProfile 皮肤获取
- ❌ 无 Config Phase 跳过
- ⚠️ FakeConnection/FakeNetHandler 死代码未清理

---

## 四、跨维度综合对比

### 4.1 实体模型对比

| 维度 | AI Mod | SiliconeDolls | PlayerEngine | Player2NPC | AI-Player |
|------|--------|-------------|-------------|-----------|-----------|
| 实体类型 | Mob + ServerPlayer | ServerPlayer | LivingEntity | LivingEntity | ServerPlayer |
| 注册方式 | placeNewPlayer | placeNewPlayer | 自定义 | 自定义 | placeNewPlayer |
| 世界可见 | ✅ AIBotEntity | ❌ | ❌ | ✅ AutomatoneEntity | ✅ |
| 玩家列表 | ✅ | ✅ | ❌ | ❌ | ✅ |
| 完整玩家能力 | ✅ (ServerPlayer) | ✅ | ⚠️ (模拟) | ⚠️ (模拟) | ✅ |
| AI 系统 | LLM + Task + Action | PlayerActionPack | Baritone | AltoClef | RL + NLP + LLM |
| 网络防御 | 1 层 | 4 层 | N/A (纯服务端) | N/A (纯服务端) | 1-2 层 |

### 4.2 移动系统对比

| 维度 | AI Mod | Baritone | Meteor Client |
|------|--------|----------|---------------|
| Movement 类型 | 2 种 (Traverse, Pillar) | 8 种 | 直接移动 (WASD) |
| 寻路 | Async A* | Async A* (双缓冲) | 简单前方探测 |
| 世界缓存 | ❌ | CachedWorld (512x512) | ❌ |
| 卡住处理 | UnstuckDetector (5 级) | 重新计算路径 | 无 |
| 代价计算 | MoveCost | CalculationContext 快照 | 无 |
| 搭桥 | ✅ | ✅ | ❌ |
| 搭柱 | ✅ | ✅ | ❌ |
| 跌落处理 | 自由跌落 | MovementFall (水桶) | ❌ |
| 输入方式 | setDeltaMovement | InputOverrideHandler | CustomPlayerInput (按键) |

### 4.3 AI/规划系统对比

| 维度 | AI Mod | Player2NPC | AI-Player | Baritone |
|------|--------|-----------|-----------|----------|
| 任务理解 | LLM (云端) | 预设命令 + Chain | NLP + LLM | 预设 Process |
| 动作序列 | LLM 解析 JSON | Task + Chain | ActionGraph | Process 预设 |
| 行为优先级 | ❌ (顺序执行) | 9 条 Chain | Markov Chain | Process 优先级 |
| 学习能力 | ❌ (无反馈学习) | ❌ | ✅ (Q-learning) | ❌ |
| 风险感知 | ❌ | WorldSurvivalChain | SequenceRiskAnalyzer | ❌ |
| 危险检测 | ❌ | ❌ | Cliff/LavaDetector | ❌ |
| 自动进食 | ❌ | FoodChain | ❌ | ❌ |
| 自动防御 | ❌ | MobDefenseChain | ❌ | ❌ |
| 水桶防摔 | ❌ | MLGBucketFallChain | ❌ | MovementFall |

### 4.4 配方/合成系统对比

| 维度 | AI Mod | JEI | EMI |
|------|--------|-----|-----|
| 索引方式 | byOutput + byInput HashMap | UID-based HashMap | EmiStack-based HashMap |
| 子类型感知 | ❌ (仅 Item 匹配) | ✅ (ISubtypeInterpreter) | ✅ (Comparison 系统) |
| NBT 感知 | ❌ | ✅ (UidContext 切换) | ✅ (Strict vs Comparison hash) |
| 标签匹配 | ✅ | ✅ | ✅ (含标签折叠) |
| 后台构建 | ❌ | ✅ (同步) | ✅ (两阶段) |
| 自动合成树 | ❌ | ❌ | ✅ (MaterialTree) |
| 可扩展性 | ❌ | ✅ (Plugin 链) | ✅ (Plugin 系统) |
| 内存优化 | ❌ | ✅ (trimToSize) | ✅ (FastUtil + 标签折叠) |
| 催化剂/消耗物区分 | ❌ | ✅ | ✅ |

### 4.5 配置/基础设施对比

| 维度 | AI Mod | RollingGate | SiliconeDolls |
|------|--------|-------------|---------------|
| 配置方式 | NeoForge ModConfigSpec | 注解驱动 @Rule | 依赖 RollingGate |
| 命令自动生成 | ❌ | ✅ (Brigadier) | ✅ (继承自 RG) |
| 事件系统 | ❌ | ✅ (可取消变更事件) | ✅ |
| 箱子 UI | ❌ | ✅ (CustomChestMenu) | ✅ (继承自 RG) |
| 世界级配置 | ❌ | ✅ (全局+世界双层) | ✅ (继承自 RG) |
| 规则验证 | NeoForge 内置 | RGValidator 管道 | ✅ (继承自 RG) |

---

## 五、功能差距分析

### 按优先级排序的完整差距表

| # | 功能 | 来源参考 | 当前状态 | 优先级 | 工作量 |
|---|------|---------|---------|--------|--------|
| 1 | **Mixin 层网络防御** | SiliconeDolls | ❌ 缺失 | **P0** | 2-3 天 |
| 2 | **Config Phase 跳过** | SiliconeDolls | ❌ 缺失 | **P0** | 0.5 天 |
| 3 | **GameProfile 皮肤获取** | SiliconeDolls | ❌ 缺失 | **P0** | 1 天 |
| 4 | **NetHandler 正确初始化** | SiliconeDolls | ⚠️ 构造函数手动设置 | **P0** | 1 天 |
<<<<<<< Updated upstream
| 5 | **更多 Movement 类型** | Baritone | 2/8 种 | **P1** | 2-3 天 |
| 6 | **CalculationContext 代价快照** | Baritone | ✅ 已实现 | **P1** | 已完成 |
=======
| 5 | **更多 Movement 类型** | Baritone | 8/8 种 ✅ | **P1** | 已完成 |
| 6 | **CalculationContext 代价快照** | Baritone | ❌ | **P1** | 1 天 |
>>>>>>> Stashed changes
| 7 | **CachedWorld 世界缓存** | Baritone | ❌ | **P1** | 3-4 天 |
| 8 | **行为链/优先级系统** | Player2NPC | ❌ | **P1** | 3-4 天 |
| 9 | **完整持久化** | SiliconeDolls | 仅 UUID | **P1** | 2 天 |
| 10 | **RecipeIndex NBT 感知** | JEI/EMI | ❌ | **P1** | 2 天 |
| 11 | **自动进食链** | Player2NPC | ❌ | **P2** | 1 天 |
| 12 | **怪物防御链** | Player2NPC | ❌ | **P2** | 2 天 |
| 13 | **水桶防摔链** | Player2NPC | ❌ | **P2** | 1 天 |
| 14 | **DangerZone 检测** | AI-Player | ❌ | **P2** | 1 天 |
| 15 | **FindItemResult 模式** | Meteor Client | ⚠️ 基础 int 返回 | **P2** | 0.5 天 |
| 16 | **完整的工具速度计算** | Meteor/Baritone | ✅ ToolSet 已实现（含附魔效率） | **P2** | 已完成 |
| 17 | **BlockIterator 共享扫描** | Meteor Client | ❌ (直接扫描) | **P2** | 2 天 |
| 18 | **规则引擎 (配置)** | RollingGate | ⚠️ 已有 ModConfig | **P2** | 2 天 |
| 19 | **皮肤自定义** | Player2NPC | ❌ | **P3** | 1 天 |
| 20 | **GUI 状态界面** | SiliconeDolls | ❌ | **P3** | 3 天 |
| 21 | **MaterialTree 自动合成树** | EMI | ❌ | **P3** | 4 天 |
| 22 | **RL 学习能力** | AI-Player | ❌ | **P3** | 7+ 天 |
| 23 | **Auto Fish/Tool/Replenish** | SiliconeDolls | ❌ | **P3** | 2 天 |

---

## 六、可吸收实现的详细方案

### 6.1 P0 — FakePlayer 基础设施修复

#### 6.1.1 Mixin 层网络防御

**问题**：当前 FakePlayer 只有 FakePlayerNetHandler.send() 一层空实现，缺少多层防御。可能的副作用：某些代码路径直接访问 Channel 或通过其他分发器发送包。

**方案**：从 SiliconeDolls 吸收 4 个关键 Mixin：

```java
// 1. PlayerListMixin — 在 placeNewPlayer 中替换 handler
@Mixin(PlayerList.class)
public class PlayerListMixin {
    @WrapOperation(method = "placeNewPlayer")
    private ServerGamePacketListenerImpl wrapPlaceNewPlayerHandler(
            Connection connection, ServerPlayer player,
            CommonListenerCookie cookie,
            Operation<ServerGamePacketListenerImpl> original) {
        ServerGamePacketListenerImpl handler = original.call(connection, player, cookie);
        if (player instanceof FakePlayer) {
            return new FakePlayerNetHandler(
                player.getServer(), connection, player, cookie);
        }
        return handler;
    }
}

// 2. ServerCommonPacketListenerImplMixin — 抑制 send
@Mixin(ServerCommonPacketListenerImpl.class)
public class ServerCommonPacketListenerImplMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), cancellable = true)
    public void onSend(Packet<?> packet, CallbackInfo ci) {
        if ((Object) this instanceof FakePlayerNetHandler) {
            ci.cancel();
        }
    }
}

// 3. ServerConfigurationPacketListenerImplMixin — 跳过 config phase
@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ServerConfigurationPacketListenerImplMixin {
    @Inject(method = "handleConfigurationFinished",
            at = @At("HEAD"), cancellable = true)
    public void onConfigFinished(
            ServerboundFinishConfigurationPacket packet,
            CallbackInfo ci) {
        if (player instanceof FakePlayer) {
            ci.cancel();
        }
    }
}

// 4. ConnectionMixin — 注入 setChannel
@Mixin(Connection.class)
public class ConnectionMixin implements IConnectionInjector {
    @Unique
    private Channel channel;
    
    @Override
    public void setChannel(Channel channel) {
        this.channel = channel;
    }
    
    @Inject(method = "channel()Lio/netty/channel/Channel;",
            at = @At("HEAD"), cancellable = true)
    public void getChannel(CallbackInfoReturnable<Channel> cir) {
        if (channel != null) {
            cir.setReturnValue(channel);
        }
    }
}
```

#### 6.1.2 FakePlayer 构造函数修复

**当前问题**：在 FakePlayer 构造函数中手动创建 FakePlayerNetHandler
```java
// ❌ 当前做法
private FakePlayer(...) {
    super(server, level, profile, clientInfo);
    this.connection = new FakePlayerNetHandler(...);  // 手动设置
}
```

**修正方案**：
```java
// ✅ 正确做法
private FakePlayer(MinecraftServer server, ServerLevel level,
                   GameProfile profile, ClientInformation clientInfo) {
    super(server, level, profile, clientInfo);
    // 让 placeNewPlayer() 创建 handler，通过 Mixin 替换
    // 不再此处手动设置 connection
}
```

在 `createAndRegister()` 中由 `placeNewPlayer()` 通过 Mixin 自动处理。

#### 6.1.3 GameProfile 皮肤获取

**方案**：在 `createAndRegister()` 中添加皮肤获取

```java
// 在 createAndRegister() 中添加
GameProfile profile = server.getProfileCache().get(name).orElse(null);
if (profile == null) {
    UUID uuid = UUIDUtil.createOfflinePlayerUUID("AI:" + name);
    profile = new GameProfile(uuid, name);
} else {
    // 异步获取皮肤
    SkullBlockEntity.fetchGameProfile(name).thenAcceptAsync(profileWithSkin -> {
        if (profileWithSkin.isPresent()) {
            // 皮肤已经包含在 GameProfile 的属性中
        }
    }, server);
}
```

---

### 6.2 P1 — 移动和寻路增强

#### 6.2.1 更多 Movement 类型

当前有 2 种 Movement（Traverse + Pillar），需增加：

```java
// MovementFall — 跌落
public class MovementFall extends BotMovement {
    // 检测水、梯子、蜘蛛网等可安全跌落的方式
    // 如果可能摔伤，尝试放置水桶 (MLG)
    // 代价与跌落距离成正比（可能受伤）
}

// MovementAscend — 斜坡上升
public class MovementAscend extends BotMovement {
    // 前方 1 格高台阶
    // 自动跳跃 + 前进
    // 检查头顶无阻挡
}

// MovementDescend — 斜坡下降
public class MovementDescend extends BotMovement {
    // 前方阶梯下降
    // 大多数情况与 Traverse 相同
    // 但需要对大落差特殊处理
}
```

完整的 Movement 类型创建 A* 路径时由 Pathfinder 自动选择最优组合。

#### 6.2.2 CalculationContext 代价快照

```java
public class CalculationContext {
    public final ServerLevel world;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway; // 搭桥/搭柱用方块
    public final boolean canSprint;
    public final boolean allowBreak;    // 是否可破坏障碍方块
    public final boolean allowPlace;    // 是否可放置方块
    public final ToolSet toolSet;
    
    public static CalculationContext from(FakePlayer bot) {
        return new CalculationContext(
            (ServerLevel) bot.level(),
            hasWaterBucket(bot),
            hasThrowawayBlock(bot),
            true,   // canSprint
            true,   // allowBreak (可配置)
            true,   // allowPlace (可配置)
            new ToolSet(bot)
        );
    }
}
```

#### 6.2.3 世界缓存 (ChunkCache)

```java
public class ChunkCache {
    // 区域级缓存，每区域 512x512 或更小
    private final Map<Long, CachedRegion> regions = new HashMap<>();
    
    // O(1) 方块查询
    public BlockState getBlockState(BlockPos pos) {
        CachedRegion region = getRegion(pos);
        return region != null ? region.getBlockState(pos) : world.getBlockState(pos);
    }
    
    // 监听区块加载/卸载事件，自动更新
    @SubscribeEvent
    public void onChunkLoad(ChunkEvent.Load event) {
        // 更新缓存
    }
}
```

---

### 6.3 P1 — 行为链系统

从 Player2NPC 吸收的优先级链系统：

```java
public abstract class BehaviorChain {
    protected final int priority;          // 优先级 (1-9，1 最高)
    protected final FakePlayer bot;
    
    /** 检查是否应激活此链 */
    public abstract boolean shouldActivate();
    
    /** 执行此链的行为 */
    public abstract void tick();
    
    /** 此链是否正在活跃 */
    public abstract boolean isActive();
}

// 示例：FoodChain
public class FoodChain extends BehaviorChain {
    private static final int HUNGER_THRESHOLD = 14;
    
    public FoodChain(FakePlayer bot) {
        super(4, bot);  // 优先级 4
    }
    
    @Override
    public boolean shouldActivate() {
        return bot.getFoodData().getFoodLevel() <= HUNGER_THRESHOLD;
    }
    
    @Override
    public void tick() {
        // 1. 在背包中查找最佳食物
        // 2. 切换到该物品
        // 3. 食用
    }
}

// ChainManager — 管理所有链
public class ChainManager {
    private final List<BehaviorChain> chains = new ArrayList<>();
    
    public void tick() {
        // 按优先级排序，找到第一个应激活的链
        for (BehaviorChain chain : chains) {
            if (chain.shouldActivate() || chain.isActive()) {
                chain.tick();
                break;  // 同一时间只运行一个链
            }
        }
    }
}
```

**建议的链优先级**：
1. **紧急链** (P1) — 死亡/坠落/溺水自救
2. **防御链** (P2) — 怪物攻击/玩家攻击
3. **生存链** (P3) — 自动进食、治疗
4. **修复链** (P4) — 卡住自救、交互修复
5. **环境链** (P5) — 避开水/岩浆/悬崖
6. **用户链** (P6) — 用户指定的 LLM 任务（正常执行）

---

### 6.4 P1 — RecipeIndex 增强

从 JEI/EMI 吸收的关键改进：

```java
public class RecipeIndex {
    // 已有的 byOutput / byInput
    private final Map<Item, List<RecipeHolder<?>>> byOutput;
    private final Map<Item, List<RecipeHolder<?>>> byInput;
    
    // 新增：基于 UID 的更精确匹配
    private final Map<Object, List<RecipeHolder<?>>> byOutputUid;
    private final Map<Object, List<RecipeHolder<?>>> byInputUid;
    
    // 新增：标签索引
    private final Map<TagKey<Item>, List<RecipeHolder<?>>> byTag;
    
    // 新增：催化剂索引（熔炉燃料等）
    private final Map<Item, List<RecipeHolder<?>>> byCatalyst;
    
    // 计算物品 UID
    private Object computeUid(ItemStack stack) {
        Item item = stack.getItem();
        // 如果有 NBT 子类型（如附魔书），返回复合 key
        Object subtype = getSubtypeData(stack);
        return subtype != null ? List.of(item, subtype) : item;
    }
    
    // 后台构建
    private volatile boolean indexing;
    public void buildAsync() {
        Thread.ofVirtual().start(() -> {
            indexing = true;
            // 构建索引
            indexing = false;
        });
    }
}
```

---

### 6.5 P2 — 工具和改进

#### 6.5.1 FindItemResult 模式

```java
public record FindItemResult(int slot, int count) {
    public boolean found() { return slot != -1; }
    public boolean isHotbar() { return slot >= 0 && slot < 9; }
    public boolean isMainInventory() { return slot >= 9 && slot < 36; }
    public boolean isArmor() { return slot >= 36 && slot < 40; }
    public boolean isOffhand() { return slot == 40; }
    
    public InteractionHand getHand() {
        if (slot == 40) return InteractionHand.OFF_HAND;
        if (slot >= 0 && slot < 9) return InteractionHand.MAIN_HAND;
        return null;
    }
}

// 在 InventoryUtils 中使用
public static FindItemResult find(Player player, Item... items) {
    // 搜索优先级：副手 > 主手 > 快捷栏 > 主背包
    // ...
}
```

#### 6.5.2 DangerZone 检测

```java
public class DangerDetector {
    // 悬崖检测 — 检查前方是否有大落差
    public static boolean isCliffAhead(FakePlayer bot, int maxDrop) {
        Vec3 lookVec = bot.getLookAngle();
        BlockPos ahead = bot.blockPosition().offset(
            (int)(lookVec.x * 3), 0, (int)(lookVec.z * 3));
        
        // 向下扫描查找实心方块
        for (int y = ahead.getY(); y > ahead.getY() - (maxDrop + 1); y--) {
            BlockPos check = new BlockPos(ahead.getX(), y, ahead.getZ());
            if (bot.level().getBlockState(check).isSolid()) {
                return Math.abs(y - ahead.getY()) > maxDrop;
            }
        }
        return true; // 无实心方块 → 悬崖
    }
    
    // 熔岩检测
    public static boolean isLavaNearby(FakePlayer bot, int radius) {
        return WorldScanner.scanForFluid(bot, Fluids.LAVA, radius);
    }
}
```

---

## 七、实施路线图

### 阶段 1：基础设施修复 (P0, ~5 天)

```
Week 1: FakePlayer 稳固性
├── Day 1-2: 添加 4 个关键 Mixin
│   ├── PlayerListMixin (handler 替换)
│   ├── ServerCommonPacketListenerImplMixin (send 拦截)
│   ├── ServerConfigurationPacketListenerImplMixin (config 跳过)
│   └── ConnectionMixin (EmbeddedChannel 注入)
├── Day 3: 修复 FakePlayer 构造函数
│   ├── 移除构造函数中的手动 connection 设置
│   └── 在 createAndRegister 中让 placeNewPlayer 通过 Mixin 处理
├── Day 4: 添加 GameProfile 皮肤获取
│   ├── SkullBlockEntity.fetchGameProfile() 集成
│   └── 异步皮肤下载
└── Day 5: 清理死代码 + 测试
    ├── 移除 FakeConnection.java / FakeNetHandler.java
    └── 集成测试
```

### 阶段 2：移动和寻路增强 (P1, ~8 天)

```
Week 2-3: Movement 系统
├── Day 1-2: MovementAscend + MovementDescend
├── Day 3: MovementFall (含水桶防摔)
├── Day 4: CalculationContext 代价快照
├── Day 5-6: ChunkCache 世界缓存
│   ├── CachedRegion 实现
│   ├── 区块加载/卸载事件监听
│   └── O(1) 方块查询
├── Day 7: 将 ChunkCache 集成到 Pathfinder
└── Day 8: 性能测试 + 调优
```

### 阶段 3：行为链系统 (P1-P2, ~8 天)

```
Week 3-4: Chain 系统 + 持久化
├── Day 1-2: BehaviorChain 基类 + ChainManager
├── Day 3: FoodChain (自动进食)
├── Day 4: DefenseChain (怪物/玩家防御)
├── Day 5: DangerChain (避开水/岩浆/悬崖)
├── Day 6: 完整持久化 (NBT 位置、背包、任务状态)
│   └── /bot save/load 命令
├── Day 7: FindItemResult + 工具速度计算
└── Day 8: RecipeIndex UID 系统 + NBT 感知
```

### 阶段 4：高级功能 (P3, ~10 天)

```
Week 5-6: 可选高级功能
├── BlockIterator 共享世界扫描
├── 规则引擎 (RollingGate 模式)
├── 皮肤自定义
├── GUI 状态界面
├── MaterialTree 自动合成树
├── Auto Fish/Tool/Replenish
└── RL 学习探索
```

---

## 八、风险与注意事项

### 8.1 Mixin 兼容性

- Mixin 是侵入式的，需要确保与其他模组兼容
- 使用 `@WrapOperation` (而非 `@Overwrite`) 保持最大兼容性
- 添加 `mixin.plugin.json` 中的 `refmap` 配置

### 8.2 架构差异

**不要盲目照搬 PlayerEngine 的架构**。我们的 `FakePlayer(ServerPlayer) + AIBotEntity(Mob)` 与 `LivingEntity + 接口` 是两个不同的设计理念：
- 我们的优势：ServerPlayer 原生支持（背包、交互、合成、方块破坏等）
- 我们的劣势：AIBotEntity 和 FakePlayer 之间的同步开销
- **建议**：保持当前架构，但吸收接口抽象的**思想**（让 Action 依赖接口而非具体类）

### 8.3 客户端 vs 服务端

- Baritone 是客户端模组 (`InputOverrideHandler`、`PlayerMovementInput`)
- 我们是服务端模组 (`setDeltaMovement`、`move(MoverType.SELF)`)
- **关键**：服务端无法模拟客户端输入，必须直接操作位置/速度
- Movement 系统需继续使用当前 `setDeltaMovement + move()` 方式

### 8.4 性能考量

- ChunkCache：每个区域 512x512 可能有内存压力，建议减小到 256x256
- BehaviorChain：每 tick 检查所有链可能有性能影响，使用轮询间隔
- Mixin：每个 Mixin 都是性能热点，避免在热路径上使用

### 8.5 向后兼容

- 新 Movement 类型通过 Pathfinder 自动选择，不破坏现有 Action
- 行为链默认不激活（除非配置开启），不改变现有行为
- 持久化格式使用 JSON（易于迁移）

---

## 附录 A：项目文件快速索引

| 项目 | 位置 | 核心关注点 |
|------|------|-----------|
| Baritone | `baritone-ref/` | `pathing/movement/`, `behavior/PathingBehavior.java`, `cache/CachedWorld.java`, `utils/ToolSet.java` |
| PlayerEngine | `PlayerEngine-main/` | `api/entity/` (接口), `utils/player/` (EntityContext) |
| Player2NPC | `Player2NPC-master/` | `companion/AutomatoneEntity.java`, `chains/` (AltoClef chains) |
| SiliconeDolls | `SiliconeDolls-releases-1.21/` | `entity/FakePlayer.java`, `entity/PlayerActionPack.java`, `mixin/` |
| RollingGate | `RollingGate-releases-1.21/` | `api/RGRuleManager.java`, `api/server/ServerRGRuleManager.java` |
| AI-Player | `AI-Player-1.21.1/` | `GameAI/planner/`, `DangerZoneDetector/`, `FunctionCaller/` |
| Meteor Client | `meteor-client-master/` | `utils/player/InvUtils.java`, `utils/player/Rotations.java`, `utils/world/BlockUtils.java` |
| EMI | `emi-1.21/` | `registry/EmiRecipes.java`, `bom/MaterialTree.java`, `bom/TreeCost.java` |
| JEI | `JustEnoughItems-26.1/` | `Library/.../recipes/RecipeManagerInternal.java`, `Library/.../recipes/collect/IngredientToRecipesMap.java` |

## 附录 B：关键参考代码片段索引

| 功能 | 参考文件 | 行号（近似） |
|------|---------|------------|
| placeNewPlayer 用法 | SiliconeDolls/FakePlayer.java | createFake() 方法 |
| PlayerListMixin | SiliconeDolls/PlayerListMixin.java | 全文 |
| 4 层网络防御 | SiliconeDolls/mixin/ | PacketDistributor, ServerCommonPacketListenerImpl, ServerConfigurationPacketListenerImpl |
| Movement 基类 | baritone-ref/Movement.java | 全文 |
| PathingBehavior 双缓冲 | baritone-ref/PathingBehavior.java | 全文 |
| CachedWorld | baritone-ref/CachedWorld.java | 全文 |
| Chain 系统 | Player2NPC/chains/ | 各 Chain 文件 |
| 规则引擎注解扫描 | RollingGate/ServerRGRuleManager.java | compileContent() |
| 箱子菜单 UI | RollingGate/tools/chest/menu/CustomChestMenu.java | 全文 |
| FindItemResult | Meteor/InvUtils.java | 内部 record 定义 |
| InvUtils.Action 建造器 | Meteor/InvUtils.java | Action 内部类 |
| Rotations 优先级队列 | Meteor/Rotations.java | rotate() + onSendMovementPacketsPre() |
| DangerZone 检测 | AI-Player/DangerZoneDetector/ | CliffDetector, LavaDetector |
| Q-learning | AI-Player/GameAI/RLAgent.java | 全文 |
| MaterialTree 自动合成 | EMI/bom/MaterialTree.java | 全文 |
| UID 子类型系统 | JEI/Library/.../IngredientToRecipesMap.java | 全文 |
| 两阶段索引构建 | EMI/registry/EmiRecipes.java | bake() + Worker |

---

## 附录 C：开发变更日志

| 版本 | 日期 | 关键变更 |
|------|------|---------|
| r29 | 2026-05-24 | i18n /ai_bot help 命令列表 + /ai_bot help <cmd> 详细示例 (中英文) |
| r28 | 2026-05-24 | 27 条命令完整帮助文本 + 示例 |
| r27 | 2026-05-24 | pause/resume/cancel [name], toggle 命令, status 显示 chain+state |
| r26 | 2026-05-24 | LLM 增量规划: 动作失败时向 LLM 请求下一步而非中止任务 |
| r25 | 2026-05-24 | BotAIStateMachine 显式状态机 + 熔炼链 + 物品→矿石映射扩展 |
| r24 | 2026-05-24 | SequencePlanner 工具配给 (检查镐子) + 熔炉/燃料/中间产物复用 |
| r23 | 2026-05-24 | 本地规划器检测附近箱子库存 (8格内) |
| r22 | 2026-05-24 | 工作台在脚下放置+用完回收, InteractBlock 找不到时自动放置 |
| r21 | 2026-05-24 | 确定性本地规划器 (CommandParser + SequencePlanner + MaterialTree) 替代硬编码 fallback |
| r20 | 2026-05-24 | 智能补全 + goto 浮点坐标 + /ai_bot select + follow/inventory 支持指定 bot |
| r19 | 2026-05-24 | spawn [name], status 全部/指定, stop [name], remove <name>, Unstuck 自动取消 |
| r18 | 2026-05-24 | DangerChain A* 寻路逃离 + 搭桥 + 水桶 MLG + 任务自动重试 |
| r16 | 2026-05-24 | FollowAction A* 寻路绕过岩浆, DangerChain 分级(urgent/passive) |
| r15 | 2026-05-24 | DangerChain 取消任务 + 更强逃脱, Follow 保持距离 |
| r14 | 2026-05-24 | FollowAction 持续跟随 + /ai_bot inventory 命令 |
| r13 | 2026-05-24 | Follow 不停止 + BotStatusScreen CONSUME |
| r12 | 2026-05-24 | DangerChain cooldown + UnstuckChain idle guard + OpenCode 5 修复 |
| r11 | 2026-05-24 | 5 Mixin + moddev + 持久化 + RecipeIndex + ChunkCache + 行为链 + 8 Movement |
| - | 2026-05-24 | 9 参考项目综合分析文档创建 |
