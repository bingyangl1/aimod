# OpenCode & OpenClaw 深度分析 — 对 AIMod 的架构启发

> 分析日期: 2026-05-26 | 基于 OpenCode v1.15+ (161K stars) 和 OpenClaw v2026.4.x (340K+ stars)

---

## 目录

1. [OpenCode 深度分析](#1-opencode-深度分析)
   - 1.1 [多 Agent 架构](#11-多-agent-架构)
   - 1.2 [任务调度与并行执行](#12-任务调度与并行执行)
   - 1.3 [模型成本分层路由](#13-模型成本分层路由)
   - 1.4 [MCP + LSP 工具系统](#14-mcp--lsp-工具系统)
   - 1.5 [Session 管理与持久化](#15-session-管理与持久化)
2. [OpenClaw 深度分析](#2-openclaw-深度分析)
   - 2.1 [ContextEngine 生命周期](#21-contextengine-生命周期)
   - 2.2 [分层记忆架构](#22-分层记忆架构)
   - 2.3 [Task Control Plane](#23-task-control-plane)
   - 2.4 [插件 Slot 系统](#24-插件-slot-系统)
   - 2.5 [OTEL 全链路可观测](#25-otel-全链路可观测)
3. [交叉对比：共同架构智慧](#3-交叉对比共同架构智慧)
4. [AIMod 现状差距分析](#4-aimod-现状差距分析)
5. [具体改进方案（按文件映射）](#5-具体改进方案按文件映射)
6. [分阶段开发路线](#6-分阶段开发路线)

---

## 1. OpenCode 深度分析

### 1.1 多 Agent 架构

**核心设计**: 主-子 Agent 两层模型，每种 Agent 有独立的权限控制和工具集。

```
┌──────────────────────────────────────┐
│  Primary Agents (Tab 切换)            │
│  ┌─────────┐ ┌─────────┐ ┌────────┐ │
│  │  Build  │ │  Plan   │ │ Scout  │ │
│  │ 全部工具 │ │ 只读    │ │ 只读   │ │
│  └────┬────┘ └─────────┘ └────────┘ │
│       │                               │
│       │ Task Tool 动态派发             │
│       ▼                               │
│  ┌─────────────────────────────────┐  │
│  │  Subagents (独立 Session)        │  │
│  │  ┌─────────┐ ┌─────────┐        │  │
│  │  │ General │ │ Explore │ ...    │  │
│  │  │ 全工具   │ │ 只读    │        │  │
│  │  └─────────┘ └─────────┘        │  │
│  │  每个子 Agent:                    │  │
│  │  - 独立 Session（上下文隔离）      │  │
│  │  - task: false（禁止递归）        │  │
│  │  - 单向结果报告（不污染主上下文）   │  │
│  └─────────────────────────────────┘  │
└──────────────────────────────────────┘
```

**关键实现细节:**

| 机制 | 实现 |
|------|------|
| Agent 定义 | YAML Frontmatter + Markdown 文件（`mode: primary/subagent/all`） |
| 上下文隔离 | `Session.create()` 为每个子 Agent 创建独立会话 |
| 反递归 | 子 Agent 的 `task` 工具设为 `false` |
| 结果回流 | 通过 `Bus` 事件系统传递 `PartUpdated` 事件 |
| 模型继承 | 子 Agent 默认继承父 Agent 模型，可覆盖 |

**对 AIMod 的启发:**

AIMod 当前只有一个 LLM 调用入口（`BotAIManager.parseCommand()`），规划和执行混合在同一个 prompt 中。这导致：
- 规划质量受执行细节干扰
- 无法复用规划结果（当前 PlanCache 只缓存成功/失败，不缓存中间规划）
- 上下文窗口被过长的执行历史污染

**建议改造**: 将 LLM 调用分离为 `PlanPhase`（只输出任务序列）+ `ReplanPhase`（执行失败时）。PlanPhase 的结果可以被 PlanCache 充分复用，ReplanPhase 只携带最近失败上下文。

### 1.2 任务调度与并行执行

**核心设计**: Task Tool 实现动态子 Agent 派发，`Promise.all()` 并行执行独立子任务。

```
主 Agent 发出:
  task(type="explore", prompt="找到所有 API 端点")
  task(type="explore", prompt="找到所有数据库模型")
  task(type="general", prompt="检查认证中间件")
        ↓ 并行执行（Promise.all）
  [Explorer] → 结果1 ─┐
  [Explorer] → 结果2 ─┼→ 主 Agent 汇总 → 下一步
  [General]  → 结果3 ─┘
```

**OpenCode Hive 的高级模式** — 成本优化模型分层:

```
SUB 模型 (订阅制, $0 边际成本)
  ├── Plan Agent     — 任务规划、路由
  ├── Orchestrator   — 只使用 task 工具分发，不直接执行
  └── Wiki Curator   — 文档维护

MID 模型 (按量付费)
  ├── Build Agent    — 主力代码生成
  └── General Agent  — 通用任务执行

PREMIUM 模型 (最贵, 手动覆盖)
  └── 仅用于最难问题
```

**并发控制（oh-my-opencode BackgroundManager）:**

```
三层并发限制:
  模型级: anthropic/claude-opus → max 2 并发
  提供商级: anthropic → max 3 并发
  全局默认: 5 并发

任务生命周期:
  pending → (获取槽位) → running → (满足三关) → completed
                                      ↓
                              ① 最小运行时间 (5s)
                              ② 有效输出存在
                              ③ 所有 Todo 完成
```

**对 AIMod 的启发:**

AIMod 的 `ChainManager` 是串行的（每 tick 只运行一条链），多 bot 场景下也是各自独立串行。应该支持：
- **并行链**: 标记为 `parallel=true` 的 action 可以与其他 action 同时分派给不同 bot
- **并发控制**: 限制同时进行的 LLM 调用数量（当前无限制，可能触发 API rate limit）
- **任务最小运行时间**: 防止 action 在 1 tick 内就判定失败（当前 `MoveToAction` 有时因路径未就绪立即 FAILED）

### 1.3 模型成本分层路由

**核心设计**: 根据任务复杂度自动选择模型。

```
任务复杂度评估 → 路由决策:
  简单 (grep/search)      → Haiku ($0.25/M tokens)
  中等 (refactor/simple)  → Sonnet ($3/M tokens)
  复杂 (architecture/bug) → Opus ($15/M tokens)
```

**对 AIMod 的启发:**

AIMod 已经有双层规划器（LLM + SequencePlanner），但使用场景有限。应该扩大本地规划器覆盖：

| 任务复杂度 | 当前方案 | 建议方案 |
|-----------|---------|---------|
| "挖 10 个石头" | LLM (2-10s) | 本地 SequencePlanner (0ms) |
| "砍 5 棵树" | LLM | 本地 CommandParser (0ms) |
| "收集 3 种不同矿石" | LLM | SequencePlanner 扩展 (0ms) |
| "造一把钻石剑" | LLM → MaterialTree | 保持 LLM（需要多步推理） |
| "建造一个小房子" | LLM | 保持 LLM（需要空间推理） |

关键洞察: AIMod 的 `DirecCommandHandler` 已经处理了最简命令，`SequencePlanner` 处理了合成链，但中间复杂度（"收集3种矿石"→需要多个 gather + 可能 smelt）还没有本地覆盖。

### 1.4 MCP + LSP 工具系统

**核心设计**: MCP (Model Context Protocol) 提供标准化的外部工具集成，LSP 提供代码智能。

```
MCP Server 生命周期:
  启动 → initialize → tools/list → 注册工具 → 运行时 tool/call
  支持 stdio 和 SSE 两种传输

LSP 集成:
  gopls / typescript-language-server → diagnostics → 自动注入 LLM prompt
  500ms debounce → 限制 20 diagnostics/file, 5 files/batch
```

**对 AIMod 的启发:**

MCP 的标准化工具协议可以映射到 AIMod 的 Action 系统：
- 每个 Action 类可以注册为 "工具"，带 schema 描述
- LLM 通过工具名调用（而非生成 JSON 再解析）
- 这其实就是 OpenAI Function Calling 模式，AIMod 可以考虑支持

LSP 的 diagnostics 自动注入模式很值得借鉴：
- Bot 的"感知结果"（scanEnvironment、inventory check）可以自动注入 LLM prompt
- 不需要在 prompt 中手写世界状态 → 让系统自动 append 结构化感知数据

### 1.5 Session 管理与持久化

**核心设计**: SQLite 持久化 Session，支持跨重启恢复。

```
Session 生命周期:
  /new → 创建 Session (SQLite INSERT)
  每轮对话 → 追加到 JSONL + SQLite
  /compact → 压缩历史为摘要
  /session <id> → 切换/恢复 Session
  --continue → 自动恢复最后一个 Session
```

**对 AIMod 的启发:**

AIMod 的任务状态完全是内存中的（`Task` 对象在 JVM 堆上），服务器重启全部丢失。OpenCode 的 Session 模型可以直接映射：

```
OpenCode Session → AIMod BotSession
  一个对话会话   → 一个 bot 从创建到移除的完整生命周期
  持久化到 SQLite → 持久化到 SQLite (bot_id, task_queue, memory)
```

---

## 2. OpenClaw 深度分析

### 2.1 ContextEngine 生命周期

**这是 OpenClaw 最核心的架构创新。** ContextEngine 将上下文管理从框架内部硬编码升级为插件化生命周期，7 个钩子覆盖完整流程：

```
┌──────────────────────────────────────────────┐
│         ContextEngine 生命周期                 │
│                                               │
│  bootstrap ──→ ingest ──→ assemble ──→ LLM   │
│     │            │           ↑         │      │
│     │            ▼           │         ▼      │
│     │         compact ◄──────┘    afterTurn  │
│     │            │                    │       │
│     │            ▼                    ▼       │
│     │      prepareSubagentSpawn  onSubagentEnded
│     │            │                    │       │
│     └────────────┴────────────────────┘       │
│             dispose (清理)                     │
└──────────────────────────────────────────────┘
```

**每个钩子的精确语义:**

| 钩子 | 调用时机 | 需要做什么 | 输入 | 输出 |
|------|---------|-----------|------|------|
| `bootstrap` | Session 首次启动 | 恢复长期记忆、初始化引擎状态 | session metadata | void |
| `ingest` | 每条消息产生后 | 逐条处理消息，决定存储策略 | single message | void |
| `assemble` | 每次 LLM 调用前 | 决定本轮带什么上下文 | session state | `{messages, estimatedTokens, systemPromptAddition}` |
| `compact` | Token 接近上限 | 压缩上下文 | current messages | `{ok, compacted, summary, tokensBefore, tokensAfter}` |
| `afterTurn` | Agent 回复完成后 | 持久化、触发后台任务 | turn result | void |
| `prepareSubagentSpawn` | 子 Agent 派发前 | 决定子 Agent 继承什么上下文 | parent session | subagent context |
| `onSubagentEnded` | 子 Agent 结束后 | 结果回流、清理 | subagent result | void |

**三种压缩触发路径:**

1. **自动触发**: Token 接近窗口限制 → `compact()` 方法
2. **手动触发**: 用户执行 `/compact` → 同样路由到 `compact()`
3. **Overflow 重试**: 模型返回 Token 超限错误 → 自动重试（最多 3 次）

**设计模式: Strangler Fig（绞杀者模式）**

LegacyContextEngine 将旧逻辑完整包装在新接口中。不配置插件时，行为与升级前 100% 一致。这是零风险升级的关键。

**lossless-claw 插件 — OOLONG benchmark 74.8 vs Claude Code 70.3:**
- 基于 LCM（无损上下文管理）
- 旧对话持久化到 SQLite + DAG 摘要结构
- 上下文组装时动态检索相关历史

**对 AIMod 的启发（核心借鉴点）:**

AIMod 的 LLM 上下文管理目前是硬编码在 `BotAIManager.collectWorldContext()` 和 `incrementalReplan()` 中。可以直接映射 ContextEngine 生命周期到 Minecraft bot:

| OpenClaw Hook | AIMod 映射 | 当前状态 | 建议 |
|---------------|-----------|---------|------|
| `bootstrap` | Bot 加入世界 / 服务器启动 | ❌ 无持久化 | 从 SQLite 恢复 bot 记忆、未完成任务 |
| `ingest` | 每个 tick 的世界观察 | ⚠️ `collectWorldContext()` 每次全量重建 | 增量更新结构化的 WorldState 对象 |
| `assemble` | LLM 调用前构建 prompt | ⚠️ 硬编码字符串拼接 | 从 WorldState + MemoryStore 动态组装 |
| `compact` | Token 接近上限 | ❌ 无 | 将旧的观察/action 结果压缩为单行摘要 |
| `afterTurn` | LLM 响应后 | ⚠️ 仅 PlanCache.store() | 更新 PlanCache + 短期记忆 + 长期知识 |
| `prepareSubagentSpawn` | 多 bot 协调 | ❌ 无 | 决定子任务 bot 继承哪些上下文 |
| `onSubagentEnded` | 子任务完成 | ❌ 无 | 提取关键结果，更新主任务状态 |

### 2.2 分层记忆架构

**核心设计:** 记忆系统独立于上下文管理系统，两者职责不同。

```
ContextEngine（上下文管理）          Memory 插件（记忆系统）
─────────────────────────          ─────────────────────
管理范围: 当前 session 内消息      管理范围: 跨 session 持久化知识
核心问题: 哪些消息送模型、何时压缩   核心问题: 怎么索引、搜索、存取记忆
生命周期: 一个 session 内          生命周期: 跨越所有 session
存储介质: session JSONL            存储介质: Markdown + SQLite/LanceDB
```

**三层记忆模型:**

```
工作记忆 (Working Memory)
  ├── 当前 tick 的感知数据（附近方块、实体、威胁）
  ├── 最近 N 条 action 及结果
  └── 生命周期: 当前 LLM 调用上下文窗口 (~数分钟)

     ↓ 自动提炼 (afterTurn / ingest)

短期摘要 (Short-term Summary)
  ├── 每 10 个 action 生成一句摘要
  ├── "收集了 12 个 oak_log，路径经过 swamp_biome"
  └── 生命周期: 当前 bot 会话 (~数小时)

     ↓ 持久化到文件 / SQLite

长期知识 (Long-term Knowledge)
  ├── 已知配方、资源位置、已探索区域
  ├── 玩家偏好、常见任务模式
  └── 生命周期: 跨服务器重启 (~永久)
```

**mem9/db0 插件的操作链路:**

```
bootstrap  → 从外部持久化存储恢复长期信息
assemble   → 按当前任务动态检索相关记忆，注入 prompt（不塞全量）
afterTurn  → 每轮结束后立即提炼事实、偏好、决策为长期记忆
compact    → 在 Token 紧张时，与外部记忆策略协同（而非替代记忆系统）
```

**对 AIMod 的启发:**

AIMod 当前只有"工作记忆"（LLM 上下文中最近的事件）。这是 bot"健忘"的根本原因——重启后完全不知道之前做过什么。

**具体方案:**

1. **短期摘要层** (低工作量): 在 `BotAIManager` 中维护 `List<String> recentSummaries`，每 10 个 action 自动生成一句摘要，注入下次 LLM prompt
2. **长期知识层** (中等工作量): 新建 `BotMemoryStore` 类，SQLite 存储，记录:
   - `known_resource_locations`: (block_type, x, y, z, dimension) — "哪里有什么矿"
   - `explored_chunks`: (chunk_x, chunk_z, dimension) — "探索过哪些区域"
   - `player_preferences`: (key, value) — "玩家喜欢用什么工具"
   - `task_history`: (command, success, actions_json, timestamp) — 扩展 PlanCache

### 2.3 Task Control Plane

**核心设计:** 统一 ACP、subagent、cron、CLI 四种执行体到 SQLite 任务账本。

```
Task Flow Registry:
  openclaw flows list       → 列出所有活动任务流
  openclaw flows show <id>  → 查看任务详情
  openclaw flows cancel <id> → 取消任务

Task 生命周期（持久化到 SQLite）:
  CREATED → QUEUED → RUNNING → COMPLETED/FAILED
     ↓         ↓        ↓
  可取消    可重排   被阻塞任务可持久化后重试
                       子任务结果可回溯父会话
```

**安全加固 — 语义审批:**
- ACP 审批从"按工具名"改为"按语义类别"
- 同一类别（如 `fs.write` + `fs.rm`）共享审批状态
- 插件安装默认 fail-closed（需要显式启用）

**对 AIMod 的启发:**

AIMod 当前任务状态在 `Task` 对象中（内存），需要持久化：

```java
// 建议: TaskLedger.java (SQLite)
CREATE TABLE tasks (
    id TEXT PRIMARY KEY,
    bot_uuid TEXT NOT NULL,
    command TEXT NOT NULL,
    status TEXT NOT NULL,  -- CREATED, IN_PROGRESS, COMPLETED, FAILED
    actions_json TEXT,
    current_action_index INT DEFAULT 0,
    created_at INT,
    completed_at INT,
    retry_count INT DEFAULT 0,
    parent_task_id TEXT  -- 支持子任务
);
```

### 2.4 插件 Slot 系统

**核心设计:** 每个能力有一个 "slot"，插件独占注册。不配置时走 legacy 默认实现。

```typescript
// 注册（全局注册表，Symbol.for() 挂载在 globalThis）
registerContextEngine("my-engine", () => new MyContextEngine());

// 切换（一行配置）
{ "plugins": { "slots": { "contextEngine": "my-engine" } } }
```

**独占 Slot 列表:**
- `contextEngine` — 上下文管理（最重要的 slot）
- 每个 slot 只有一个活跃插件
- 声明 `info.ownsCompaction: true` 可接管压缩控制权

**对 AIMod 的启发:**

这个模式非常适合 AIMod 的 BehaviorChain 系统。当前 ChainManager 硬编码了 4 条链，新增链需要修改核心代码：

```java
// 建议: 将 Chain 注册改为 Slot 模式
public class ChainManager {
    private final Map<String, BehaviorChain> slots = new LinkedHashMap<>();

    public void registerSlot(String name, BehaviorChain chain) {
        slots.put(name, chain);
    }
    // "danger" slot → DangerChain
    // "defense" slot → DefenseChain
    // "food" slot → FoodChain
    // "unstuck" slot → UnstuckChain
    // 第三方可注册自定义 chain 到新 slot
}
```

### 2.5 OTEL 全链路可观测

**核心设计:** OpenTelemetry 集成，追踪模型调用、token 消费、工具循环、上下文组装。

```
Trace Span 层次:
  session.turn
    ├── context.assemble (token 估算、系统提示)
    ├── llm.call (模型、延迟、token 消耗)
    │   ├── llm.stream (chunk 级追踪)
    │   └── llm.retry (重试次数、退避)
    ├── tool.execute (工具名、参数、耗时)
    └── context.compact (压缩前后 token 对比)
```

**对 AIMod 的启发:**

AIMod 的 `DevLog` 系统已经记录了关键事件，但缺少结构化指标。建议增加:

```java
// BotMetrics.java — 结构化性能指标
public class BotMetrics {
    long llmCallCount;
    long totalTokensConsumed;
    long pathfindingTimeMs;
    long actionSuccessCount;
    long actionFailureCount;
    Map<String, Long> actionTypeCounts;  // 每种 action 的执行次数
    // 输出: /aimod metrics 命令
}
```

---

## 3. 交叉对比：共同架构智慧

### 3.1 两者共同的设计原则

| 原则 | OpenCode 实现 | OpenClaw 实现 | AIMod 当前 |
|------|-------------|-------------|-----------|
| **分离关注点** | Plan Agent ≠ Build Agent | ContextEngine ≠ Memory 插件 | ❌ LLM 调用混合规划+执行 |
| **上下文隔离** | 子 Agent 独立 Session | 子 Agent 独立上下文继承 | ❌ 所有 bot 共享 LLM 历史 |
| **插件化扩展** | 25+ Event Hooks | ContextEngine Slot 注册 | ❌ 硬编码 |
| **持久化优先** | SQLite Session | SQLite Task Ledger | ⚠️ 仅 PlanCache JSON |
| **可观测性** | Plugin event tracing | OTEL 全链路 | ⚠️ DevLog 文本日志 |
| **模型分层** | Haiku→Sonnet→Opus | Gateway 多模型路由 | ❌ 单一模型 |

### 3.2 架构演进路径的共同模式

两个项目都走了相同的演进路径:

```
阶段 1: 单体 Agent（单一 LLM 调用）
   ↓
阶段 2: 工具调用（LLM 可以调用外部工具）
   ↓
阶段 3: 子 Agent 派发（LLM 可以派发子任务）
   ↓
阶段 4: 上下文管理独立（ContextEngine / Session 系统）
   ↓
阶段 5: 记忆系统独立（Memory 插件 / 长期持久化）
   ↓
阶段 6: 全链路可观测 + 多 Agent 协调
```

AIMod 目前处于**阶段 2-3 之间**（有工具调用 = Action 系统，有子任务 = incrementalReplan，但上下文管理和记忆系统尚未独立）。

---

## 4. AIMod 现状差距分析

### 4.1 架构对比总览

| 维度 | OpenCode | OpenClaw | AIMod 当前 | 差距 |
|------|----------|----------|-----------|------|
| Agent 数量 | 3 Primary + N Subagent | 可配置 N Agent | 1 (单 LLM 调用) | 大 |
| 上下文管理 | Session + compact | ContextEngine 7 hooks | 硬编码字符串拼接 | **最大** |
| 记忆系统 | SQLite Session | 3 层记忆 (工作/短期/长期) | PlanCache JSON | **最大** |
| 任务持久化 | SQLite | SQLite Task Ledger | 无 (内存) | 大 |
| 并行执行 | Promise.all() | 子 Agent 并发 | 串行 Chain | 中 |
| 模型路由 | 多模型分层 | Gateway 多提供商 | 单模型 | 中 |
| 可观测性 | Plugin events | OTEL 全链路 | DevLog 文本 | 中 |
| 插件系统 | 25+ Event Hooks | Slot 注册 | 无 | 大 |
| 工具协议 | MCP 标准 | 内置工具 | Action 类 | 小 |
| Plan/Execute 分离 | Plan Agent (只读) | Plan + Execute | 混合 | 中 |

### 4.2 按 AIMod 文件的具体差距

| 文件 | 当前状态 | 差距 |
|------|---------|------|
| `BotAIManager.java` | `parseCommand()` 一次 LLM 调用完成规划+执行 | 无 Plan/Execute 分离 |
| `BotAIManager.java` | `collectWorldContext()` 每次全量重建字符串 | 无增量 WorldState |
| `BotAIManager.java` | `incrementalReplan()` 硬编码上下文拼接 | 无 assemble/compact 机制 |
| `LLMService.java` | 单模型，重试逻辑，健康检查 | 无模型分层路由 |
| `PlanCache.java` | TF-IDF 相似度 + JSON 持久化 | 无 TTL 自动清理 (已有 MAX_AGE_DAYS=7 但未在 store 时检查) |
| `ChainManager.java` | 4 条链硬编码 `addChain()` | 无插件化注册 |
| `Task.java` | 内存对象，无持久化 | 服务器重启全部丢失 |
| `FakePlayer.java` | per-bot 无独立记忆 | 所有 bot 共享 PlanCache |
| `SequencePlanner.java` | 覆盖合成链 | 不覆盖 "收集多种资源" 等中等复杂度任务 |
| `DevLog.java` | 结构化日志 | 无性能指标聚合 |

---

## 5. 具体改进方案（按文件映射）

### 5.1 新建文件

#### `ai/memory/BotMemoryStore.java` — 分层记忆存储

```
职责: SQLite 持久化的三层记忆系统
- 工作记忆: 当前 tick 感知数据 (内存)
- 短期摘要: 最近 N 个 action 的一行摘要 (内存 + SQLite)
- 长期知识: 资源位置/探索区域/玩家偏好 (SQLite)

核心方法:
- ingest(WorldObservation obs) — 摄入一条观察
- assemble(int maxTokens) → String — 组装当前上下文
- compact(int targetTokens) → boolean — 压缩旧记忆
- rememberLocation(BlockPos, String type) — 记录资源位置
- queryLocations(String type, int radius) → List<BlockPos> — 查询附近资源
```

#### `ai/memory/WorldObservation.java` — 结构化世界观察

```
职责: 替代当前的字符串拼接，结构化记录每次 tick 的感知数据

字段:
- BlockPos botPosition
- float health, food
- List<NearbyBlock> blocksOfInterest  // 矿、威胁、可采集物
- List<NearbyEntity> threats           // 怪物、熔岩
- Map<Item, Integer> inventorySnapshot // 当前背包
- String biome
- long timestamp
```

#### `ai/task/TaskLedger.java` — 任务持久化

```
职责: SQLite 持久化所有任务状态

核心方法:
- saveTask(Task) — 保存任务
- loadTasks(FakePlayer) → List<Task> — 恢复 bot 的未完成任务
- updateTaskStatus(Task) — 更新状态
- getTaskHistory(int limit) → List<Task> — 查询历史
```

### 5.2 修改现有文件

#### `BotAIManager.java` — Plan/Execute 分离

```java
// 当前: parseCommand() 直接返回 Task（含完整 action 列表）
// 改进: 分离为 planPhase() + executePhase()

public Task planPhase(String command) {
    // 1. 检查 PlanCache
    // 2. 调用 LLM（仅规划，不执行）
    // 3. 缓存规划结果
    // 返回 Task（含 action 列表，状态=PLANNED）
}

public void executePhase(Task task) {
    // 从 PLANNED 状态开始执行
    // 失败时调用 replanPhase()（仅 replan，不重新规划全部）
}
```

#### `BotAIManager.java` — 上下文装配改造

```java
// 当前: collectWorldContext() 返回硬编码字符串
// 改进: 使用 BotMemoryStore.assemble()

private String assembleContext(Task task) {
    BotMemoryStore memory = bot.getMemoryStore();
    // 1. 短期摘要（最近 N 个 action 结果）
    // 2. 当前世界状态（位置、生命、背包）
    // 3. 相关长期知识（附近的已知资源位置）
    // 4. 任务上下文（当前 action、失败原因）
    return memory.assemble(maxContextTokens);
}
```

#### `LLMService.java` — 模型分层路由

```java
// 添加 model tier 配置
public enum ModelTier {
    CHEAP,   // 简单任务: deepseek-chat / haiku
    DEFAULT, // 常规任务: deepseek-v4-pro / sonnet
    PREMIUM  // 复杂推理: 保留
}

// 根据任务复杂度自动选择
public ModelTier selectTier(String command) {
    if (isSimpleGather(command)) return ModelTier.CHEAP;
    if (isComplexCraft(command)) return ModelTier.PREMIUM;
    return ModelTier.DEFAULT;
}
```

#### `ChainManager.java` — Slot 注册模式

```java
// 当前: addChain() 硬编码 4 条链
// 改进: Slot 注册模式

public class ChainManager {
    private final Map<String, BehaviorChain> slots = new LinkedHashMap<>();

    public void registerSlot(String slotName, BehaviorChain chain) {
        // 按优先级排序
        slots.put(slotName, chain);
    }
    // 内置 slot: danger, defense, food, unstuck
    // 第三方可注册自定义 slot
}
```

#### `PlanCache.java` — 扩展为通用记忆缓存

```java
// 当前: 仅缓存 command → actions 映射
// 改进: 同时缓存 WorldObservation 摘要 + 任务结果

public class PlanCache {
    // 新增: 存储每次任务执行后的关键观察
    public void storeObservation(String command, WorldObservationSummary summary);

    // 新增: 查询历史观察（"上次在哪里找到钻石？"）
    public Optional<WorldObservationSummary> findObservation(String query);
}
```

#### `ModConfig.java` — 新增配置项

```java
// 模型分层
public static final ConfigValue<String> CHEAP_MODEL_NAME;
public static final ConfigValue<String> PREMIUM_MODEL_NAME;

// 上下文管理
public static final ConfigValue<Integer> MAX_CONTEXT_TOKENS;     // 上下文硬上限
public static final ConfigValue<Integer> COMPACT_TRIGGER_TOKENS; // 触发压缩的阈值
public static final ConfigValue<Integer> MAX_WORKING_MEMORY;     // 工作记忆条数

// 任务持久化
public static final ConfigValue<Boolean> PERSIST_TASKS;          // 是否持久化任务

// 指标
public static final ConfigValue<Boolean> COLLECT_METRICS;        // 是否收集性能指标
```

---

## 6. 分阶段开发路线

### Phase 1: 上下文管理重构（5-7 天）

**目标**: 解决最大差距——上下文硬编码，引入 assemble/compact 机制。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 1.1 | 新建 `WorldObservation.java` — 结构化世界观察 | new | 小 |
| 1.2 | 新建 `BotMemoryStore.java` — SQLite 三层记忆 | new | 中 |
| 1.3 | 改造 `BotAIManager.collectWorldContext()` → `assembleContext()` | modify | 中 |
| 1.4 | 实现 `compact()` — 旧观察压缩为摘要 | new | 中 |
| 1.5 | 在 `ModConfig` 添加 `maxContextTokens`, `compactTriggerTokens` | modify | 小 |
| 1.6 | 在 `LLMService` 添加上下文长度检查和截断 | modify | 小 |

**验证**: bot 运行 30 分钟后 LLM 调用不因上下文过长失败。

### Phase 2: 任务持久化 + Plan/Execute 分离（4-6 天）

**目标**: 任务不因服务器重启丢失，规划与执行解耦。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 2.1 | 新建 `TaskLedger.java` — SQLite 任务账本 | new | 中 |
| 2.2 | 改造 `Task.java` — 支持序列化/反序列化 | modify | 小 |
| 2.3 | `BotAIManager` Plan/Execute 分离 | modify | 中 |
| 2.4 | 服务器启动时恢复未完成任务 | modify | 中 |
| 2.5 | `ModConfig` 添加 `persistTasks` | modify | 小 |
| 2.6 | 添加 `/aimod task history` 命令 | modify | 小 |

**验证**: 服务器重启后 bot 继续执行之前的任务。

### Phase 3: 模型分层 + 多 Bot 协调（3-5 天）

**目标**: 降低 API 成本，支持多 bot 并行协同。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 3.1 | `LLMService` 模型分层路由（CHEAP/DEFAULT/PREMIUM） | modify | 中 |
| 3.2 | 扩大 `SequencePlanner` 覆盖（"收集N种资源"等） | modify | 中 |
| 3.3 | `ChainManager` Slot 注册模式重构 | modify | 小 |
| 3.4 | Per-bot 上下文过滤（各 bot 只带自己的观察） | modify | 小 |
| 3.5 | `ModConfig` 添加 CHEAP_MODEL, PREMIUM_MODEL | modify | 小 |

**验证**: 简单采集任务不再调用 LLM，多 bot 不互相干扰。

### Phase 4: 长期记忆 + 知识持久化（4-6 天）

**目标**: Bot 拥有跨会话的"经验"，重启后不"失忆"。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 4.1 | 扩展 `BotMemoryStore` — 长期知识索引 | modify | 中 |
| 4.2 | 实现资源位置记忆（"上次在哪找到钻石"） | new | 中 |
| 4.3 | 实现已探索区域追踪（chunk 级） | new | 中 |
| 4.4 | 实现玩家偏好学习（"玩家喜欢用什么材料"） | new | 中 |
| 4.5 | 扩展 `PlanCache` → 通用任务记忆 | modify | 小 |
| 4.6 | 在 LLM prompt 中注入相关长期记忆 | modify | 小 |

**验证**: 第二次执行"帮我找钻石"时，bot 直接前往已知位置，不需要重新扫描。

### Phase 5: 可观测性 + 性能指标（2-3 天）

**目标**: 量化 bot 性能，追踪瓶颈。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 5.1 | 新建 `BotMetrics.java` — 结构化性能指标 | new | 小 |
| 5.2 | 在各 Action/Pathfinder 埋点 | modify | 小 |
| 5.3 | 添加 `/aimod metrics` 命令 | modify | 小 |
| 5.4 | 定期输出性能报告到日志 | modify | 小 |

**验证**: `/aimod metrics` 显示各 action 成功率、LLM 调用次数、token 消耗等。

### Phase 6: 插件化扩展接口（5-7 天，可选长期）

**目标**: 第三方可以为 bot 添加自定义行为。

| # | 任务 | 文件 | 工作量 |
|---|------|------|--------|
| 6.1 | 定义 BehaviorChain 注册 API | new | 中 |
| 6.2 | 定义 Action 注册 API | new | 中 |
| 6.3 | 配置文件驱动的 Chain/Action 加载 | new | 中 |
| 6.4 | 文档: 插件开发指南 | docs | 中 |

---

## 附录 A: 技术决策记录

### ADR-1: 为什么选择 SQLite 而非 JSON 做任务持久化

- **JSON (当前 PlanCache)**: 简单，但并发写不安全，查询需要全量加载
- **SQLite**: 支持 SQL 查询、并发安全、增量更新、可索引
- **决定**: 新模块（BotMemoryStore、TaskLedger）统一使用 SQLite；保留 PlanCache 的 JSON 格式用于跨版本兼容

### ADR-2: 为什么不实现完整的 MCP 协议

- MCP 是通用工具协议，需要 stdio/SSE 服务端
- AIMod 的 Action 系统已经实现了类似功能（Action schema + 参数 + 执行）
- **决定**: 不实现 MCP 协议本身，但借鉴其设计：每个 Action 类提供 `getToolSchema()` 方法返回 OpenAI Function Calling 兼容的 JSON Schema

### ADR-3: ContextEngine 实现策略

- OpenClaw 的 ContextEngine 是 TypeScript 接口，不能直接移植到 Java
- **决定**: 使用简化的 4 钩子模型（bootstrap → assemble → compact → afterTurn），不实现完整的 7 钩子。`prepareSubagentSpawn` 和 `onSubagentEnded` 在 Phase 3 多 bot 协调中按需添加

---

## 附录 B: SQLite Schema 设计

### bot_memory.sql

```sql
-- 长期知识: 资源位置
CREATE TABLE IF NOT EXISTS resource_locations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    block_type TEXT NOT NULL,       -- e.g. "minecraft:diamond_ore"
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    dimension TEXT NOT NULL,        -- e.g. "minecraft:overworld"
    discovered_at INTEGER NOT NULL, -- epoch ms
    last_seen_at INTEGER NOT NULL,
    mined BOOLEAN DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_resource_type ON resource_locations(block_type);
CREATE INDEX IF NOT EXISTS idx_resource_pos ON resource_locations(x, z, dimension);

-- 长期知识: 已探索区块
CREATE TABLE IF NOT EXISTS explored_chunks (
    chunk_x INTEGER NOT NULL,
    chunk_z INTEGER NOT NULL,
    dimension TEXT NOT NULL,
    explored_at INTEGER NOT NULL,
    PRIMARY KEY (chunk_x, chunk_z, dimension)
);

-- 长期知识: 玩家偏好
CREATE TABLE IF NOT EXISTS player_preferences (
    key TEXT PRIMARY KEY,           -- e.g. "preferred_fuel", "preferred_building_block"
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

-- 短期摘要
CREATE TABLE IF NOT EXISTS action_summaries (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    bot_uuid TEXT NOT NULL,
    summary TEXT NOT NULL,           -- e.g. "Collected 12 oak_logs near swamp_biome"
    action_count INTEGER,           -- number of actions this summary covers
    created_at INTEGER NOT NULL
);
```

### task_ledger.sql

```sql
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,             -- UUID
    bot_uuid TEXT NOT NULL,
    command TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'CREATED',
    actions_json TEXT,               -- JSON array of serialized actions
    current_action_index INTEGER DEFAULT 0,
    created_at INTEGER NOT NULL,
    completed_at INTEGER,
    failed_at INTEGER,
    retry_count INTEGER DEFAULT 0,
    parent_task_id TEXT,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_tasks_bot ON tasks(bot_uuid, status);
```

---

## 附录 C: 与现有 ANALYSIS_v2 的关联

本文档关注的是 **AI Agent 架构层面** 的改进（上下文管理、记忆系统、任务持久化），与 ANALYSIS_v2 关注的 **Minecraft 游戏机制层面** 改进（寻路性能、ChunkCache 压缩、WorldScanner 优化）是互补关系：

| 关注点 | ANALYSIS_v2 | 本文档 |
|--------|------------|--------|
| 寻路性能 | P0: BinaryHeapOpenSet, ChunkCache 压缩 | — |
| 游戏机制 | P1-P2: 跳跃对齐、搭桥、PlanCache TTL | — |
| LLM 上下文 | — | Phase 1: assemble/compact 机制 |
| 记忆持久化 | — | Phase 2 + 4: TaskLedger + BotMemoryStore |
| 模型成本 | — | Phase 3: 模型分层路由 |
| 可观测性 | — | Phase 5: BotMetrics |

两份文档应合并阅读以形成完整的改进视图。

---

*分析完成日期: 2026-05-26 | 版本: 1.0*
