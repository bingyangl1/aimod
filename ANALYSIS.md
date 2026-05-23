# 参考项目深度对比分析

> 本文档深入分析三个参考项目（PlayerEngine、Player2NPC、Baritone），与 AI Mod 对比，提炼可学习、吸收和复用的功能与实现。

---

## 一、参考项目概览

| 项目 | 仓库 | 定位 | 核心理念 |
|------|------|------|---------|
| PlayerEngine | Goodbird-git/PlayerEngine | 服务端 Baritone 分支（Automatone） | 将 Baritone 从客户端扩展到服务端，支持任意 LivingEntity |
| Player2NPC | Goodbird-git/Player2NPC | NPC 行为系统 | 基于 Automatone + AltoClef，将 LivingEntity 包装为可对话的 AI NPC |
| Baritone | cabaletta/baritone (1.21.1) | 自动化路径规划 | 业界标杆 A* 寻路 + Movement 系统 |

**关键发现**：PlayerEngine 不是独立的假人引擎，而是 Baritone 的服务端分叉（Automatone），核心创新是将 Baritone 的玩家绑定改为实体绑定。

---

## 二、PlayerEngine（Automatone）深度分析

### 2.1 架构核心：从 Player 到 Entity 的抽象

PlayerEngine 最大的创新是将 Baritone 从客户端玩家（LocalPlayer）扩展为服务端任意实体（LivingEntity）。

**核心接口体系**：
`
IAutomatone              — 标记接口，标识可使用 Baritone 的实体
IInventoryProvider       — 提供玩家级背包（LivingEntityInventory）
IInteractionManagerProvider — 提供玩家级交互管理（LivingEntityInteractionManager）
IHungerManagerProvider   — 提供饥饿管理（LivingEntityHungerManager）
IEntityContext           — 实体上下文（替代 IPlayerContext）
IInteractionController   — 交互控制器（替代 IPlayerController）
`

### 2.2 关键实现

#### LivingEntityInventory（实体背包）
- 完整复制 Player.Inventory 的功能（36 主背包 + 4 盔甲 + 1 副手）
- 支持 NBT 序列化/反序列化
- 支持 TagKey 物品查找
- 实现 Container 接口，可与其他容器交互

#### LivingEntityInteractionManager（实体交互管理）
- 完整的方块挖掘逻辑（开始/继续/停止挖掘）
- 方块放置逻辑
- 物品使用逻辑
- 水桶拾取/放置
- 游戏模式切换（创造/生存）

#### EntityContext（实体上下文）
- 替代 PlayerContext，适用于任意 LivingEntity
- 提供 feetPos()、world()、inventory()、playerController() 等
- 支持 mob 避让（Avoidance）系统

#### EntityInteractionController（实体交互控制器）
- 服务端方块破坏/放置
- 射线追踪（rayTraceTowards）
- 固定方块交互距离 4.5

### 2.3 线程池
`java
threadPool = new ThreadPoolExecutor(
    4, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
    new SynchronousQueue<>(),
    r -> new Thread(r, "Automatone Worker " + threadCounter.incrementAndGet())
);
`
- 核心线程 4 个，最大无限制
- 用于异步寻路和其他计算

### 2.4 与我们的对比

| 维度 | PlayerEngine | AI Mod | 差异 |
|------|-------------|--------|------|
| 实体模型 | LivingEntity + 接口 | AIBotEntity(Mob) + FakePlayer(ServerPlayer) | PE 更抽象，我们更具体 |
| 背包 | LivingEntityInventory（完整复制 Player.Inventory） | SimpleContainer(36) | PE 更完整 |
| 交互管理 | LivingEntityInteractionManager（完整） | 委托给 FakePlayer | 我们更简单 |
| 网络层 | 无（纯服务端） | FakeClientConnection + FakePlayerNetHandler | 我们需要（因为 FakePlayer 注册） |
| 寻路 | Baritone A*（已适配服务端） | 自研 A*（简化版） | PE 更成熟 |

---

## 三、Player2NPC 深度分析

### 3.1 架构：Automatone + AltoClef + Player2 API

Player2NPC 在 PlayerEngine（Automatone）基础上，集成了：
1. **AltoClef** — 任务/行为系统（Task + Chain + Tracker）
2. **Player2 API** — AI 对话系统（Character + ConversationManager）

### 3.2 AutomatoneEntity（NPC 实体）

