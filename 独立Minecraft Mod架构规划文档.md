# 独立Minecraft Mod架构规划文档

**版本**：v1\.0
**日期**：2026 年 5 月 22 日
**目标**：零前置依赖、单一 jar 交付的独立自动化 Mod

---

## 1\. 许可证合规分析

### 1\.1 Baritone \(LGPL v3\) 许可证分析

#### 可参考 / 移植的内容

- **算法思想**：A \* 路径规划算法的核心思路、启发函数设计原则

- **架构设计**：模块划分方式、接口抽象思想、数据流设计

- **通用概念**：世界扫描策略、运动控制逻辑、方块交互模式

- **数学公式**：代价计算公式、路径平滑算法公式

#### 必须完全重写的内容

- **所有具体代码实现**：不能直接复制任何源代码片段

- **数据结构定义**：所有类、接口、枚举必须重新命名和设计

- **配置文件格式**：配置项命名、序列化方式必须独立设计

- **资源文件**：所有纹理、语言文件、音效必须原创

#### LGPL v3 合规边界

- ✅ **允许**：学习算法思想、理解设计模式、参考架构

- ✅ **允许**：基于相同原理独立实现相同功能

- ❌ **禁止**：直接复制、修改、衍生原代码

- ❌ **禁止**：使用原 Mod 的类名、方法名、变量名

- ❌ **禁止**：二进制层面链接或依赖原 Mod

### 1\.2 SiliconeDolls 许可证分析（MIT 假设）

#### MIT 许可证特性

- 相对宽松，允许参考和重实现

- 无 Copyleft 传染效应

- 只需保留原许可证声明（如直接使用代码时）

#### 合规注意事项

- FakePlayer 创建机制可参考原理但需独立实现

- 网络包处理逻辑需重新编写

- 动画系统设计思想可借鉴，实现需原创

### 1\.3 RollingGate 许可证分析

#### 通用合规原则

- 规则引擎的设计模式可参考

- 事件驱动架构思想可借鉴

- 任务编排逻辑需独立实现

### 1\.4 许可证风险规避策略

1. **Clean Room 开发流程**

    - 一人负责阅读原 Mod 代码并撰写功能规格说明

    - 另一人根据规格说明独立实现代码

    - 实现者不接触原 Mod 源代码

2. **代码审查机制**

    - 所有代码提交前进行许可证合规审查

    - 检查是否存在与原 Mod 相似的代码结构

    - 验证所有标识符均为原创命名

3. **文档追溯**

    - 记录每个功能的参考来源

    - 明确标注 \&\#34;基于 XX 思想独立实现\&\#34;

    - 保留开发过程的决策记录

---

## 2\. 模块化架构设计

### 2\.1 整体架构图（分层架构）

```Plain Text
┌─────────────────────────────────────────────────────────┐
│                    用户交互层                            │
│  Command System │ Config System │ UI Interface │ Debug  │
├─────────────────────────────────────────────────────────┤
│                    业务逻辑层                            │
│  Task Scheduler │ Rule Engine │ State Machine │ Event   │
├─────────────────────────────────────────────────────────┤
│                    核心功能层                            │
│  Pathfinding │ FakePlayer │ World Scan │ Action Control │
├─────────────────────────────────────────────────────────┤
│                    基础设施层                            │
│  NeoForge API │ Minecraft Core │ Network │ Data Sync    │
└─────────────────────────────────────────────────────────┘
```

### 2\.2 核心模块划分与解耦设计

|模块名称|职责范围|依赖关系|
|---|---|---|
|`core\-api`|定义所有模块的公共接口、数据结构|无外部依赖|
|`pathfinding`|A \* 算法、路径计算、运动控制|仅依赖 core\-api|
|`fakeplayer`|假人创建、动作模拟、物品管理|仅依赖 core\-api|
|`worldscan`|区块扫描、方块缓存、增量更新|仅依赖 core\-api|
|`taskengine`|任务调度、状态机、规则引擎|依赖 core\-api \+ pathfinding \+ fakeplayer|
|`userinterface`|命令、配置、UI、调试工具|依赖所有功能模块|

### 2\.3 模块间接口定义与通信机制

#### 接口设计原则

- **面向接口编程**：所有模块通过接口交互，不直接依赖具体实现

- **事件驱动**：使用事件总线进行模块间解耦通信

- **数据不可变**：跨模块传递的数据对象设计为不可变

#### 核心接口定义

