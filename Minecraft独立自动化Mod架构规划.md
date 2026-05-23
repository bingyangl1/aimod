# Minecraft独立自动化Mod架构规划

## 集四大开源项目精华的终极融合方案

---

## 一、四项目横向对比分析表

|维度|PlayerEngine|Player2NPC|Baritone 1\.21\.1|SiliconeDolls|最佳实现方案|
|---|---|---|---|---|---|
|**假人实现**|✅ 接口化能力注入<br>✅ 组件化设计<br>❌ 依赖 Cardinal Components|✅ 基于 PlayerEngine 框架<br>✅ 完整 AI 控制循环<br>❌ 依赖 PlayerEngine|❌ 仅控制真实玩家<br>❌ 无独立假人实体|✅ 纯 FakePlayer 实现<br>✅ 服务端独立运行<br>✅ 无额外依赖|**SiliconeDolls \+ PlayerEngine**<br>采用 FakePlayer 为基础，融合接口化能力设计|
|**路径规划**|✅ 集成 Automatone \(Baritone 分支\)<br>✅ 任务驱动导航<br>❌ 性能一般|✅ 复用 PlayerEngine<br>✅ 高层命令解析|✅ 业界最优 A \* 实现<br>✅ 分段计算优化<br>✅ 增量成本回退<br>✅ 异步路径计算|❌ 无内置路径规划<br>❌ 仅基础移动指令|**Baritone**<br>采用其优化 A \* 算法、分段计算、异步架构|
|**世界感知**|✅ 基于组件的状态感知<br>✅ 与任务系统集成|✅ 复用 PlayerEngine|✅ 完整区块缓存系统<br>✅ 方块状态预计算<br>✅ 碰撞检测优化|❌ 基础世界交互<br>❌ 无高级感知|**Baritone**<br>采用其区块缓存、状态预计算架构|
|**动作模拟**|✅ 玩家级动作能力<br>✅ 饥饿 / 交互系统|✅ 完整玩家动作<br>✅ AI 行为驱动|✅ 精确移动控制<br>✅ 工具使用优化|✅ 细粒度动作指令<br>✅ 动画状态同步<br>✅ 连续 / 间隔动作模式|**SiliconeDolls \+ Baritone**<br>细粒度指令 \+ 精确移动控制|
|**API 设计**|✅ 接口驱动设计<br>✅ 能力可组合<br>❌ 依赖外部库|✅ 高层命令 API<br>✅ LLM 集成友好|✅ 完善的 JavaDoc<br>✅ 清晰的包边界<br>✅ 设置系统完善|✅ 命令式 API<br>✅ 群组管理 API|**PlayerEngine \+ Baritone**<br>接口化设计 \+ 完善文档|
|**依赖管理**|❌ 依赖 Cardinal Components<br>❌ 依赖 Player2 API|❌ 强依赖 PlayerEngine|✅ 最小依赖<br>✅ 自包含算法|✅ 纯 NeoForge 原生<br>✅ 零第三方依赖|**SiliconeDolls**<br>纯原生 API，零第三方依赖|
|**实体管理**|✅ 生命周期组件化<br>✅ 多实体支持|✅ 角色配置系统<br>✅ 人格化设定|❌ 单玩家控制|✅ 假人池管理<br>✅ 群组批量操作<br>✅ 持久化配置|**SiliconeDolls \+ PlayerEngine**<br>群组管理 \+ 组件化生命周期|
|**网络同步**|✅ 服务端权威模式<br>✅ 组件状态同步|✅ 复用 PlayerEngine|✅ 客户端预测<br>✅ 服务端验证|✅ 精确状态同步<br>✅ 动画插值|**SiliconeDolls**<br>精确状态同步与动画插值|
|**性能优化**|✅ 按需加载组件<br>✅ 事件驱动|✅ 复用 PlayerEngine|✅ 30x 性能优化<br>✅ BinaryHeap 优先队列<br>✅ 节点哈希去重|✅ 轻量级实体<br>✅ 懒加载|**Baritone**<br>数据结构级性能优化|

---

## 二、各项目核心精华提取

### 2\.1 PlayerEngine 核心设计精华

#### 架构模式创新

**\&\#34;玩家即接口\&\#34; 理念** \- 最具革命性的设计思想

- 将 \&\#34;玩家能力\&\#34; 从`PlayerEntity`类中解耦，定义为一组可组合的接口

- 任何`LivingEntity`只需实现接口即可获得玩家级能力