`java
public class AutomatoneEntity extends LivingEntity
    implements IAutomatone, IInventoryProvider, IInteractionManagerProvider, IHungerManagerProvider
`

**关键设计**：
- 继承 LivingEntity（不是 ServerPlayer！）
- 通过接口组合的"玩家能力"（背包、交互、饥饿）
- 持有 AltoClefController（AI 控制器）
- 持有 Character（Player2 AI 角色）
- 支持 NBT 持久化（背包、位置、角色数据）

**物品拾取**：
`java
public void pickupItems() {
    // 3 格范围内拾取物品
    // 使用 getLivingInventory().insertStack() 存入背包
}
`

**攻击实现**：
`java
public boolean tryAttack(Entity target) {
    // 计算攻击力（含附魔）
    // 计算击退（含附魔）
    // 火焰附加
    // 应用伤害
}
`

### 3.3 AltoClefController（AI 控制器）

**核心组件**：
- **TaskRunner** — 任务执行器
- **TrackerManager** — 追踪器管理（实体、方块、区块、背包、合成配方）
- **Chain 系统** — 行为链（FoodChain、MobDefenseChain、MLGBucketFallChain、UnstuckChain 等）
- **CommandExecutor** — 命令执行器
- **InputControls** — 输入控制
- **SlotHandler** — 槽位管理
- **BotBehaviour** — 机器人行为配置

**Chain 系统详解**（行为链，优先级从高到低）：
1. **UserTaskChain** — 用户指定任务
2. **MobDefenseChain** — 怪物防御
3. **PlayerDefenseChain** — 玩家防御
4. **FoodChain** — 自动进食
5. **MLGBucketFallChain** — 水桶防摔
6. **UnstuckChain** — 卡住自救
7. **PreEquipItemChain** — 预装备
8. **WorldSurvivalChain** — 世界生存
9. **PlayerInteractionFixChain** — 交互修复

### 3.4 CompanionManager（伙伴管理）

- 管理 NPC 生命周期（召唤/解散/持久化）
- 异步获取角色数据（CompletableFuture）
- NBT 持久化（companion name -> UUID 映射）
- 每 tick 检查是否需要召唤

### 3.5 皮肤系统

- SkinManager — 管理皮肤纹理
- ImageDownloadAlt — 异步下载皮肤图片
- ResourceDownloader — 资源下载器
- 支持自定义皮肤 URL

### 3.6 GUI 系统

- CharacterSelectionScreen — 角色选择界面
- CharacterDetailScreen — 角色详情界面
- CharacterCardWidget — 角色卡片组件

### 3.7 与我们的对比

| 维度 | Player2NPC | AI Mod | 差异 |
|------|-----------|--------|------|
| AI 系统 | AltoClef（Task + Chain + Tracker） | LLM + Task + Action | P2N 更成熟，我们更灵活 |
| 行为优先级 | Chain 系统（9 条链） | 无优先级（顺序执行） | P2N 更完善 |
| 防御系统 | MobDefenseChain + PlayerDefenseChain | 无 | 我们缺失 |
| 自动进食 | FoodChain | 无 | 我们缺失 |
| 卡住自救 | UnstuckChain | 卡住超时跳过 | P2N 更智能 |
| 水桶防摔 | MLGBucketFallChain | 无 | 我们缺失 |
| 持久化 | NBT 完整持久化 | 仅 GameProfile | P2N 更完整 |
| 皮肤系统 | 自定义皮肤 URL | 固定 Steve 皮肤 | P2N 更灵活 |
| GUI | 角色选择/详情界面 | 无 GUI | P2N 更丰富 |
| AI 对话 | Player2 API（LLM 对话） | LLM 任务解析 | 类似 |

---

## 四、Baritone 深度分析（本地参考代码）

### 4.1 Movement 系统（核心差距）

**Movement 基类**：
`java
public abstract class Movement {
    protected final IBaritone baritone;
    protected final IPlayerContext ctx;
    protected final BetterBlockPos src;           // 起点
    protected final BetterBlockPos dest;          // 终点
    protected final BetterBlockPos[] positionsToBreak;  // 需要破坏的方块
    protected final BetterBlockPos positionToPlace;     // 需要放置的方块
    private MovementState currentState;
    private Double cost;

    public abstract double calculateCost(CalculationContext context);
    public abstract MovementState updateState(MovementState state);
    protected abstract Set<BetterBlockPos> calculateValidPositions();
}
`