```java
// 路径规划服务接口
public interface IPathfindingService {
    PathResult findPath(BlockPos start, BlockPos goal, PathOptions options);
    void cancelPath();
    PathStatus getCurrentStatus();
}

// 假人服务接口
public interface IFakePlayerService {
    IFakePlayer createFakePlayer(GameProfile profile);
    void destroyFakePlayer(UUID playerId);
    Collection<IFakePlayer> getAllFakePlayers();
}

// 任务执行接口
public interface ITaskExecutor {
    TaskResult executeTask(ITask task);
    void pauseTask();
    void resumeTask();
    void cancelTask();
}
```

### 2\.4 Baritone 功能移植清单

#### A \* 路径规划算法

- ✅ 网格图建模：将世界抽象为可通行节点

- ✅ 启发函数：欧氏距离 \+ 曼哈顿距离混合

- ✅ 开放 / 关闭集合管理：优先队列优化

- ✅ 节点代价计算：行走、挖掘、放置代价

- ✅ 路径回溯与重建

#### 世界扫描与区块缓存

- ✅ 区块数据访问：通过 ChunkProvider 获取区块

- ✅ 方块状态缓存：LRU 缓存最近访问的方块

- ✅ 增量更新：监听区块更新事件

- ✅ 异步扫描：后台线程预加载周边区块

#### 运动控制与寻路执行

- ✅ 移动指令生成：前进、跳跃、下蹲、游泳

- ✅ 速度控制：根据地形调整移动速度

- ✅ 碰撞检测：防止卡墙、掉落

- ✅ 路径跟随：实时校正移动方向

#### 方块交互逻辑

- ✅ 挖掘序列：选择工具 → 对准方块 → 挖掘

- ✅ 放置序列：选择方块 → 对准位置 → 放置

- ✅ 交互序列：右键点击方块（打开容器等）

- ✅ 破坏 / 放置路径规划：自动清除障碍

### 2\.5 SiliconeDolls 功能移植清单

#### 假人 \(FakePlayer\) 创建与管理

- ✅ 服务端假人实例化：继承 ServerPlayer 重写

- ✅ 假人生命周期管理：创建、销毁、复用池

- ✅ 假人数据持久化：位置、状态、背包

- ✅ 多假人并发控制：线程安全的假人管理器

#### 玩家动作模拟系统

- ✅ 攻击动作：挥剑、射箭、投掷

- ✅ 交互动作：右键点击、使用物品

- ✅ 移动动作：行走、奔跑、跳跃、游泳

- ✅ 合成动作：工作台、熔炉操作

#### 动画与姿态控制

- ✅ 身体旋转：头部、身体、视线方向

- ✅ 手臂摆动：挖掘、攻击、放置动画

- ✅ 腿部运动：行走、跑步、跳跃动画

- ✅ 姿态切换：站立、下蹲、爬行、游泳

#### 背包与物品管理

- ✅ 物品选择：热栏切换、背包搜索

- ✅ 物品移动：拖动物品、快速转移

- ✅ 装备管理：盔甲穿戴、工具切换

- ✅ 容器交互：箱子、熔炉、工作台

### 2\.6 RollingGate 功能移植清单

#### 规则引擎与条件判断

- ✅ 条件抽象：方块存在、物品数量、实体位置

- ✅ 逻辑运算：AND、OR、NOT、比较运算

- ✅ 规则匹配：条件满足时触发动作

- ✅ 规则优先级：多规则冲突处理

#### 事件驱动系统

- ✅ 事件总线：发布 \- 订阅模式

- ✅ 事件类型：方块变化、物品变化、实体事件

- ✅ 事件过滤：条件筛选感兴趣的事件

- ✅ 异步处理：事件队列与线程池

#### 任务编排与流程控制

- ✅ 任务序列：按顺序执行多个子任务

- ✅ 条件分支：if\-else 流程控制

- ✅ 循环执行：while/for 循环结构

- ✅ 并行任务：多个任务同时执行

### 2\.7 完全自研功能清单

#### 配置系统

- ✅ TOML 格式配置文件

- ✅ 客户端 / 服务端配置分离

- ✅ 运行时配置热重载

- ✅ 配置验证与默认值

#### 命令系统

- ✅ 子命令架构：/mod command subcommand

- ✅ 参数解析：位置、物品、数字、枚举

- ✅ 权限控制：OP 权限、玩家权限

- ✅ 命令补全：Tab 自动补全

#### UI 界面

- ✅ 主控制面板：任务状态、参数调整

- ✅ 路径可视化：渲染当前路径

- ✅ 调试信息：性能指标、错误提示

- ✅ 配置界面：图形化配置编辑

#### 调试工具

- ✅ 性能监控：CPU、内存、TPS 统计

- ✅ 日志系统：分级日志、文件输出

- ✅ 调试命令：强制状态、强制动作

- ✅ 数据导出：路径数据、任务数据

---

## 3\. 自研替代方案详细清单

### 3\.1 自研假人系统（FakePlayer）

