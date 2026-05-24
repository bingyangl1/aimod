# 深度开发分析报告：LLM 大模型规划器与本地规划器

## 项目信息

| 项目 | 版本 | 路径 |
|------|------|------|
| AIMod | 1.0.x | `f:\MC\src\main\java\com\aimod` |
| Baritone-ref | 1.21.1 | `f:\MC\baritone-ref` |
| PlayerEngine | 1.21 | `f:\MC\PlayerEngine-main` |
| MineColonies | release/1.21 | GitHub ldttteam/minecolonies |

---

## 第一部分：架构现状分析

### 1.1 LLM 规划器现状

#### 核心组件

| 文件 | 职责 | 代码行数 |
|------|------|----------|
| `LLMService.java` | HTTP 调用、速率限制、重试 | 507 |
| `BotAIManager.java` | 命令解析、动作转换 | 593 |
| `LLMResponse.java` | 响应数据模型 | ~100 |
| `LLMResponseParser.java` | JSON 解析 | ~100 |

#### 执行流程

```
用户命令 → BotAIManager.parseCommand()
    ↓
LLMService.parseCommand() → HTTP POST (OpenAI/DeepSeek)
    ↓
collectWorldContext() → 位置/生命值/背包/时间/生物群系
    ↓
LLMResponse.parseResponse() → JSON 解析
    ↓
convertResponseToActions() → Action 对象列表
    ↓
executeTask() → 顺序执行动作
```

#### 关键问题分析

| 问题 | 代码位置 | 严重性 | 依据 |
|------|----------|--------|------|
| **无状态机** | `BotAIManager.executeTask()` | 🔴 高 | 仅顺序执行，无中断/恢复机制 |
| **提示词静态** | `LLMService.buildPrompt()` | 🟡 中 | 固定格式，无动态上下文增强 |
| **解析脆弱** | `parseActionsFromContent()` | 🔴 高 | 简单字符串匹配，无 Schema 验证 |
| **无增量规划** | 整个 LLMService | 🟡 中 | 长任务无法分段，需完整响应 |

#### 源码证据

```java
// BotAIManager.java:189-203 - 简单顺序执行，无状态机
if (currentAction.getStatus() == Action.ActionStatus.PENDING) {
    if (currentAction.canExecute(bot)) {
        currentAction.execute(bot);
    } else {
        currentAction.setStatus(Action.ActionStatus.FAILED);
        feedback.reportActionFailed(...);
    }
}
```

---

### 1.2 本地规划器现状

#### 核心组件

| 文件 | 职责 | 代码行数 |
|------|------|----------|
| `CommandParser.java` | 正则解析命令 | 147 |
| `SequencePlanner.java` | 合成规划、材料树 | 128 |
| `MaterialTree.java` | 配方递归分解 | 121 |
| `RecipeIndex.java` | O(1) 配方查找 | 373 |

#### 正则模式覆盖

```java
// CommandParser.java:25-33
private static final Pattern VERB_CRAFT = Pattern.compile("(制作|做|合成|craft|make)\\s*");
private static final Pattern VERB_MINE  = Pattern.compile("(挖|采集|开采|mine|dig)\\s*");
private static final Pattern VERB_GATHER = Pattern.compile("(砍|采集|收集|gather|chop|collect|get)\\s*");
```

#### 关键问题分析

| 问题 | 代码位置 | 严重性 | 依据 |
|------|----------|--------|------|
| **动作类型有限** | `Action.java` | 🔴 高 | 仅 14 种动作类型 |
| **无世界感知** | `SequencePlanner.java` | 🟡 中 | 仅基于背包，不感知环境 |
| **无行为链** | 整个 planner | 🟡 中 | 无条件触发/状态转换 |
| **缺乏学习** | 整个 planner | 🟢 低 | 无历史模式记忆 |

#### 源码证据

```java
// SequencePlanner.java:24-78 - 静态规划，无环境感知
public static List<Action> planCraftAndGive(...) {
    MaterialTree tree = new MaterialTree(targetItem, count);
    tree.build(inv, 6);  // 仅依赖背包，无环境检查
    // ...
}
```

