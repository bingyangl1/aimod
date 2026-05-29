# AI Mod v2 — 深度功能分析

> 基于 `analysis/9-reference-projects` 分支，101 源文件，对比 10 参考项目。

---

## 一、总体评价

AI Mod 是**功能完整的 Minecraft AI 机器人原型**，核心亮点是 **LLM + 本地双层规划器** 与 **Baritone 风格 Movement 系统** 的结合。

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★☆ | 双层实体 + 双层规划器 |
| 移动系统 | ★★★★☆ | 8 Movement + A\*，接近 Baritone 80% |
| AI/规划 | ★★★★★ | LLM+本地+缓存，最强规划能力 |
| 生存系统 | ★★★☆☆ | 4 链覆盖主要场景 |
| 命令系统 | ★★★★★ | 27 条 + Tab + i18n |
| 代码质量 | ★★★☆☆ | 部分文件过长，重复逻辑 |
| 测试覆盖 | ★★★☆☆ | 129 单元 + 18 GameTest |

---

## 二、各模块对比

### 2.1 移动系统 (8 Movement)

| 特性 | Baritone | AI Mod |
|------|----------|--------|
| Movement 类型 | 8 | 8 ✅ |
| 状态机 | PREPPING→WAITING→RUNNING→SUCCESS | PENDING→PREPPING→RUNNING→COMPLETE ✅ |
| 输入方式 | `Input.JUMP` 模拟 | `setDeltaMovement` + `move()` |
| 跳跃对齐 | `flatDistToNext≤1.2` + `lateral≤0.1` | `dy>0.3 && dy≤1.5` ⚠️ |
| 搭桥 | SNEAK + CLICK_RIGHT | PlaceBlockAction 直接 setBlock ⚠️ |
| 路径预计算 | next PathExecutor | 无 ❌ |
| 路径拼接 | trySplice | 无 ❌ |
| 世界缓存 | CachedWorld (512×512) | ChunkCache (2048) ✅ |

**优点**: 8 Movement 完整对齐。**缺点**: 跳跃对齐不如 Baritone 精确，搭桥无 SNEAK。

### 2.2 行为链 (4 Chains)

| Chain | Player2NPC | AI Mod |
|-------|-----------|--------|
| 用户任务 | UserTaskChain(P50) | Task直接执行 ✅ |
| 怪物防御 | MobDefenseChain(P65-80) | DefenseChain(P70) ✅ 简化版 |
| 玩家防御 | PlayerDefenseChain(P55) | 无 ❌ |
| 自动进食 | FoodChain(P55) | FoodChain(P55) ✅ |
| 水桶防摔 | MLGBucketFallChain(P100) | 集成DangerChain ✅ |
| 卡住自救 | UnstuckChain(P65) | UnstuckChain(P50) ✅ +PILLAR |
| 世界生存 | WorldSurvivalChain(P60-100) | 集成DangerChain ✅ |
| 预装备 | PreEquipItemChain | 无 ⚠️ |

**优点**: 4 链覆盖主要场景，PILLAR 策略超越原始实现。**缺点**: 缺少玩家防御、链间协同。

### 2.3 规划器 (LLM + Local)

**核心优势**: 双层规划器业界独有。PlanCache 复用。MaterialTree 反向链接。

**缺点**: 增量规划仅失败时触发。`findBlockForItem` 映射不完整。

### 2.4 命令系统 (27 条)

超越 SiliconeDolls（15 条）。Tab 补全、i18n、撤销、路径可视化均超越参考。

**缺点**: `BotCommand.java` 1077 行过长。

---

## 三、优缺点

### 核心优势
1. 双层规划器（业界独有）
2. 完整 Movement（8 种类型对齐 Baritone）
3. 行为链（4 链 + PILLAR）
4. 命令系统（27 条 + 双语）
5. 工具链（连锁/撤销/路径/测试）

### 主要不足
1. 跳跃对齐不如 Baritone 精确
2. 搭桥无 SNEAK 保护
3. 链系统缺 PlayerDefense
4. 增量规划非真正的"每步增量"
5. BotCommand 文件过长

---

## 四、改进优先级

| 优先级 | 改进 | 影响 |
|--------|------|------|
| P0 | 移动跳跃对齐检测 | bot 不原地跳 |
| P1 | GatherResource 集成 ToolSet | 采集效率 |
| P1 | BotCommand 拆分 | 可维护性 |
| P2 | PlayerDefenseChain | 完整防御 |
| P2 | PlanCache 相似度优化 | 缓存命中率 |
| P3 | 路径预计算 | 长距离导航 |
| P3 | 皮肤系统 | 视觉效果 |

---

## 五、参考项目相似度

| 参考项目 | 参考深度 | 实现完整度 |
|---------|---------|-----------|
| Baritone | ★★★★★ | ★★★★☆ (80%) |
| Player2NPC | ★★★★☆ | ★★★☆☆ (4/9链) |
| SiliconeDolls | ★★★★☆ | ★★★★☆ |
| AI-Player | ★★★☆☆ | ★★☆☆☆ |
| Meteor | ★★★☆☆ | ★★★☆☆ |
| EMI/JEI | ★★★★☆ | ★★★★☆ |

---