#### 服务端假人创建机制

**核心实现思路：**

```java
public class CustomFakePlayer extends ServerPlayer {
    // 继承ServerPlayer，重写关键方法以避免真实玩家行为
    // 不加入玩家列表、不发送聊天消息、不触发玩家事件
    
    public CustomFakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile);
        // 初始化假人特定状态
        this.connection = new FakeNetHandler(this);
    }
    
    @Override
    public boolean isFakePlayer() {
        return true;
    }
}
```

**创建流程：**

1. 生成唯一 UUID 和 GameProfile

2. 创建 FakePlayer 实例

3. 注册到世界但不加入玩家列表

4. 初始化网络处理器（不真实发送数据包）

5. 加入假人管理器进行统一管理

#### 网络包伪造与处理

**客户端侧模拟：**

- 不真实发送网络包到服务端

- 直接调用服务端方法处理动作

- 伪造必要的网络事件以触发 Minecraft 逻辑

**服务端侧处理：**

```java
public class FakeNetHandler extends ServerGamePacketListenerImpl {
    @Override
    public void handleMovePlayer(ServerboundMovePlayerPacket packet) {
        // 直接更新位置，不进行网络验证
        fakePlayer.setPos(packet.getX(), packet.getY(), packet.getZ());
    }
    
    @Override
    public void send(Packet<?> packet) {
        // 丢弃所有 outgoing 包，假人不需要客户端
    }
}
```

#### 玩家数据同步

**数据同步策略：**

- 假人数据仅在服务端维护

- 其他玩家看到的假人通过实体同步

- 使用标准实体追踪机制同步位置和状态

**关键数据同步：**

- ✅ 位置和旋转：实体位置数据包

- ✅ 装备和手持物品：实体装备数据包

- ✅ 动画状态：实体动画数据包

- ✅ 生命值和状态：实体属性数据包

#### 碰撞箱与物理模拟

**碰撞箱处理：**

- 使用标准玩家碰撞箱尺寸

- 重写碰撞检测方法避免与真实玩家冲突

- 支持自定义碰撞箱大小

**物理模拟：**

```java
public void tickPhysics() {
    // 应用重力
    if (!onGround) {
        deltaY -= 0.08D;
    }
    
    // 应用移动
    move(MoverType.SELF, deltaMovement);
    
    // 摩擦减速
    deltaMovement.multiply(0.91D, 1.0D, 0.91D);
    
    // 检查碰撞
    checkBlockCollisions();
}
```

### 3\.2 自研 A \* 路径规划

#### 算法核心实现

**数据结构设计：**

```java
public class PathNode {
    BlockPos pos;
    double gCost;  // 起点到当前节点的代价
    double hCost;  // 当前节点到终点的估计代价
    double fCost;  // gCost + hCost
    PathNode parent;
    NodeType type;  // WALK, BREAK, PLACE, FALL, SWIM
}
```

**核心算法流程：**

1. 初始化开放集合（优先队列）和关闭集合

2. 将起点加入开放集合

3. 循环直到开放集合为空或找到终点：

    - 取出 fCost 最小的节点

    - 如果是终点，回溯构建路径

    - 将节点移入关闭集合

    - 遍历所有相邻节点，计算代价并更新

#### 启发函数设计

**混合启发函数：**

```java
public double calculateHCost(BlockPos from, BlockPos to) {
    double dx = Math.abs(from.getX() - to.getX());
    double dy = Math.abs(from.getY() - to.getY());
    double dz = Math.abs(from.getZ() - to.getZ());
    
    // 欧氏距离（保证可采纳性）
    double euclidean = Math.sqrt(dx*dx + dy*dy + dz*dz);
    
    // 曼哈顿距离加权
    double manhattan = dx + dy + dz;
    
    // 加权混合，偏向更快搜索
    return euclidean * 0.8 + manhattan * 0.2;
}
```

#### 代价计算系统

**基础代价表：**

|动作类型|基础代价|附加条件|
|---|---|---|
|平地行走|1\.0|\-|
|上台阶|1\.5|需要跳跃|
|下台阶|1\.2|\-|
|挖掘方块|10\.0 \+ 挖掘时间|根据工具调整|
|放置方块|5\.0|需要有方块|
|掉落|2\.0 / 格|≤3 格无伤害|
|游泳|2\.0|水中移动|
|攀爬|1\.5|梯子 / 藤蔓|

#### 路径平滑与优化

**后处理优化：**

1. **节点合并**：将连续的同方向直线移动合并

2. **跳跃优化**：检测可跳跃越过的障碍

3. **对角线优化**：允许对角线移动（如可行）

4. **关键点提取**：只保留方向变化的节点

**平滑算法：**