---

### 1.3 路径系统现状

#### 核心组件

| 文件 | 职责 | 优先级 |
|------|------|--------|
| `Pathfinder.java` | A* 寻路，16 方向移动 | P0 |
| `AsyncPathfinder.java` | 后台线程寻路 | P0 |
| `CalculationContext.java` | 线程安全的世界快照 | P0 |
| `PathExecutor.java` | 路径跟随 | P1 |
| `BotMovement.java` | 移动步骤抽象 | P0 |
| `MovementController.java` | 移动控制中枢 | P1 |

#### Baritone PathExecutor vs AIMod PathExecutor

| 特性 | Baritone | AIMod | 差距 |
|------|----------|-------|------|
| 路径拼接 | `trySplice()` | ❌ 无 | 缺失 |
| 预计算 | `next` PathExecutor | ❌ 无 | 缺失 |
| 提前规划 | `planningTickLookahead` | ❌ 无 | 缺失 |
| 路径裁剪 | `CutoffPath` | ❌ 无 | 缺失 |
| 路径缓存 | `CachedWorld` | ⚠️ 基础 | 待增强 |
| 失败恢复 | PathEvent 机制 | ⚠️ 简单重试 | 待增强 |

#### 源码证据

```java
// Baritone PathExecutor.java:602-619 - 路径拼接
public PathExecutor trySplice(PathExecutor next) {
    if (next == null) return cutIfTooLong();
    return SplicedPath.trySplice(path, next.path, false).map(path -&gt; {
        // 复杂的拼接逻辑
    }).orElseGet(this::cutIfTooLong);
}

// AIMod PathExecutor.java - 无此功能
public BlockPos tick(FakePlayer bot) {
    // 简单跟随，无拼接
}
```

---

### 1.4 MineColonies 参考价值

#### TickRateStateMachine 分析

```java
// 来源: MineColonies TickRateStateMachine.java
public class TickRateStateMachine&lt;S extends IState&gt; extends BasicStateMachine&lt;ITickingTransition&lt;S&gt;, S&gt; {
    private int tickRate = 1;
    
    @Override
    public void tick() {
        // 优先级: AI_BLOCKING → EVENT → STATE_BLOCKING → CURRENT_STATE
        for (ITickingTransition&lt;S&gt; transition : aiBlockingTransitions) {
            if (checkTransition(transition)) return;
        }
        // ...
    }
}
```

#### TickingTransition 分析

```java
// 来源: MineColonies TickingTransition.java
public class TickingTransition&lt;S extends IState&gt; extends BasicTransition&lt;S&gt; {
    private int tickRate;
    private int ticksToUpdate = 0;
    
    // 均匀分布的 tick 偏移，避免同时检查
    this.ticksToUpdate = tickOffsetVariant % this.tickRate;
}
```

#### 参考价值矩阵

| 模块 | 参考价值 | 理由 |
|------|----------|------|
| **TickRateStateMachine** | ⭐⭐⭐⭐⭐ | 事件优先级机制，适合复杂 AI |
| **TickingTransition** | ⭐⭐⭐⭐ | 均匀 tick 分布，避免 CPU 峰值 |
| **AIEventTarget** | ⭐⭐⭐⭐ | 条件触发，适合行为链 |
| **RequestSystem** | ⭐⭐⭐ | 物品请求，但架构差异大 |
| **WorkerBuildingModule** | ⭐⭐⭐ | 建筑-工人绑定，可借鉴 |

---

## 第二部分：改进方案

### 2.1 改进方向总览