**8 种 Movement 类型**：
1. **MovementTraverse** — 平地行走 + 搭桥（潜行放置方块）
2. **MovementPillar** — 搭柱向上（放置 + 跳跃）
3. **MovementFall** — 跌落（含水桶放置）
4. **MovementAscend** — 斜坡上升
5. **MovementDescend** — 斜坡下降
6. **MovementDiagonal** — 对角移动
7. **MovementParkour** — 跑跳（跳跃 + 搭桥）
8. **MovementDownward** — 向下挖掘

**每个 Movement 的执行流程**：
1. PREPPING — 准备阶段（检查前置条件）
2. WAITING — 等待阶段
3. RUNNING — 执行阶段（控制输入）
4. SUCCESS / UNREACHABLE — 结果

### 4.2 PathingBehavior（异步寻路）

`java
public final class PathingBehavior extends Behavior {
    private PathExecutor current;    // 当前执行的路径
    private PathExecutor next;       // 预计算的下一段路径
    private volatile AbstractNodeCostSearch inProgress;  // 正在计算的寻路任务
}
`

**双缓冲机制**：
- current 正在执行
- next 预计算（当 current 接近终点时启动）
- inProgress 正在异步计算的 A* 任务

**异步计算**：
`java
Baritone.getExecutor().execute(() -> {
    PathCalculationResult calcResult = pathfinder.calculate(primaryTimeout, failureTimeout);
    // 回到主线程更新状态
});
`

### 4.3 CalculationContext（代价快照）

`java
public class CalculationContext {
    public final Level world;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasWaterBucket;
    public final boolean hasThrowaway;
    public final boolean canSprint;
    public final boolean allowBreak;
    public final boolean allowParkour;
    // ... 更多配置
}
`

**关键设计**：在寻路开始时创建快照，整个过程使用同一份数据，保证线程安全。

### 4.4 CachedWorld（世界缓存）

- 区域级缓存（512x512）
- PackerThread 异步打包 LevelChunk -> CachedChunk
- getLocationsOf(blockName) O(1) 方块查找
- 定期保存（每 10 分钟）
- 内存管理（prune 远离玩家的区域）

### 4.5 InputOverrideHandler（输入模拟）

`java
public class PlayerMovementInput extends net.minecraft.client.player.Input {
    public void tick(boolean sneaking, float speed) {
        this.forwardImpulse = 0;
        this.leftImpulse = 0;
        this.jumping = handler.isInputForcedDown(Input.JUMP);
        if (this.up = handler.isInputForcedDown(Input.MOVE_FORWARD)) forwardImpulse++;
        // ...
    }
}
`

### 4.6 ToolSet（工具选择）

- 缓存方块破坏速度
- 效率附魔计算
- 精准采集偏好
- 药水效果（挖掘速度/缓慢）
- 物品保护（itemSaver）

---

## 五、综合对比分析

### 5.1 架构对比

| 维度 | AI Mod | PlayerEngine | Player2NPC | Baritone |
|------|--------|-------------|-----------|----------|
| **实体模型** | AIBotEntity(Mob) + FakePlayer(ServerPlayer) | LivingEntity + 接口 | LivingEntity + 接口 | LocalPlayer（客户端） |
| **背包** | SimpleContainer(36) + FakePlayer 背包 | LivingEntityInventory | LivingEntityInventory | Player.Inventory |
| **交互管理** | 委托 FakePlayer | LivingEntityInteractionManager | LivingEntityInteractionManager | PlayerController |
| **AI 系统** | LLM + Task + Action | Baritone（无 AI） | AltoClef Task + Chain | Process（Mine/Follow/Build） |
| **寻路** | 自研 A*（同步） | Baritone A*（异步） | Baritone A*（异步） | Baritone A*（异步） |
| **移动控制** | setDeltaMovement | Input 模拟 | Input 模拟 | InputOverrideHandler |
| **世界感知** | WorldScanner 暴力扫描 | CachedWorld | CachedWorld | CachedWorld |
| **持久化** | GameProfile | 无 | NBT 完整持久化 | 设置持久化 |

### 5.2 功能差距（按优先级）