```java
public List<PathNode> smoothPath(List<PathNode> original) {
    List<PathNode> smoothed = new ArrayList<>();
    int i = 0;
    
    while (i < original.size()) {
        smoothed.add(original.get(i));
        int j = original.size() - 1;
        
        // 找到最远可直达的节点
        while (j > i) {
            if (canWalkDirectly(original.get(i), original.get(j))) {
                i = j;
                break;
            }
            j--;
        }
        i++;
    }
    
    return smoothed;
}
```

### 3\.3 自研世界扫描与缓存

#### 区块数据访问层

**统一访问接口：**

```java
public interface IWorldAccess {
    BlockState getBlockState(BlockPos pos);
    FluidState getFluidState(BlockPos pos);
    boolean isLoaded(BlockPos pos);
    void addChunkListener(ChunkPos pos, Consumer<Chunk> listener);
}
```

**实现策略：**

- 服务端直接访问 Level 对象

- 客户端通过 ClientLevel 访问

- 统一异常处理和空值返回

- 异步预加载机制

#### 方块状态缓存机制

**LRU 缓存设计：**

```java
public class BlockStateCache {
    private final LinkedHashMap<BlockPos, CacheEntry> cache;
    private final int maxSize = 10000;
    
    public BlockState get(BlockPos pos) {
        CacheEntry entry = cache.get(pos);
        if (entry != null && !entry.isExpired()) {
            return entry.state;
        }
        return null;
    }
    
    public void put(BlockPos pos, BlockState state) {
        cache.put(pos, new CacheEntry(state, System.currentTimeMillis()));
        if (cache.size() > maxSize) {
            // 移除最旧的条目
            Iterator<Map.Entry<BlockPos, CacheEntry>> it = cache.entrySet().iterator();
            it.next();
            it.remove();
        }
    }
}
```

#### 增量更新策略

**事件监听机制：**

1. 监听`LevelChunkEvent\.Load` \- 区块加载

2. 监听`LevelChunkEvent\.Unload` \- 区块卸载

3. 监听`BlockEvent\.BreakEvent` \- 方块破坏

4. 监听`BlockEvent\.EntityPlaceEvent` \- 方块放置

5. 监听`NeighborNotifyEvent` \- 邻居更新

**更新策略：**

- 方块变化时立即失效对应缓存

- 区块卸载时清除整个区块缓存

- 定期清理过期的缓存条目

- 批量更新时合并处理

#### 内存管理与优化

**内存优化措施：**

1. **软引用缓存**：内存不足时自动回收

2. **分区缓存**：按区块分组，整体淘汰

3. **压缩存储**：BlockState 使用 ID 而非对象

4. **异步清理**：后台线程定期清理过期缓存

**内存监控：**

```java
public class CacheStats {
    int hitCount;
    int missCount;
    int evictionCount;
    long memoryUsage;
    
    public double getHitRate() {
        return (double) hitCount / (hitCount + missCount);
    }
}
```

### 3\.4 自研任务调度与状态机

#### 任务队列与优先级调度

**任务优先级定义：**

```java
public enum TaskPriority {
    CRITICAL(0),    // 紧急：躲避危险、自救
    HIGH(1),        // 高优：战斗、挖掘
    NORMAL(2),      // 普通：移动、放置
    LOW(3),         // 低优：收集物品
    BACKGROUND(4);  // 后台：探索、扫描
}
```

**调度器实现：**

```java
public class TaskScheduler {
    private final PriorityQueue<ScheduledTask> taskQueue;
    private final Map<UUID, ITask> runningTasks;
    
    public void schedule(ITask task, TaskPriority priority) {
        taskQueue.add(new ScheduledTask(task, priority));
    }
    
    public void tick() {
        // 执行最高优先级的就绪任务
        while (!taskQueue.isEmpty()) {
            ScheduledTask next = taskQueue.peek();
            if (next.task.canStart()) {
                executeTask(next.task);
                taskQueue.poll();
            } else {
                break;
            }
        }
    }
}
```

#### 状态机框架

**状态机核心：**

```java
public abstract class StateMachine<T> {
    protected T currentState;
    protected final Map<T, StateHandler<T>> handlers = new HashMap<>();
    
    public void tick() {
        StateHandler<T> handler = handlers.get(currentState);
        if (handler != null) {
            T nextState = handler.tick();
            if (nextState != currentState) {
                transitionTo(nextState);
            }
        }
    }
    
    protected void transitionTo(T newState) {
        handlers.get(currentState).onExit();
        currentState = newState;
        handlers.get(newState).onEnter();
    }
}
```

**典型状态定义（路径跟随）：**

- `IDLE` \- 空闲，等待指令

- `CALCULATING` \- 计算路径中

- `MOVING` \- 沿路径移动