```
                    ┌─────────────────────────────────────────────┐
                    │              规划器架构改进                   │
                    └─────────────────────────────────────────────┘
                                          │
          ┌───────────────────────────────┼───────────────────────────────┐
          │                               │                               │
          ▼                               ▼                               ▼
┌─────────────────────┐     ┌─────────────────────┐     ┌─────────────────────┐
│   LLM 规划器改进     │     │   本地规划器改进     │     │   路径系统改进      │
├─────────────────────┤     ├─────────────────────┤     ├─────────────────────┤
│ • 状态机化           │     │ • 动作类型扩展       │     │ • 路径预计算        │
│ • 动态提示词         │     │ • 目标驱动规划       │     │ • 路径拼接          │
│ • 容错解析           │     │ • 行为链机制         │     │ • 路径缓存          │
│ • 增量规划           │     │ • 世界感知增强       │     │ • 失败恢复增强      │
└─────────────────────┘     └─────────────────────┘     └─────────────────────┘
```

---

### 2.2 LLM 规划器改进

#### 2.2.1 状态机化 (P0)

**依据**: MineColonies `TickRateStateMachine` 实现

**新增文件**:

```
ai/llm/
├── BotAIState.java           # AI 状态枚举
├── BotAIStateMachine.java     # 状态机核心
├── LLMPlanningContext.java    # 规划上下文
└── IncrementalPlanner.java    # 增量规划器
```

**BotAIState.java 设计**:

```java
public enum BotAIState {
    IDLE,           // 空闲等待
    PLANNING,       // 正在调用 LLM 规划
    EXECUTING,      // 执行动作
    WAITING,        // 等待资源
    PAUSED,         // 暂停
    COMPLETED,      // 完成
    FAILED,         // 失败
    REPLAN          // 重新规划
}
```

**BotAIStateMachine.java 设计**:

```java
public class BotAIStateMachine {
    private BotAIState currentState;
    private final List&lt;Transition&gt; transitions;
    
    public void tick() {
        for (Transition t : transitions) {
            if (t.condition.test() &amp;&amp; t.source == currentState) {
                t.action.execute();
                currentState = t.target;
                return;
            }
        }
    }
}
```

#### 2.2.2 动态提示词 (P1)

**依据**: Baritone `PathingCommandContext` 上下文增强

**LLMPlanningContext.java 设计**:

```java
public class LLMPlanningContext {
    // 基础信息
    BlockPos botPosition;
    double health;
    Map&lt;Item, Integer&gt; inventory;
    
    // 任务语义
    String currentTask;
    int actionsCompleted;
    int totalActions;
    List&lt;String&gt; recentActions;  // 最近动作，用于 LLM 理解进度
    
    // 世界状态
    List&lt;String&gt; nearbyBlocks;
    List&lt;String&gt; nearbyEntities;
    List&lt;String&gt; nearbyPlayers;
    
    // 资源状态
    Map&lt;Item, Integer&gt; availableTools;
    boolean hasCraftingTable;
    boolean hasFurnace;
    
    // 动态构建提示词
    public String buildPrompt(String command) { ... }
}
```

#### 2.2.3 容错解析 (P1)

**依据**: 当前 `parseActionsFromContent()` 的脆弱实现

**RobustActionParser.java 设计**:

```java
public class RobustActionParser {
    private final JsonSchema schema;
    private final ActionValidator validator;
    
    public List&lt;Action&gt; parseWithFallback(String json) {
        try {
            return strictParse(json);  // Schema 验证
        } catch (ParseException e) {
            return looseParse(json);   // 正则回退
        }
    }
    
    private List&lt;Action&gt; strictParse(String json) {
        // JSON Schema 验证
        if (!schema.validate(json)) throw new ParseException();
        // ...
    }
}
```

#### 2.2.4 增量规划 (P2)

**依据**: Baritone 路径预计算机制

**IncrementalPlanner.java 设计**:

```java
public class IncrementalPlanner {
    private String fullTask;
    private List&lt;SubGoal&gt; completedGoals;
    private SubGoal currentGoal;
    
    public void planNextStep(LLMPlanningContext ctx) {
        // 发送已完成动作 + 当前状态给 LLM
        // LLM 返回下一步动作
        // ...
    }
    
    public boolean isTaskComplete() {
        return completedGoals.size() &gt;= totalGoals;
    }
}
```

---

### 2.3 本地规划器改进