- 核心接口：`IAutomatone`、`IInventoryProvider`、`IInteractionManagerProvider`、`IHungerManagerProvider`

#### 代码组织最佳实践

1. **能力分离原则**

    - 每个玩家能力独立为一个 Manager 类

    - `LivingEntityInventory` \- 持久化玩家级背包

    - `LivingEntityInteractionManager` \- 方块 / 物品交互

    - `LivingEntityHungerManager` \- 饥饿值系统

2. **初始化模式**

    ```java
    public void init() {
        manager = new LivingEntityInteractionManager(this);
        inventory = new LivingEntityInventory(this);
        hungerManager = new LivingEntityHungerManager();
        controller = new AltoClefController(IBaritone.KEY.get(this));
    }
    ```

    - 极简初始化，无复杂继承链

    - 组合优于继承的典范

#### 性能优化策略

- **组件按需加载**：仅为需要的实体附加组件

- **事件驱动更新**：避免轮询检查，减少 tick 开销

- **服务端纯逻辑**：无客户端渲染负担，纯计算优化

---

### 2\.2 Player2NPC 假人实现独到之处

#### 实体管理创新

**AI 控制闭环设计** \- 从自然语言到具体动作的完整链路

1. **命令解析层**：Player2 API 将自然语言转为结构化命令

2. **任务调度层**：AltoClefController 接收高层命令

3. **执行层**：任务系统创建具体执行链（如`MineAndCollectTask`）

4. **动作层**：Automatone 路径规划 \+ 实体交互

#### 网络同步机制

- **服务端权威模式**：所有逻辑在服务端执行，客户端仅渲染

- **状态增量同步**：仅同步变化的属性，减少网络带宽

- **实体分离设计**：假人实体与真实玩家完全隔离，避免冲突

#### AI 控制最佳实践

- **角色人格化**：每个假人可配置独立性格和外观

- **问候机制**：AI 桥接层支持个性化问候语

- **对话上下文**：维护对话历史，支持连续交互

---

### 2\.3 Baritone 被忽略的宝藏功能

#### 超越 A \* 的算法优化

1. **分段计算机制**

    - 传统 A \* 需计算完整路径，Baritone 按渲染距离分段

    - 每段执行完再计算下一段，适应 Minecraft 区块加载

    - 支持超时中断和渲染距离边界终止

2. **增量成本回退**

    - 路径未完全计算时，智能选择最优分段

    - f\-cost 加权选择，确保局部最优

    - 避免长距离计算卡顿

3. **数据结构级优化**

    - `BinaryHeapOpenSet`：高效优先队列，O \(log n\) 取最小值

    - 哈希表节点去重：避免重复节点计算

    - 路径节点池：复用节点对象，减少 GC 压力

#### 世界交互系统

1. **方块破坏策略**

    - 工具自动选择，考虑挖掘速度和耐久

    - 方块硬度预计算，动态调整挖掘优先级

    - 安全挖掘检测，避免掉落伤害

2. **状态机设计**

    - 路径执行状态机：CALCULATING、EXECUTING、PAUSED

    - 任务优先级队列：支持任务抢占和挂起

    - 异常处理机制：路径中断自动重算

#### 内存占用控制

- **区块缓存 LRU**：最近使用的区块数据缓存，超时自动释放

- **路径节点限制**：单路径最大节点数限制，防止内存爆炸

- **异步计算隔离**：路径计算在独立线程，不阻塞主线程

---

### 2\.4 SiliconeDolls 动作模拟系统优势

#### 动画系统设计

**细粒度动作控制** \- Minecraft 中最精细的假人动作实现

1. **动作模式**

    - `once`：单次执行

    - `continue`：每 tick 连续执行

    - `interval \&lt;times\&gt;`：指定间隔执行

2. **完整动作集**

    - 基础动作：use/jump/attack/drop/sneak/sprint

    - 移动控制：mount/dismount/move

    - 视角控制：look/turn（支持绝对 / 相对坐标）

    - 物品操作：hotbar/dropstack

#### 玩家行为模拟

1. **状态精确同步**

    - 动画状态插值：避免动作跳变

    - 肢体旋转平滑：头部 / 身体 / 手臂独立旋转

    - 移动加速度模拟：真实玩家移动物理

2. **群组管理系统**

    - 假人分组：批量创建 / 销毁 / 控制

    - 配置持久化：假人属性保存到配置文件

    - Shadow 模式：替换离线玩家为 AFK 假人