- `OBSTACLE` \- 遇到障碍，重新规划

- `PAUSED` \- 暂停

- `COMPLETED` \- 完成

- `FAILED` \- 失败

#### 超时与错误处理

**超时机制：**

```java
public class TimeoutGuard {
    private final long timeoutMs;
    private long startTime;
    private boolean expired;
    
    public boolean checkTimeout() {
        if (System.currentTimeMillis() - startTime > timeoutMs) {
            expired = true;
            return true;
        }
        return false;
    }
}
```

**错误恢复策略：**

1. **重试机制**：失败后自动重试最多 N 次

2. **回退策略**：复杂任务失败时回退到简单方案

3. **紧急停止**：严重错误立即终止并报告

4. **状态重置**：失败后清理状态，恢复到安全点

#### 任务暂停 / 恢复机制

**可暂停任务接口：**

```java
public interface IPausableTask extends ITask {
    void pause();
    void resume();
    boolean isPaused();
    TaskSnapshot createSnapshot();
    void restoreFromSnapshot(TaskSnapshot snapshot);
}
```

**暂停状态保存：**

- 当前执行位置

- 已完成的子任务

- 中间计算结果

- 资源占用状态

---

## 4\. 代码移植策略

### 4\.1 \&\#34;参考实现\&\#34; 的正确方法论

#### 正确的参考流程

1. **功能理解**：运行原 Mod，观察功能表现和用户体验

2. **黑盒分析**：不看代码，只根据行为推测实现原理

3. **规格撰写**：编写详细的功能规格说明书

4. **独立实现**：根据规格书从零开始编写代码

5. **对比验证**：与原 Mod 功能对比，调整实现细节

#### 错误的参考方式（禁止）

- ❌ 直接复制粘贴代码

- ❌ 仅修改变量名和类名

- ❌ 逐行翻译代码逻辑

- ❌ 使用原 Mod 的 API 或数据结构

### 4\.2 如何避免直接复制代码

#### 技术隔离措施

1. **开发环境隔离**

    - 参考代码和新代码放在不同的工作区

    - 实现时不打开原 Mod 源代码

    - 使用独立的 Git 仓库管理

2. **命名空间完全独立**

    - 包名：`com\.yourname\.modname\.\*` 完全独立

    - 类名：避免使用原 Mod 的命名风格

    - 方法名：使用自己的命名规范

3. **实现差异化**

    - 数据结构选择不同（如原 Mod 用 ArrayList，我们用 ArrayDeque）

    - 算法细节调整（如启发函数权重不同）

    - 架构分层方式不同（原 Mod 扁平，我们分层）

### 4\.3 算法思想 vs 具体实现的分离

|层面|可借鉴|需原创|
|---|---|---|
|**算法思想**|✅ A \* 搜索的基本原理|\-|
|**数据结构**|✅ 优先队列的使用思想|❌ 具体类设计和字段|
|**流程控制**|✅ 状态机的状态划分|❌ 具体状态转换逻辑|
|**数学公式**|✅ 代价计算的基本公式|❌ 具体系数和常量|
|**代码结构**|\-|❌ 类层次、方法划分|
|**变量命名**|\-|❌ 所有标识符|

### 4\.4 代码重构与重命名策略

#### 全面重命名清单

- **包名**：完全更换，不包含原 Mod 任何标识

- **类名**：所有类重新命名，避免相似性

- **接口名**：重新设计接口抽象

- **方法名**：所有方法重新命名

- **变量名**：所有局部变量和字段重命名

- **常量名**：所有常量重新定义和命名

#### 结构重构策略

1. **重新划分类职责**：将原 Mod 的大类拆分为多个小类

2. **提取公共接口**：抽象出原 Mod 没有的接口层

3. **调整继承关系**：改变类的继承层次

4. **优化方法粒度**：拆分过长方法，合并过短方法

5. **引入设计模式**：在合适位置引入原 Mod 未使用的设计模式

### 4\.5 单元测试验证移植正确性

#### 测试策略

1. **功能等价测试**：相同输入下，输出与原 Mod 一致

2. **边界条件测试**：极端情况的处理正确性

3. **性能对比测试**：性能不低于原 Mod 的 80%

4. **回归测试**：确保修改不破坏已有功能

#### 关键测试用例