#### 2.3.1 动作类型扩展 (P0)

**依据**: MineColonies Worker 类型分类

**新增动作**:

| 动作类型 | 描述 | 依赖 |
|----------|------|------|
| `MineVeinAction` | 矿脉挖掘 (鱼骨矿道) | MovementController |
| `BuildSchematicAction` | Schematic 建筑 | SchematicSystem |
| `FarmCropAction` | 自动耕种 | WorldScanner |
| `BreedAnimalAction` | 动物繁殖 | EntityAI |
| `GuardAreaAction` | 区域巡逻 | PathingBehavior |

**MineVeinAction.java 设计**:

```java
public class MineVeinAction extends Action {
    private final String oreType;
    private final int maxDepth;
    private final Set&lt;BlockPos&gt; visited;
    
    @Override
    public void execute(FakePlayer bot) {
        // 1. 扫描附近矿脉
        // 2. A* 导航到矿石
        // 3. 挖掘并收集
        // 4. 扩展鱼骨矿道
        // 5. 重复直到 maxDepth
    }
}
```

#### 2.3.2 目标驱动规划 (P1)

**依据**: STRIPS/PDDL 规划思想

**GoalDrivenPlanner.java 设计**:

```java
public class GoalDrivenPlanner {
    public List&lt;Action&gt; plan(WorldState from, WorldState to) {
        // 1. 比较 from 和 to 的差异
        // 2. 反向链接 (backward chaining)
        // 3. 递归分解子目标
        // 4. 返回动作序列
        return backwardChaining(from, to);
    }
    
    private List&lt;Action&gt; backwardChaining(WorldState current, WorldState goal) {
        // 找到差异
        Diff diff = current.diff(goal);
        // 找到能减少差异的动作
        Action action = findAction(diff);
        // 递归规划前置条件
        WorldState precondition = action.getPrecondition(diff);
        return concat(backwardChaining(current, precondition), action);
    }
}
```

#### 2.3.3 行为链机制 (P1)

**依据**: Player2NPC 行为链系统

**BehaviorChain.java 设计**:

```java
public class BehaviorChain {
    String id;
    Predicate&lt;BotContext&gt; condition;
    List&lt;Action&gt; actions;
    Consumer&lt;BotContext&gt; onSuccess;
    Consumer&lt;BotContext&gt; onFailure;
}

public class ChainExecutor {
    List&lt;BehaviorChain&gt; chains;
    
    public void tick(BotContext ctx) {
        for (BehaviorChain chain : chains) {
            if (chain.condition.test(ctx)) {
                chain.execute(ctx);
                break;
            }
        }
    }
}
```

#### 2.3.4 世界感知增强 (P2)

**依据**: Baritone `WorldScanner` 和 `CachedWorld`

**EnhancedWorldScanner.java 设计**:

```java
public class EnhancedWorldScanner extends WorldScanner {
    private final ChunkCache cache;
    private final OreVeinTracker oreTracker;
    
    public BlockPos findOreVein(String oreType, int radius) {
        // 1. 检查 OreVeinTracker 缓存
        // 2. 扩展搜索区域
        // 3. 追踪相连矿石
        // 4. 返回最佳挖掘点
    }
    
    public List&lt;BlockPos&gt; findSafePath(BlockPos from, BlockPos to) {
        // 考虑危险因素: 岩浆、水、深渊
    }
}
```

---

### 2.4 路径系统改进

#### 2.4.1 路径预计算 (P1)

**依据**: Baritone PathingBehavior `next` PathExecutor

**PathExecutor 增强**:

```java
public class PathExecutor {
    private final IPath path;
    private int currentIndex;
    private PathExecutor next;  // 预计算的下一段路径
    
    public void precomputeNext(PathingBehavior behavior, BlockPos goal) {
        // 在当前路径执行时，提前计算下一段
        PathResult result = asyncPathfinder.requestPath(current.getDest(), goal);
        result.onComplete(p -&gt; this.next = new PathExecutor(p));
    }
    
    public PathExecutor trySplice(PathExecutor incoming) {
        // 拼接当前路径和预计算路径
        return SplicedPath.trySplice(path, incoming.path);
    }
}
```

