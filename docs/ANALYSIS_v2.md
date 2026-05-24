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