#### 物理效果模拟

- **碰撞检测精确**：与真实玩家完全一致的碰撞盒

- **重力模拟**：准确的掉落和跳跃物理

- **流体交互**：水 / 熔岩中的移动模拟

---

## 三、最终融合架构设计

### 3\.1 整体架构概览

```Plain Text
┌─────────────────────────────────────────────────────────────┐
│                      应用层 (Application)                    │
├─────────────────────────────────────────────────────────────┤
│  命令解析  │  任务调度  │  行为树  │  AI桥接  │  配置管理  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      核心层 (Core)                           │
├─────────────────────────────────────────────────────────────┤
│  假人实体池  │  路径规划引擎  │  动作执行器  │  世界感知器  │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      能力层 (Capabilities)                   │
├─────────────────────────────────────────────────────────────┤
│  背包管理  │  交互管理  │  饥饿系统  │  装备系统  │  战斗系统 │
└─────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────┐
│                      平台层 (Platform)                       │
├─────────────────────────────────────────────────────────────┤
│              Fabric/NeoForge 原生API 零第三方依赖            │
└─────────────────────────────────────────────────────────────┘
```

### 3\.2 假人系统设计

**采用方案：SiliconeDolls FakePlayer \+ PlayerEngine 接口化能力**

#### 实体注册机制

```java
// 核心设计：不继承，不混入，纯组合
public class AutomationFakePlayer implements FakePlayer {
    // 基础FakePlayer实现（来自SiliconeDolls）
    private final ServerPlayerEntity delegate;
    
    // 可插拔能力模块（来自PlayerEngine思想）
    private final InventoryCapability inventory;
    private final InteractionCapability interaction;
    private final PathfindingCapability pathfinding;
    private final AnimationCapability animation;
    
    public AutomationFakePlayer(ServerWorld world, GameProfile profile) {
        this.delegate = createFakePlayer(world, profile);
        this.inventory = new InventoryCapability(this);
        this.interaction = new InteractionCapability(this);
        this.pathfinding = new PathfindingCapability(this);
        this.animation = new AnimationCapability(this);
    }
}
```

#### 生命周期管理

1. **创建阶段**

    - 假人池分配唯一 ID

    - 初始化所有能力模块

    - 注册到世界和追踪器

2. **运行阶段**

    - 主 tick 驱动各能力模块

    - 状态变更事件通知

    - 网络状态同步

3. **销毁阶段**

    - 资源清理

    - 从世界移除

    - 假人池回收

#### 多假人支持

- **假人池上限**：可配置，默认 32 个

- **资源隔离**：每个假人独立线程上下文

- **负载均衡**：tick 任务分散到不同时间片

- **优先级调度**：重要假人获得更多计算资源

---

### 3\.3 路径规划系统设计

*采用方案：Baritone 优化 A 精简重构版*\*

#### 算法核心优化

1. *A 核心改进*\*

    ```Plain Text
    F = G + H + P
    G = 实际移动成本（考虑方块硬度、工具效率）
    H = 欧氏距离启发函数（可采纳）
    P = 偏好加权（避免危险、偏好道路）
    ```

2. **分段计算策略**

    - 分段大小：渲染距离 × 0\.8（预留安全边界）

    - 计算超时：500ms（单段最大计算时间）

    - 前瞻缓存：提前计算下一段路径

3. **内存优化**

    - 节点对象池：复用 PathNode 对象，减少 GC

    - LRU 区块缓存：最多缓存 64 个区块数据

    - 路径压缩：合并连续直线移动节点

#### 性能指标目标

- 1000 格路径计算：\&lt; 100ms

- 内存占用：\&lt; 10MB / 假人

- CPU 占用：\&lt; 5% 单核心（4 假人同时寻路）

---

### 3\.4 动作模拟系统设计

**采用方案：SiliconeDolls 细粒度控制 \+ 行为树状态管理**

#### 动画插值系统

1. **平滑过渡算法**

    ```java
    // 线性插值 + 缓动函数
    public float interpolate(float from, float to, float progress) {
        float t = easeInOutQuad(progress);
        return from + (to - from) * t;
    }
    ```

2. **身体部位独立控制**

    - 头部旋转：\-90° \~ \+90° 俯仰，0° \~ 360° 水平

    - 身体旋转：与移动方向同步

    - 手臂摆动：挖掘 / 攻击 / 使用独立动画

    - 腿部移动：行走 / 跑步 / 跳跃步态模拟