```java
// 路径规划测试
@Test
public void testPathfindingEquivalence() {
    // 在相同世界条件下
    BlockPos start = new BlockPos(0, 64, 0);
    BlockPos goal = new BlockPos(100, 64, 100);
    
    // 我们的实现
    PathResult ourResult = ourPathfinder.findPath(start, goal);
    
    // 验证路径存在且合理
    assertTrue(ourResult.isSuccess());
    assertTrue(ourResult.getPathLength() < 150); // 合理长度
}

// 假人动作测试
@Test
public void testFakePlayerAction() {
    FakePlayer player = fakePlayerManager.createPlayer();
    player.setItemInHand(Items.DIAMOND_PICKAXE);
    
    // 执行挖掘动作
    boolean success = player.mineBlock(targetPos);
    
    // 验证结果
    assertTrue(success);
    assertTrue(level.getBlockState(targetPos).isAir());
}
```

---

## 5\. 最终 jar 体积与性能优化

### 5\.1 依赖裁剪与最小化

#### 依赖分析与清理

- ✅ 移除所有第三方 Mod 依赖

- ✅ 仅保留 NeoForge 作为唯一依赖

- ✅ 剔除未使用的库和工具类

- ✅ 避免传递依赖引入

#### Gradle 配置优化

```groovy
dependencies {
    // 仅依赖NeoForge
    minecraft "net.neoforged:neoforge:${neo_forge_version}"
    
    // 不添加任何其他mod依赖
    // 不使用 compileOnly 引用其他mod
}

jar {
    // 不包含任何依赖库
    // 所有代码都是我们自己的
}
```

### 5\.2 无用代码剔除

#### 代码清理措施

1. **ProGuard 混淆优化**

    - 移除未使用的类、方法、字段

    - 优化字节码，删除调试信息

    - 重命名标识符缩短名称

2. **手动清理**

    - 删除调试代码和测试方法

    - 清理注释和 TODO 标记

    - 移除备用实现和废弃代码

3. **特性裁剪**

    - 仅保留核心功能，高级功能可选

    - 移除开发调试命令

    - 精简日志输出

### 5\.3 资源文件优化

#### 纹理与模型优化

- ✅ 所有纹理使用 PNG 压缩

- ✅ 移除不必要的高清纹理

- ✅ 合并小纹理为精灵图

- ✅ 简化模型面数

#### 语言文件优化

- ✅ 仅保留核心语言（中文 / 英文）

- ✅ 移除重复的翻译键

- ✅ 精简键名长度

#### 配置文件优化

- ✅ 默认配置内嵌代码，不单独打包

- ✅ 移除注释和空行

- ✅ 使用更紧凑的格式

### 5\.4 类加载优化

#### 懒加载策略

1. **按需初始化**：大对象首次使用时才创建

2. **延迟注册**：非核心功能延迟注册

3. **异步初始化**：后台线程初始化重组件

4. **类分离**：将不常用功能放到单独的类

#### 示例：懒加载单例

```java
public class HeavyComponent {
    private static HeavyComponent instance;
    
    private HeavyComponent() {
        // 重量级初始化
    }
    
    public static HeavyComponent getInstance() {
        if (instance == null) {
            synchronized (HeavyComponent.class) {
                if (instance == null) {
                    instance = new HeavyComponent();
                }
            }
        }
        return instance;
    }
}
```

### 5\.5 运行时性能优化目标

#### 性能指标目标

|指标|目标值|说明|
|---|---|---|
|Jar 体积|≤ 500KB|不含依赖的纯代码|
|启动延迟|≤ 100ms|Mod 初始化时间|
|路径规划|≤ 50ms|100 格内路径计算|
|内存占用|≤ 50MB|正常运行时|
|CPU 占用|≤ 5%|单核心|
|TPS 影响|≤ 0\.5|对服务器 TPS 影响|

#### 关键优化点

1. **路径规划**：使用更快的优先队列、节点缓存

2. **世界扫描**：批量读取、避免重复查询

3. **假人 Tick**：降低非活跃假人的 tick 频率

4. **事件处理**：批量处理、过滤无关事件

5. **GC 优化**：减少临时对象创建、对象池复用

---

## 6\. 开发里程碑与工作量评估

### 6\.1 里程碑 1：核心基础设施（2 周）

#### 第 1 周：项目搭建与基础框架

- ✅ NeoForge 开发环境搭建

- ✅ 项目结构与模块划分

- ✅ 核心 API 接口定义

- ✅ 事件总线与基础工具类

- ✅ 日志系统与配置框架

#### 第 2 周：假人系统基础与世界访问

- ✅ FakePlayer 核心类实现

- ✅ 假人管理器与生命周期

- ✅ 世界访问层抽象

- ✅ 基础方块状态缓存

- ✅ 单元测试框架搭建

**交付物**：可运行的基础 Mod，能创建假人并读取世界数据

### 6\.2 里程碑 2：路径规划核心（3 周）

#### 第 3 周：A \* 算法核心实现

- ✅ 节点数据结构设计

- ✅ A \* 核心算法实现

- ✅ 启发函数与代价计算

- ✅ 基础可通行性判断