#### 2.4.2 路径拼接 (P1)

**依据**: Baritone `SplicedPath.trySplice()`

**SplicedPath.java 设计**:

```java
public class SplicedPath {
    public static Optional&lt;Path&gt; trySplice(Path current, Path next, boolean cutStart) {
        // 1. 找到拼接点 (当前路径末尾 + 下一路径起点)
        // 2. 验证连接有效性
        // 3. 合并路径节点
        // 4. 返回新路径或 Optional.empty()
    }
}
```

#### 2.4.3 路径缓存 (P2)

**依据**: Baritone `CachedWorld`

**PathCache.java 设计**:

```java
public class PathCache {
    private final LoadingCache&lt;PathKey, PathResult&gt; cache;
    
    public PathCache(int maxSize) {
        this.cache = Caffeine.newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
    }
    
    public PathResult get(BlockPos from, BlockPos to) {
        return cache.get(new PathKey(from, to));
    }
}
```

#### 2.4.4 失败恢复增强 (P1)

**依据**: Baritone PathEvent 机制

**PathRecoveryStrategy.java 设计**:

```java
public enum RecoveryStrategy {
    RETRY,           // 重试当前路径
    SKIP,            // 跳过卡住点
    REROUTE,        // 重新寻路
    ALTERNATIVE,    // 使用备用路径
    ABORT           // 放弃任务
}

public class PathRecoveryManager {
    public RecoveryStrategy determineRecovery(PathExecutor executor, StuckEvent event) {
        // 1. 分析卡住原因
        // 2. 检查周围环境
        // 3. 选择最佳恢复策略
    }
}
```

---

## 第三部分：开发任务与验收标准

### 3.1 LLM 规划器改进

#### 任务 1: 状态机化 (LLM-StateMachine)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LLM-001 |
| **任务名称** | LLM 规划器状态机化 |
| **优先级** | P0 |
| **预估工时** | 8h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-1.1 | 创建 `BotAIState.java` 枚举 | 包含 8 种状态 |
| ST-1.2 | 创建 `BotAIStateMachine.java` | 支持 `tick()`, `transition()`, `pause()`, `resume()` |
| ST-1.3 | 重构 `BotAIManager` 使用状态机 | 状态转换正确 |
| ST-1.4 | 添加状态转换回调 | 支持 `onEnter()`, `onExit()` |
| ST-1.5 | 状态持久化 (暂停/恢复) | 任务可中断/恢复 |

**验收标准**:

```
✓ 状态机正确处理 IDLE → PLANNING → EXECUTING → COMPLETED/FAILED
✓ 支持 PAUSE/RESUME 操作
✓ 状态转换时触发回调
✓ 暂停后重启能恢复执行
✓ 单元测试覆盖所有状态转换
```

**测试用例**:

```java
@Test
public void testStateTransition() {
    BotAIStateMachine sm = new BotAIStateMachine();
    sm.transition(BotAIState.PLANNING);
    assertEquals(BotAIState.PLANNING, sm.getCurrentState());
    
    sm.transition(BotAIState.EXECUTING);
    assertEquals(BotAIState.EXECUTING, sm.getCurrentState());
}

@Test
public void testPauseResume() {
    BotAIStateMachine sm = new BotAIStateMachine();
    sm.transition(BotAIState.EXECUTING);
    sm.pause();
    assertEquals(BotAIState.PAUSED, sm.getCurrentState());
    
    sm.resume();
    assertEquals(BotAIState.EXECUTING, sm.getCurrentState());
}
```

---

#### 任务 2: 动态提示词 (LLM-Prompt)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LLM-002 |
| **任务名称** | LLM 动态提示词系统 |
| **优先级** | P1 |
| **预估工时** | 6h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-2.1 | 创建 `LLMPlanningContext.java` | 包含所有上下文字段 |
| ST-2.2 | 实现动态提示词构建 | 根据上下文动态生成 |
| ST-2.3 | 添加历史动作上下文 | 最近 5 个动作供 LLM 参考 |
| ST-2.4 | 世界状态压缩 | 精简但完整的周围信息 |