#### 行为树架构

```Plain Text
Selector (根节点)
├─ Sequence: 紧急避险
│  ├─ Check: 受到伤害
│  └─ Action: 逃跑/反击
├─ Sequence: 任务执行
│  ├─ Check: 有活跃任务
│  └─ Action: 执行当前任务
└─ Sequence: 空闲行为
   ├─ Check: 无任务
   └─ Action: 随机巡逻/待机
```

#### 状态管理

- **状态枚举**：IDLE、MOVING、MINING、BUILDING、FIGHTING、INTERACTING

- **状态转换**：原子操作，避免竞态条件

- **状态持久化**：支持暂停 / 恢复任务链

---

### 3\.5 世界交互系统设计

**采用方案：Baritone 方块操作 \+ PlayerEngine 交互管理**

#### 挖掘系统

1. **工具选择策略**

    - 按挖掘速度排序（考虑附魔）

    - 耐久度检查（剩余耐久 \&gt; 10）

    - 背包自动切换

2. **挖掘安全检测**

    - 上方方块支撑检查

    - 液体流入检测

    - 怪物威胁评估

#### 放置系统

1. **方块放置逻辑**

    - 可放置面检测

    - 玩家朝向计算

    - 放置顺序优化（从近到远）

2. **背包管理**

    - 物品分类索引

    - 快捷栏自动填充

    - 背包整理策略

#### 交互系统

- 方块右键交互（工作台、熔炉、箱子）

- 实体交互（村民交易、坐骑）

- 物品使用（食物、药水、弓箭）

---

### 3\.6 独立无依赖架构实现

#### 核心原则：零第三方依赖，100% 使用加载器原生 API

1. **拒绝的依赖**

    - ❌ Cardinal Components API（自行实现轻量组件系统）

    - ❌ Player2 API（可选集成，非强制依赖）

    - ❌ 任何第三方库（Guava、Apache Commons 等）

    - ❌ Mixin 侵入性修改（仅必要时使用）

2. **Fabric/NeoForge 双平台支持**

    ```Plain Text
    src/
    ├── main/
    │   ├── java/
    │   │   └── com/automation/
    │   │       ├── core/          # 平台无关核心代码
    │   │       ├── fabric/        # Fabric 特定实现
    │   │       └── neoforge/      # NeoForge 特定实现
    │   └── resources/
    └── test/
    ```

3. **自包含所有功能**

    - 路径规划算法：完整重写，不依赖 Baritone jar

    - 假人实体：基于加载器原生 FakePlayer 接口

    - 数学工具：自行实现向量、矩阵、几何计算

    - 数据结构：自行实现优先队列、LRU 缓存

---

## 四、技术选型与项目规划

### 4\.1 技术选型最终定版

|类别|选型|版本|理由|
|---|---|---|---|
|**Mod 加载器**|Fabric \+ NeoForge|双平台|Fabric 轻量适合技术向，NeoForge 生态好|
|**Minecraft 版本**|1\.21\.1|1\.21\.1|最新稳定版，API 成熟，参考项目均支持|
|**JDK 版本**|JDK 21|LTS|Minecraft 1\.21\+ 官方要求，性能最优|
|**构建工具**|Gradle|8\.5|Minecraft Mod 标准构建工具|
|**代码规范**|Google Java Style|最新|业界标准，可读性好|
|**测试框架**|JUnit 5 \+ Mockito|5\.10|单元测试 \+ 模拟测试|

### 4\.2 12 周开发里程碑

#### **第 1 周：项目初始化与基础框架**

- ✅ 搭建 Gradle 多平台构建环境

- ✅ 核心包结构定义

- ✅ 假人实体基础类实现

- ✅ 第一个可运行假人 Demo

- **交付物**：可编译项目，能生成假人实体

#### **第 2 周：假人系统核心**

- 假人池管理实现

- 实体生命周期管理

- 基础网络同步

- 假人创建 / 销毁命令

- **交付物**：完整假人管理系统

#### **第 3 周：路径规划引擎 \- 算法层**

- A \* 算法核心实现

- BinaryHeap 优先队列

- 节点池与内存优化

- 基础路径计算测试

- **交付物**：独立路径计算库

#### **第 4 周：路径规划引擎 \- 集成层**

- 世界感知与区块缓存

- 移动成本计算

- 分段路径执行

- 路径跟随控制器