| 功能 | 参考项目 | AI Mod 现状 | 差距 | 优先级 |
|------|---------|-----------|------|--------|
| **Movement 系统** | Baritone 8 种 Movement | 无（直接 navigateTo） | 极高 | P0 |
| **输入模拟** | InputOverrideHandler | setDeltaMovement | 极高 | P0 |
| **行为链/优先级** | Player2NPC 9 条 Chain | 顺序执行 | 高 | P1 |
| **异步寻路** | PathingBehavior 双缓冲 | 同步 2 秒超时 | 高 | P1 |
| **世界缓存** | CachedWorld 区域级 | WorldScanner 暴力扫描 | 高 | P1 |
| **实体背包** | LivingEntityInventory | SimpleContainer | 中 | P1 |
| **自动进食** | FoodChain | 无 | 中 | P2 |
| **怪物防御** | MobDefenseChain | 无 | 中 | P2 |
| **水桶防摔** | MLGBucketFallChain | 无 | 中 | P2 |
| **卡住自救** | UnstuckChain | 卡住超时跳过 | 中 | P2 |
| **持久化** | NBT 完整持久化 | 仅 GameProfile | 中 | P2 |
| **皮肤系统** | 自定义皮肤 URL | 固定 Steve 皮肤 | 低 | P3 |
| **GUI** | 角色选择界面 | 无 | 低 | P3 |

### 5.3 我们的优势

| 优势 | 说明 | 参考项目对比 |
|------|------|------------|
| **LLM 自然语言** | 核心差异化 | 所有参考项目均无（Player2NPC 有对话但无任务解析） |
| **混合架构** | AIBotEntity 可见 + FakePlayer 完整能力 | PE/P2N 仅 LivingEntity，无 ServerPlayer 能力 |
| **合成系统** | RecipeIndex O(1) + Tag 感知 | Baritone 无合成，P2N 基础合成 |
| **任务反馈** | TaskFeedback 实时状态报告 | Baritone 无反馈 |
| **流式 LLM** | 实时显示 LLM 思考过程 | 所有参考项目均无 |
| **i18n** | 中英文支持 | 其他项目英文 |
| **战斗 AI** | AttackAction + 目标追踪 | Baritone 无战斗 |
| **FakePlayer 注册** | placeNewPlayer 服务器原生支持 | PE/P2N 不注册到玩家列表 |

---

## 六、可吸收的具体实现

### 6.1 从 PlayerEngine 吸收

#### 实体抽象接口（P1）
PlayerEngine 的接口体系可以让我们的代码更解耦：

`java
// 当前：直接依赖 FakePlayer
public class Action {
    protected FakePlayer bot;
}

// 改进：依赖接口
public class Action {
    protected IInventoryProvider inventory;
    protected IInteractionManagerProvider interaction;
}
`

**好处**：
- 便于单元测试（Mock 接口）
- 未来支持其他实体类型
- 代码更清晰

#### LivingEntityInteractionManager（P1）
PlayerEngine 的交互管理器实现了完整的方块挖掘逻辑：
- 开始/继续/停止挖掘
- 挖掘进度计算
- 附魔效果（效率、精准采集）
- 工具选择

**可直接复用**：将 LivingEntityInteractionManager 的逻辑适配到我们的 FakePlayer 上。

#### 线程池（P1）
`java
ThreadPoolExecutor(4, Integer.MAX_VALUE, 60, SECONDS, new SynchronousQueue<>())
`
- 用于异步寻路
- 用于异步皮肤下载
- 用于异步 LLM 调用

### 6.2 从 Player2NPC 吸收

#### 行为链系统（P1）
Player2NPC 的 Chain 系统是行为优先级的优秀实现：

`java
// 建议实现
public abstract class BehaviorChain {
    protected int priority;
    public abstract boolean shouldActivate();
    public abstract void tick();
}

public class FoodChain extends BehaviorChain { ... }
public class DefenseChain extends BehaviorChain { ... }
public class UnstuckChain extends BehaviorChain { ... }
`

**优先级**：
1. 紧急防御（怪物/玩家攻击）
2. 自动进食（饥饿时）
3. 水桶防摔（坠落时）
4. 卡住自救（卡住时）
5. 用户任务（正常执行）

#### CompanionManager（P1）
- NPC 生命周期管理（创建/销毁/持久化）
- 异步角色获取
- NBT 持久化

#### 皮肤系统（P3）
- SkinManager 异步下载皮肤
- ResourceDownloader 资源下载器
- 支持自定义皮肤 URL

#### GUI 系统（P3）
- CharacterSelectionScreen 角色选择
- CharacterDetailScreen 角色详情

### 6.3 从 Baritone 吸收

#### Movement 系统（P0，最高优先级）
这是我们需要的最核心改进：