**验收标准**:

```
✓ LLMPlanningContext 包含: 位置/生命值/背包/任务进度/周围实体/可用工具
✓ 提示词长度控制在 maxTokens 的 50% 以内
✓ 动态提示词能引导 LLM 生成更准确的动作
✓ 支持中英双语提示词
```

---

#### 任务 3: 容错解析 (LLM-RobustParse)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LLM-003 |
| **任务名称** | LLM 响应容错解析器 |
| **优先级** | P1 |
| **预估工时** | 4h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-3.1 | 创建 JSON Schema | 定义动作结构 |
| ST-3.2 | 实现严格解析器 | Schema 验证 |
| ST-3.3 | 实现宽松解析器 | 正则回退 |
| ST-3.4 | 解析结果验证 | 动作有效性检查 |

**验收标准**:

```
✓ JSON Schema 验证所有已知动作类型
✓ 解析失败时回退到正则匹配
✓ 不正确的动作类型被过滤
✓ 解析成功率 &gt; 95% (测试集)
```

---

### 3.2 本地规划器改进

#### 任务 4: 动作类型扩展 (Local-Actions)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LOCAL-001 |
| **任务名称** | 本地规划器动作扩展 |
| **优先级** | P0 |
| **预估工时** | 12h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 | 依赖 |
|--------|------|----------|------|
| ST-4.1 | 创建 `MineVeinAction.java` | 鱼骨矿道挖掘 | ST-4.4 |
| ST-4.2 | 创建 `BuildSchematicAction.java` | Schematic 建筑 | ST-4.4 |
| ST-4.3 | 创建 `FarmCropAction.java` | 自动耕种 | - |
| ST-4.4 | 创建 `SchematicSystem.java` | Schematic 加载/解析 | - |
| ST-4.5 | 扩展 `SequencePlanner` | 支持新动作 | ST-4.1-4.3 |

**MineVeinAction 验收标准**:

```
✓ 扫描矿石矿脉 (diamond_ore, iron_ore 等)
✓ 鱼骨矿道模式: 主隧道 + 支隧道
✓ 自动放置火把 (每 8 格)
✓ 深度限制: 可配置 maxDepth
✓ 矿脉耗尽后停止
✓ 单元测试: 矿脉扫描、路径生成
```

**BuildSchematicAction 验收标准**:

```
✓ 支持 .nbt/.schematic 格式
✓ 方块状态匹配
✓ 素材检查 (所需方块)
✓ 逐层建造
✓ 障碍物处理 (等待/移除)
✓ 集成测试: 建造简单房屋
```

---

#### 任务 5: 目标驱动规划 (Local-GoalPlanning)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LOCAL-002 |
| **任务名称** | 目标驱动规划系统 |
| **优先级** | P1 |
| **预估工时** | 8h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-5.1 | 创建 `WorldState.java` | 世界状态快照 |
| ST-5.2 | 创建 `GoalState.java` | 目标状态定义 |
| ST-5.3 | 实现 `backwardChaining()` | 反向链接规划 |
| ST-5.4 | 实现 `ActionEffect.java` | 动作效果建模 |
| ST-5.5 | 集成到 `SequencePlanner` | 支持目标规划 |

**验收标准**:

```
✓ WorldState 能表示: 背包/位置/已放置方块/已破坏方块
✓ GoalState 支持: 物品交付/方块放置/位置到达
✓ backwardChaining 能分解复杂目标
✓ 规划结果与 SequencePlanner 输出格式兼容
✓ 单元测试: 简单目标规划 (e.g., "到达 X 位置并建造 Y")
```

---