- ✅ 算法单元测试

#### 第 4 周：世界扫描与缓存优化

- ✅ 区块扫描与异步加载

- ✅ LRU 缓存与增量更新

- ✅ 内存管理与性能优化

- ✅ 方块状态快速访问

- ✅ 缓存命中率优化

#### 第 5 周：基础运动控制

- ✅ 移动指令生成

- ✅ 路径跟随与校正

- ✅ 碰撞检测与避障

- ✅ 基础跳跃与下落控制

- ✅ 运动状态机

**交付物**：完整的自动寻路功能，可从 A 点走到 B 点

### 6\.3 里程碑 3：动作与交互（2 周）

#### 第 6 周：动作模拟系统

- ✅ 挖掘动作序列

- ✅ 放置动作序列

- ✅ 攻击动作模拟

- ✅ 交互动作（右键）

- ✅ 动画与姿态控制

#### 第 7 周：方块交互与物品管理

- ✅ 方块挖掘与工具选择

- ✅ 方块放置与方向控制

- ✅ 背包物品管理

- ✅ 热栏切换与装备

- ✅ 容器交互逻辑

**交付物**：假人可自主挖掘、放置方块，管理物品

### 6\.4 里程碑 4：任务与规则（2 周）

#### 第 8 周：状态机与调度

- ✅ 通用状态机框架

- ✅ 任务队列与优先级调度

- ✅ 超时与错误处理

- ✅ 暂停 / 恢复机制

- ✅ 任务组合与嵌套

#### 第 9 周：规则引擎与事件系统

- ✅ 条件表达式解析

- ✅ 规则匹配与触发

- ✅ 事件总线与订阅

- ✅ 事件过滤与处理

- ✅ 任务编排 DSL

**交付物**：可编写复杂自动化脚本，支持条件判断和事件响应

### 6\.5 里程碑 5：集成与优化（1 周）

#### 第 10 周：命令、配置与优化

- ✅ 完整命令系统

- ✅ 图形化配置界面

- ✅ 调试工具与可视化

- ✅ 性能分析与优化

- ✅ 最终集成测试

**交付物**：功能完整、性能优化的最终版本

### 6\.6 总工作量评估

|里程碑|时间|人周|主要产出|
|---|---|---|---|
|核心基础设施|2 周|2|项目框架、假人基础、世界访问|
|路径规划核心|3 周|3|A \* 算法、世界扫描、运动控制|
|动作与交互|2 周|2|动作模拟、方块交互、物品管理|
|任务与规则|2 周|2|状态机、调度、规则引擎|
|集成与优化|1 周|1|命令、配置、性能优化|
|**总计**|**10 周**|**10 人周**|**完整独立 Mod**|

**等效人月**：约 2\.5 人月（按 4 周 / 月计算）

**推荐团队配置**：

- 1 名资深开发者（全程）

- 1 名初级开发者（协助编码和测试）

- 0\.5 名测试人员（后期测试）

---

## 7\. 风险评估：自研 vs 直接依赖的利弊分析

### 7\.1 自研优势

#### ✅ 用户体验优势

- **零前置依赖**：玩家只需安装一个 jar，无需额外下载

- **版本兼容**：不受其他 Mod 更新节奏影响

- **安装简单**：降低新手用户门槛

- **冲突减少**：避免与其他 Mod 的兼容性问题

#### ✅ 技术控制优势

- **完全掌控**：100% 控制代码质量和架构

- **灵活定制**：可按需修改任何功能

- **独立更新**：按自己的节奏发布更新

- **Bug 修复**：发现问题可立即修复，不依赖第三方

#### ✅ 法律合规优势

- **许可证纯净**：无许可证冲突风险

- **知识产权清晰**：完全自主知识产权

- **商业友好**：无 Copyleft 传染

- **可闭源**：未来可选择闭源发布

#### ✅ 产品质量优势

- **体积更小**：单一 jar 通常小于多个依赖总和

- **性能更好**：针对性优化，无冗余代码

- **更稳定**：减少依赖链中的故障点

- **更安全**：减少第三方代码的安全风险

### 7\.2 自研风险

#### ⚠️ 开发周期风险

- **时间长**：10 周 vs 直接依赖的 1\-2 周

- **复杂度高**：需要深入理解多个复杂系统

- **技术门槛**：需要精通 Minecraft 底层机制

- **人员要求**：需要经验丰富的开发者

#### ⚠️ 功能完整性风险

- **功能缩水**：初期功能可能不如原 Mod 丰富

- **Bug 更多**：自研代码缺乏大规模用户测试

- **性能差距**：初期性能可能不如成熟的原 Mod

- **特性缺失**：可能遗漏一些边缘功能