- **交付物**：假人可自动走到指定坐标

#### **第 5 周：动作模拟系统**

- 动画插值实现

- 细粒度动作控制

- 身体部位独立旋转

- 动作状态机

- **交付物**：自然流畅的假人动作

#### **第 6 周：世界交互 \- 挖掘系统**

- 方块挖掘逻辑

- 工具自动选择

- 挖掘安全检测

- 挖掘进度同步

- **交付物**：假人可自动挖掘方块

#### **第 7 周：世界交互 \- 放置与背包**

- 方块放置逻辑

- 背包管理系统

- 物品分类索引

- 快捷栏自动切换

- **交付物**：假人可放置方块、管理物品

#### **第 8 周：任务调度系统**

- 任务抽象层设计

- 任务队列与优先级

- 任务暂停 / 恢复 / 取消

- 基础任务实现（Goto、Mine、Collect）

- **交付物**：可执行高层命令

#### **第 9 周：行为树与 AI**

- 行为树框架实现

- 基础行为节点库

- 紧急避险逻辑

- 空闲行为模拟

- **交付物**：智能自主行为

#### **第 10 周：群组管理与配置**

- 假人分组功能

- 批量控制命令

- 配置文件系统

- 假人持久化

- **交付物**：多假人协同工作

#### **第 11 周：性能优化与测试**

- 性能基准测试

- 内存泄漏检测

- 多假人压力测试

- Bug 修复与稳定性

- **交付物**：性能优化报告

#### **第 12 周：发布准备与文档**

- API 文档编写

- 使用教程

- 示例配置

- 正式版本构建

- **交付物**：正式发布 jar 包

### 4\.3 团队分工与人天估算

|模块|负责人|人天|主要工作内容|
|---|---|---|---|
|**项目架构师**|架构师|10 人天|整体架构设计、技术选型、代码评审|
|**假人系统**|开发 A|15 人天|实体类、假人池、生命周期、网络同步|
|**路径规划**|开发 B|20 人天|A \* 算法、世界感知、路径执行、性能优化|
|**动作模拟**|开发 C|12 人天|动画插值、动作控制、状态机、行为树|
|**世界交互**|开发 D|18 人天|挖掘、放置、背包、交互、战斗|
|**任务系统**|开发 E|12 人天|任务调度、命令解析、AI 桥接|
|**测试工程师**|测试|10 人天|单元测试、集成测试、性能测试|
|**文档工程师**|文档|8 人天|API 文档、使用教程、示例|
|**总计**|\-|**105 人天**|\-|

---

## 五、第一周可执行开发任务清单

### Day 1：项目初始化

#### 任务 1\.1：创建 Gradle 项目结构

```bash
# 1. 创建项目目录
mkdir AutomationMod
cd AutomationMod

# 2. 初始化Gradle Wrapper
gradle init --type java-library

# 3. 创建多平台目录结构
mkdir -p src/main/java/com/automation/core
mkdir -p src/main/java/com/automation/fabric
mkdir -p src/main/java/com/automation/neoforge
mkdir -p src/main/resources/assets/automation
mkdir -p src/main/resources/data/automation
```

#### 任务 1\.2：配置 build\.gradle

- 配置 Fabric Loom 插件

- 配置 NeoForge 插件

- 设置依赖：仅 Minecraft \+ 加载器

- 配置打包：生成独立 jar

#### 任务 1\.3：创建 mod 主类

```java
// Fabric主类
package com.automation.fabric;

import net.fabricmc.api.ModInitializer;

public class AutomationModFabric implements ModInitializer {
    public static final String MOD_ID = "automation";
    
    @Override
    public void onInitialize() {
        // 初始化核心
        AutomationCore.init();
        // 注册命令
        CommandRegistration.register();
    }
}
```

### Day 2：核心类结构定义

#### 任务 2\.1：定义假人能力接口

```java
// src/main/java/com/automation/core/capability/ICapability.java
package com.automation.core.capability;

public interface ICapability {
    void tick();
    void init();
    void dispose();
}

// IInventoryCapability.java
public interface IInventoryCapability extends ICapability {
    ItemStack getStack(int slot);
    void setStack(int slot, ItemStack stack);
    boolean addItem(ItemStack stack);
    int getSelectedSlot();
    void setSelectedSlot(int slot);
}

// IInteractionCapability.java
public interface IInteractionCapability extends ICapability {
    boolean startMining(BlockPos pos);
    void stopMining();
    boolean placeBlock(BlockPos pos, ItemStack stack);
    void useItem();
}
```