#### 任务 6: 行为链机制 (Local-BehaviorChain)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-LOCAL-003 |
| **任务名称** | 行为链执行系统 |
| **优先级** | P1 |
| **预估工时** | 6h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-6.1 | 创建 `BehaviorChain.java` | 链节点定义 |
| ST-6.2 | 创建 `ChainExecutor.java` | 链执行器 |
| ST-6.3 | 实现条件评估 | Predicate&lt;BotContext&gt; |
| ST-6.4 | 实现链式触发 | 条件满足时执行 |

**验收标准**:

```
✓ BehaviorChain 包含: id/condition/actions/onSuccess/onFailure
✓ ChainExecutor 按优先级执行链
✓ 支持条件并行检查
✓ 链执行支持暂停/恢复
✓ 示例链: "检测到敌人 → 切换武器 → 攻击 → 捡战利品"
```

---

### 3.3 路径系统改进

#### 任务 7: 路径预计算 (Path-Precompute)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-PATH-001 |
| **任务名称** | 路径预计算系统 |
| **优先级** | P1 |
| **预估工时** | 6h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-7.1 | 添加 `next` PathExecutor | 预计算路径存储 |
| ST-7.2 | 实现 `precomputeNext()` | 提前计算下一段 |
| ST-7.3 | 添加规划阈值配置 | `planningTickLookahead` |
| ST-7.4 | 集成到 `MovementController` | 自动预计算 |

**验收标准**:

```
✓ next PathExecutor 在当前路径执行时计算
✓ 预计算触发条件: 剩余路径 &lt; planningTickLookahead ticks
✓ 预计算使用 MIN_PRIORITY 线程
✓ 预计算结果在 tick() 中回调到主线程
✓ 性能测试: 预计算不影响主线程 tick
```

---

#### 任务 8: 路径拼接 (Path-Splice)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-PATH-002 |
| **任务名称** | 路径拼接功能 |
| **优先级** | P1 |
| **预估工时** | 4h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-8.1 | 创建 `SplicedPath.java` | 拼接逻辑 |
| ST-8.2 | 实现 `trySplice()` | 尝试拼接两条路径 |
| ST-8.3 | 添加到 `PathExecutor` | 支持自动拼接 |
| ST-8.4 | 配置开关 | `settings.splicePath` |

**验收标准**:

```
✓ trySplice 找到两条路径的拼接点
✓ 拼接后路径连续无断裂
✓ 拼接失败时返回 Optional.empty()
✓ 自动拼接在 movement 完成时触发
✓ 配置可关闭自动拼接
```

---

#### 任务 9: 路径缓存 (Path-Cache)

| 属性 | 内容 |
|------|------|
| **任务 ID** | AIMOD-PATH-003 |
| **任务名称** | 路径缓存系统 |
| **优先级** | P2 |
| **预估工时** | 4h |
| **开发者** | [团队分配] |

**子任务**:

| 子任务 | 描述 | 验收标准 |
|--------|------|----------|
| ST-9.1 | 创建 `PathCache.java` | LRU 缓存 |
| ST-9.2 | 实现 `PathKey` | from+to 哈希 |
| ST-9.3 | 添加过期机制 | 5 分钟过期 |
| ST-9.4 | 集成到 `AsyncPathfinder` | 自动缓存 |

**验收标准**:

```
✓ PathCache 使用 LRU 策略，最大 100 条
✓ PathKey 基于 from/to 坐标哈希
✓ 缓存命中率 &gt; 30% (典型场景)
✓ 缓存失效时自动重新计算
✓ 内存占用可控
```

---

### 3.4 验收测试矩阵

#### 单元测试覆盖要求

| 模块 | 覆盖率要求 | 关键测试 |
|------|------------|----------|
| BotAIStateMachine | &gt;90% | 状态转换、暂停恢复 |
| LLMPlanningContext | &gt;80% | 提示词生成、上下文提取 |
| RobustActionParser | &gt;90% | 严格解析、宽松回退 |
| MineVeinAction | &gt;80% | 矿脉扫描、鱼骨生成 |
| BuildSchematicAction | &gt;80% | Schematic 加载、层构建 |
| PathExecutor (增强) | &gt;90% | 预计算、拼接、恢复 |
| PathCache | &gt;90% | LRU、过期、命中 |