`java
// 建议实现
public abstract class Movement {
    protected final FakePlayer bot;
    protected final BlockPos src, dest;
    protected final BlockPos[] positionsToBreak;
    protected final BlockPos positionToPlace;
    private MovementStatus status;

    public abstract double calculateCost(CalculationContext ctx);
    public abstract MovementState updateState(MovementState state);
}

public class MovementTraverse extends Movement { ... }  // 平地 + 搭桥
public class MovementPillar extends Movement { ... }     // 搭柱
public class MovementFall extends Movement { ... }       // 跌落
`

#### PathingBehavior 异步寻路（P1）
`java
public class PathingBehavior {
    private PathExecutor current;
    private PathExecutor next;
    private volatile AStarPathFinder inProgress;
    private final Object pathCalcLock = new Object();
}
`

#### CalculationContext 代价快照（P1）
`java
public class CalculationContext {
    public final ServerLevel world;
    public final BlockStateInterface bsi;
    public final ToolSet toolSet;
    public final boolean hasThrowaway;
    public final boolean allowBreak;
}
`

#### CachedWorld 世界缓存（P1）
`java
public class ChunkCache {
    private Long2ObjectMap<CachedRegion> regions;
    // O(1) 方块查询
    // 区块加载/卸载自动更新
}
`

#### InputOverrideHandler 输入模拟（P0）
`java
public class InputOverrideHandler {
    private final Map<BotInput, Boolean> forceStateMap;

    public boolean isForcedDown(BotInput input);
    public void setForceState(BotInput input, boolean forced);
}
`

---

## 七、实施路线图

### 阶段 1：Movement + 输入系统（P0，约 2 周）
- [ ] Movement 基类 + MovementState 状态机
- [ ] MovementTraverse（平地行走 + 搭桥）
- [ ] MovementPillar（搭柱向上）
- [ ] MovementFall（跌落 + 水桶放置）
- [ ] InputOverrideHandler + ServerMovementInput
- [ ] CalculationContext 代价快照
- [ ] 修改 Action 使用 Movement 系统

### 阶段 2：寻路和缓存优化（P1，约 2 周）
- [ ] PathingBehavior 异步寻路（双缓冲）
- [ ] ChunkCache 世界缓存
- [ ] ToolSet 增强（附魔、药水、物品保护）
- [ ] LivingEntityInteractionManager 适配

### 阶段 3：行为链和生存系统（P2，约 2 周）
- [ ] BehaviorChain 基类
- [ ] DefenseChain（怪物/玩家防御）
- [ ] FoodChain（自动进食）
- [ ] MLGBucketChain（水桶防摔）
- [ ] UnstuckChain（卡住自救）
- [ ] 持久化系统（NBT 完整持久化）

### 阶段 4：高级功能（P3，约 2 周）
- [ ] 皮肤系统（自定义 URL）
- [ ] GUI 系统（角色选择/详情）
- [ ] MovementDiagonal / MovementParkour
- [ ] 巡逻路径系统

---

## 八、注意事项

### 8.1 架构差异

**PlayerEngine/P2NPC 使用 LivingEntity + 接口**，我们使用 **AIBotEntity(Mob) + FakePlayer(ServerPlayer)**。

**我们的优势**：
- FakePlayer 注册到服务器玩家列表，获得原生支持
- FakePlayer 继承 ServerPlayer，拥有完整玩家能力
- 不需要重新实现背包/交互管理

**我们的劣势**：
- AIBotEntity 和 FakePlayer 之间的同步开销
- FakePlayer 注册到服务器列表可能有副作用

**建议**：保持我们的架构，但吸收 PlayerEngine 的接口抽象思想，在 Action 层使用接口而非具体类。

### 8.2 客户端 vs 服务端

Baritone 是客户端模组，使用 InputOverrideHandler 替换客户端输入。我们是服务端模组，需要适配：

`java
// Baritone（客户端）
public class PlayerMovementInput extends Input {
    public void tick() {
        this.forwardImpulse = handler.isForcedDown(MOVE_FORWARD) ? 1 : 0;
    }
}

// 我们（服务端）
public class ServerMovementInput {
    public void applyTo(FakePlayer bot) {
        if (forward) bot.setDeltaMovement(bot.getDeltaMovement().add(0, 0, speed));
    }
}
`

### 8.3 渐进式重构

1. 先实现 Movement 基类和 1-2 个类型
2. 在新 Action 中使用 Movement 系统
3. 逐步迁移旧 Action
4. 最后移除旧的 navigateTo() 代码