#### 任务 2\.2：定义假人实体接口

```java
// src/main/java/com/automation/core/entity/IAutomationPlayer.java
package com.automation.core.entity;

public interface IAutomationPlayer {
    // 基础信息
    UUID getUUID();
    String getName();
    ServerWorld getWorld();
    
    // 位置与旋转
    Vec3d getPos();
    void setPos(Vec3d pos);
    float getYaw();
    float getPitch();
    void setRotation(float yaw, float pitch);
    
    // 能力获取
    <T extends ICapability> T getCapability(Class<T> type);
    
    // 生命周期
    void spawn();
    void despawn();
    void tick();
}
```

### Day 3：基础假人实体实现

#### 任务 3\.1：FakePlayer 基础实现

```java
// src/main/java/com/automation/core/entity/AutomationFakePlayer.java
package com.automation.core.entity;

public class AutomationFakePlayer implements IAutomationPlayer {
    private final ServerPlayerEntity player;
    private final Map<Class<? extends ICapability>, ICapability> capabilities;
    
    public AutomationFakePlayer(ServerWorld world, GameProfile profile) {
        this.player = createFakePlayer(world, profile);
        this.capabilities = new HashMap<>();
        initCapabilities();
    }
    
    private ServerPlayerEntity createFakePlayer(ServerWorld world, GameProfile profile) {
        // 使用加载器原生FakePlayer API
        MinecraftServer server = world.getServer();
        ServerPlayerEntity fakePlayer = new ServerPlayerEntity(
            server, world, profile, null
        );
        return fakePlayer;
    }
    
    private void initCapabilities() {
        capabilities.put(IInventoryCapability.class, new InventoryCapability(this));
        capabilities.put(IInteractionCapability.class, new InteractionCapability(this));
    }
    
    @Override
    public void tick() {
        player.tick();
        capabilities.values().forEach(ICapability::tick);
    }
}
```

#### 任务 3\.2：假人池管理器

```java
// src/main/java/com/automation/core/entity/FakePlayerPool.java
package com.automation.core.entity;

public enum FakePlayerPool {
    INSTANCE;
    
    private final Map<UUID, AutomationFakePlayer> players = new ConcurrentHashMap<>();
    
    public AutomationFakePlayer create(ServerWorld world, String name) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        AutomationFakePlayer player = new AutomationFakePlayer(world, profile);
        players.put(player.getUUID(), player);
        return player;
    }
    
    public void destroy(UUID uuid) {
        AutomationFakePlayer player = players.remove(uuid);
        if (player != null) {
            player.despawn();
        }
    }
    
    public void tickAll() {
        players.values().forEach(AutomationFakePlayer::tick);
    }
}
```

### Day 4：命令系统与测试

#### 任务 4\.1：注册基础命令

```java
// /fakeplayer spawn <name>
// /fakeplayer kill <name>
// /fakeplayer list
```

#### 任务 4\.2：实现背包能力基础版

- 基础物品存取

- 快捷栏切换

### Day 5：Demo 验证与优化

#### 任务 5\.1：第一个可运行 Demo

- 输入命令生成假人

- 假人出现在世界中

- 假人可见可交互

#### 任务 5\.2：代码审查与重构

- 检查依赖：确保无第三方库引入

- 检查 API：仅使用加载器原生接口

- 性能检查：无明显内存泄漏

#### 任务 5\.3：第一周总结

- 编译生成 jar 包

- 测试在纯净客户端运行

- 记录问题与下周计划

---

## 六、质量保障与验收标准

### 核心验收指标

1. **独立性**：jar 可单独运行，无需任何前置 Mod

2. **性能**：4 假人同时运行，TPS \&gt; 18

3. **稳定性**：连续运行 24 小时无崩溃

4. **兼容性**：Fabric 和 NeoForge 双平台正常工作

5. **功能完整**：假人可自主移动、挖掘、放置物品

### 代码质量标准

- 单元测试覆盖率 \&gt; 80%

- 无 Checkstyle 警告

- JavaDoc 覆盖率 \&gt; 90%

- 无循环依赖

- 每个类职责单一

---

**文档版本**：v1\.0 最终版
**创建日期**：2026 年 5 月 23 日
**适用项目**：Minecraft 独立自动化 Mod 开发

> （注：文档部分内容可能由 AI 生成）