#### 集成测试场景

| 测试名称 | 描述 | 验收条件 |
|----------|------|----------|
| IT-LLM-ComplexTask | 复杂任务: "挖 10 颗钻石并制作钻石剑给玩家" | 状态机正确转换，物品成功交付 |
| IT-Local-VeinMining | 矿脉挖掘: "挖这个钻石矿脉" | 鱼骨矿道，收集 &gt;90% 矿石 |
| IT-Local-BuildHouse | 建筑任务: "建造一个 5x5 木屋" | 方块位置正确，无遗漏 |
| IT-Path-Precompute | 长距离导航: 导航 100 格 | 预计算生效，拼接成功 |
| IT-Path-SpliceRetry | 路径中断: 路径中途有障碍 | 自动重新规划，无崩溃 |
| IT-PauseResume | 暂停恢复: 任务中途暂停 | 状态恢复，继续执行 |

---

## 第四部分：开发优先级与里程碑

### 4.1 优先级矩阵

```
        高影响
            ▲
            │
    ┌───────┼───────┐
    │ P0    │ P1    │
    │ 状态机 │ 动作   │
    │ 预计算 │ 拼接   │
    │ 容错   │ 提示词 │
    ├───────┼───────┤
    │ P2    │ P3    │
    │ 缓存   │ 学习   │
    │ 目标   │ 行为   │
    │ 规划   │ 链    │
    └───────┴───────┘
            │
            ▼
        低影响
    ◄──────────────────► 高难度
```

### 4.2 里程碑计划

#### 里程碑 M1: 状态机化 (第 1-2 周)

| 任务 | 交付物 |
|------|--------|
| AIMOD-LLM-001 | BotAIStateMachine 可用，LLM 规划器状态机化 |
| ST-测试 | 状态转换测试通过率 100% |

#### 里程碑 M2: 动作扩展 (第 3-4 周)

| 任务 | 交付物 |
|------|--------|
| AIMOD-LOCAL-001 | MineVeinAction, BuildSchematicAction |
| AIMOD-PATH-001 | 路径预计算系统 |

#### 里程碑 M3: 容错与拼接 (第 5-6 周)

| 任务 | 交付物 |
|------|--------|
| AIMOD-LLM-003 | RobustActionParser |
| AIMOD-PATH-002 | 路径拼接 |

#### 里程碑 M4: 高级规划 (第 7-8 周)

| 任务 | 交付物 |
|------|--------|
| AIMOD-LOCAL-002 | 目标驱动规划 |
| AIMOD-PATH-003 | 路径缓存 |

---

## 第五部分：附录

### A. 参考文件清单

| 文件 | 来源 | 用途 |
|------|------|------|
| `baritone-ref/src/.../PathingBehavior.java` | Baritone | 路径行为参考 |
| `baritone-ref/src/.../PathExecutor.java` | Baritone | 路径执行参考 |
| `PlayerEngine-main/.../PathingBehavior.java` | PlayerEngine | 服务端适配 |
| `TickRateStateMachine.java` | MineColonies | 状态机参考 |
| `TickingTransition.java` | MineColonies | Tick 分配参考 |

### B. 术语表

| 术语 | 定义 |
|------|------|
| 状态机 | State Machine，通过状态和转换管理 AI 行为的机制 |
| 增量规划 | Incremental Planning，将复杂任务分解为可独立执行的子任务 |
| 鱼骨矿道 | Fishbone Mining，主隧道+支隧道的采矿模式 |
| 路径拼接 | Path Splicing，将两段路径合并为连续路径 |
| 行为链 | Behavior Chain，条件-动作序列的执行机制 |
| 反向链接 | Backward Chaining，从目标反推动作序列的规划方法 |

### C. 报告信息

| 属性 | 内容 |
|------|------|
| 报告日期 | 2026-05-24 |
| 报告版本 | 1.0 |
| 分析团队 | ClaudeCode / OpenCode |
| 项目版本 | AIMod 1.0.x |

---

*报告结束*