*分析日期: 2026-05-25 | 版本: 1.0.43-r54*

---

## P2 实施记录 (2026-05-29)

### p2a: 地下方块不可达 + LLM JSON 格式健壮性

**地下方块不可达修复:**
- `GatherResourceAction.findBestStandPos()` dy 范围: -2~+3 → -10~+3
- `findAdjacentStandPos()` 增加向下搜索 1-3 格
- 可达检查: dy >= -1.5 → -3.0 (允许站在上方挖矿)
- `MAX_FALL_BLOCKS`: 4 → 10 (支持更深的地下寻路)

**LLM JSON 格式健壮性:**
- 自动修正 shorthand 格式: `{"mine": "iron_ore"}` → `{"type": "mine", "block_type": "iron_ore"}`
- 支持所有已知 action type 的自动识别和修正
- 日志记录修正行为 (`PLAN_ACTION_FIX_FORMAT`)

### p2b: BotAIManager 职责分离

BotAIManager.java (800行) 拆分为 4 个文件:

| 类 | 行数 | 职责 |
|----|------|------|
| `TaskPlanner.java` | ~350 | LLM 调用、响应解析、PlanCache 交互、JSON 辅助方法 |
| `TaskExecutor.java` | ~120 | 任务执行循环、状态转换、内存压缩 |
| `TaskReplanner.java` | ~180 | 增量重规划、缺口重规划、failedAttempts 管理 |
| `BotAIManager.java` | ~100 | 协调器，保持原有公共 API |

### p2c: BotCommand 拆分

BotCommand.java (1257行) 拆分为 8 个文件:

| 类 | 职责 |
|----|------|
| `SubCommand.java` | 子命令注册接口 |
| `BotCommandTask.java` | task, task_all, stop, cancel, pause, resume, status |
| `BotCommandAction.java` | goto, mine, vein, follow, gather, craft, say, give, equip |
| `BotCommandAdmin.java` | spawn, select, remove, save, load, list, delete, inventory |
| `BotCommandConfig.java` | toggle, config, veinmine, showpath |
| `BotCommandTestCmd.java` | test (25 子系统测试) |
| `BotCommandHelp.java` | help |
| `BotCommand.java` | ~150 行协调器 + 共享辅助方法 |

*更新日期: 2026-05-29 | 版本: 1.0.63-p2c*

---

## P3 实施记录 (2026-05-29)

### p3a: 测试覆盖扩展

新增测试文件:

| 文件 | 覆盖目标 |
|------|----------|
| `ActionFactoryTest.java` | Action JSON 解析 (move_to, mine, craft, equip, say, aliases, position flattening) |
| `NameTagFormatterTest.java` | BotAIStateMachine 状态转换、计数器、重置 |
| `InventoryUtilsTest.java` | FindItemResult 属性 (found, isHotbar, slot, count) |

测试总计: 18 个文件, 150+ 测试方法。

*更新日期: 2026-05-29 | 版本: 1.0.64-p3a*

### p3b: 多模型路由 (CHEAP/PREMIUM)

**设计方案:**
- 新增 `CHEAP_MODEL_NAME` 配置 — 用于 incremental replan 等简单任务
- `PREMIUM_MODEL_NAME` 使用原有的 `MODEL_NAME` — 用于完整任务规划

**修改文件:**
| 文件 | 修改内容 |
|------|----------|
| `ModConfig.java` | 新增 `CHEAP_MODEL_NAME` 配置项和 getter/setter |
| `LLMService.java` | 新增 `sendPromptWithModel()` 方法支持模型名覆盖 |
| `TaskReplanner.java` | incremental replan 使用 cheap model |
| `BotCommandConfig.java` | 配置命令支持 cheapModelName 显示和设置 |

**路由逻辑:**
- `TaskPlanner.parseCommand()` → 使用 PREMIUM 模型 (MODEL_NAME)
- `TaskReplanner.incrementalReplan()` → 使用 CHEAP 模型 (CHEAP_MODEL_NAME)
- 默认 CHEAP_MODEL_NAME 为空，回退到 MODEL_NAME

*更新日期: 2026-05-30 | 版本: 1.0.66-p3b*

### p3c: BotMetrics 可观测性

**设计方案:**

新建 `BotMetrics.java` — 线程安全的指标收集器：

| 指标类型 | 计数器 |
|----------|--------|
| LLM 调用 | llmCalls, llmSuccesses, llmFailures, llmTotalMs, llmMaxMs |
| 任务 | tasksStarted, tasksCompleted, tasksFailed |
| 动作 | actionsExecuted, actionsSucceeded, actionsFailed |
| 重规划 | replansTriggered, replansSucceeded |

**集成点:**
| 文件 | 记录内容 |
|------|----------|
| `TaskPlanner.java` | LLM 调用成功/失败 + 耗时 |
| `TaskExecutor.java` | 动作成功/失败、任务完成 |
| `TaskReplanner.java` | 重规划触发/成功、LLM 调用 |

**命令:**
- `/ai_bot metrics` — 显示当前 bot 的指标摘要
- `/ai_bot metrics <name>` — 显示指定 bot 的指标

*更新日期: 2026-05-30 | 版本: 1.0.67-p3c*