#### ⚠️ 维护成本风险

- **长期维护**：需要持续跟进 Minecraft 版本更新

- **Bug 修复**：用户发现的问题需要自己修复

- **功能迭代**：新功能开发全部自己承担

- **技术债务**：初期快速开发可能积累技术债务

#### ⚠️ 技术挑战风险

- **算法复杂度**：A \* 路径规划调优难度大

- **网络同步**：假人数据同步容易出问题

- **物理模拟**：准确的玩家物理模拟困难

- **边缘情况**：各种异常情况处理不完备

### 7\.3 风险缓解策略

#### 针对开发周期长

1. **分阶段交付**

    - 第 4 周：交付核心寻路功能（MVP）

    - 第 6 周：交付基础自动化功能

    - 第 10 周：交付完整功能

2. **并行开发**

    - 路径规划与假人系统并行开发

    - 前端命令系统与后端逻辑并行

    - 测试人员提前介入

3. **复用成熟方案**

    - 使用通用的 A \* 算法实现

    - 参考公开的 FakePlayer 实现

    - 借鉴开源项目的最佳实践

#### 针对功能完整性

1. **优先级排序**

    - P0：核心寻路、假人移动、基础挖掘

    - P1：物品管理、容器交互、战斗

    - P2：高级规则、脚本系统、可视化

2. **渐进式增强**

    - 首发版本包含核心功能

    - 后续补丁逐步添加高级功能

    - 根据用户反馈调整优先级

3. **充分测试**

    - 自动化单元测试覆盖核心逻辑

    - 内部测试团队进行集成测试

    - 公开 Beta 测试收集用户反馈

#### 针对维护成本

1. **代码质量保障**

    - 严格的代码审查流程

    - 完善的文档和注释

    - 清晰的架构设计和模块划分

2. **自动化工具**

    - CI/CD 自动构建和测试

    - 版本更新自动化脚本

    - 错误日志自动收集分析

3. **社区参与**

    - 开源接受社区贡献

    - 建立 Issue 跟踪系统

    - 鼓励用户提交 Bug 报告和 PR

#### 针对技术挑战

1. **技术预研**

    - 关键技术提前做原型验证

    - 难点问题集中攻关

    - 必要时咨询领域专家

2. **容错设计**

    - 优雅降级：核心功能优先保障

    - 异常捕获：防止局部问题崩溃

    - 调试信息：完善的日志和诊断

3. **参考验证**

    - 与原 Mod 进行功能对比测试

    - 学习原 Mod 的问题解决方案

    - 吸收社区积累的经验教训

### 7\.4 最终决策建议

|评估维度|自研方案|直接依赖方案|推荐|
|---|---|---|---|
|用户体验|⭐⭐⭐⭐⭐|⭐⭐⭐|✅ 自研|
|开发周期|⭐⭐|⭐⭐⭐⭐⭐|❌ 自研较慢|
|功能完整性|⭐⭐⭐|⭐⭐⭐⭐⭐|⚠️ 初期差距|
|维护成本|⭐⭐⭐|⭐⭐⭐⭐|相当|
|法律风险|⭐⭐⭐⭐⭐|⭐⭐|✅ 自研|
|技术控制|⭐⭐⭐⭐⭐|⭐⭐|✅ 自研|
|产品质量|⭐⭐⭐⭐|⭐⭐⭐|✅ 自研|

**最终结论：强烈推荐自研方案**

虽然开发周期更长、初期投入更大，但从长期产品质量、用户体验、法律合规和技术自主性来看，自研方案具有显著优势。对于一个希望长期发展、打造优质产品的项目，这是值得的投入。

---

## 附录：合规声明模板

### 代码文件头声明

```java
/*
 * This file is part of [Your Mod Name].
 * 
 * [Your Mod Name] is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * [Your Mod Name] is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with [Your Mod Name].  If not, see <http://www.gnu.org/licenses/>.
 * 
 * NOTICE: This implementation was independently developed based on the
 * principles of pathfinding and automation in Minecraft. No source code
 * from Baritone, SiliconeDolls, or RollingGate was directly copied or
 * used in this project.
 */
```

### 关于页面声明

> **关于本 Mod**
> 
> 本 Mod 是完全独立开发的 Minecraft 自动化模组，所有代码均为原创实现。
> 
> 我们参考了以下项目的设计思想（但未直接使用任何源代码）：
> 
> - Baritone 的路径规划算法原理
> 
> - SiliconeDolls 的假人设计思路
> 
> - RollingGate 的任务编排思想
> 
> 本 Mod 与上述项目不存在任何代码依赖关系，用户无需安装任何前置 Mod 即可使用。
> 
> 

---

**文档结束**

> （注：文档部分内容可能由 AI 生成）
