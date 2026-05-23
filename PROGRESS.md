# AI Mod 项目进度文档

## 项目概况

**目标**：实现类似"Minecraft 版 Claude Code"的模组，通过自然语言下达任务，大模型解析、规划任务，假人玩家执行、实现。

**版本**：Minecraft 1.21.1 + NeoForge 21.1.176

**参考资源**：
- SiliconeDolls（假人实现参考）
- Baritone（路径规划/挖矿）
- RollingGate（配置风格）
- AI-Player（AI 机器人参考）
- Meteor Client（外挂端模块参考）
- EMI（合成配方索引参考）

---

## 当前进度总结

| 模块 | 状态 | 完成度 | 说明 |
|------|------|--------|------|
| 模组基础框架 | 完成 | 100% | 注册、配置、命令系统 |
| 实体系统 | 完成 | 95% | AIBotEntity(Mob) + 懒加载 FakePlayer |
| FakePlayer 系统 | 完成 | 97% | 持久化 GameProfile + 自动重生 + 无敌 |
| LLM 集成 | 功能完整 | 95% | OpenAI 兼容 API，流式支持，HTTP/2 共享实例 |
| 动作系统 | 大部分完成 | 85% | 14 种动作，含挖矿/采集/交互/装备 |
| 世界感知 | 完成 | 80% | WorldScanner 支持方块/实体/玩家扫描 |
| 合成系统 | 重构完成 | 80% | RecipeIndex O(1) 查找 + Tag 感知 + 催化物区分 |
| 任务反馈 | 完成 | 90% | 向玩家报告执行状态，支持 i18n |
| 研发日志 | 完成 | 100% | DevLog 统一日志 |
| 路径寻找 | 重构完成 | 70% | Baritone 风格 A* 寻路 + Goal 系统 |
| 命令系统 | 完成 | 90% | 16 个子命令 + 直接命令 + i18n |
| 测试 | 完成 | 40% | 10 个测试文件，87 个单元测试全部通过 |
| 持久化 | 部分完成 | 30% | GameProfile 已持久化，背包/任务未保存 |

**总体完成度：约 75%**

---

## 构建状态

**BUILD SUCCESSFUL**（.\gradlew.bat build --init-script init-local.gradle）

最后验证：2026-05-23

### 构建环境备注
- 必须使用 --init-script init-local.gradle（本地依赖仓库，SSL 限制）
- Java 21 required（gradle.properties 中 systemProp.java.home 配置 JDK 路径）
- Gradle daemon 已禁用
- 测试依赖：JUnit 5.10.2 + Mockito 5.11.0

---

## 架构说明

### 混合架构：AIBotEntity + FakePlayer

当前架构采用混合模式：

- **AIBotEntity**（extends Mob）：世界中可见的实体，渲染为玩家外观（Steve 皮肤），持有 BotAIManager 和 inventory，是 AI 的实际控制者
- **FakePlayer**（extends ServerPlayer）：由 AIBotEntity 懒加载创建，通过 placeNewPlayer() 注册到服务器玩家列表，提供完整玩家能力（背包、合成、交互、破坏方块等）
- AIBotEntity 每 tick 同步位置到 FakePlayer
- FakePlayer 无敌（hurt() 返回 false），死亡后自动重生（40 tick 延迟）

**设计理由**：
- AIBotEntity 作为 Mob 实体，可被玩家看见、交互、追踪，使用原版渲染系统
- FakePlayer 提供完整玩家 API，避免重复实现背包/合成/交互等功能
- 参考 SiliconeDolls 的 FakePlayer 创建模式

---

## 最新更新 (2026-05-23)

### 混合架构 + Baritone 路径系统

**架构说明**：
- AIBotEntity extends Mob — 世界中可见的实体
- FakePlayer extends ServerPlayer — 懒加载，注册到服务器玩家列表
- 移除旧版 AIBotRenderer/AIBotModel，恢复为 HumanoidMobRenderer

**新增 Baritone 风格路径系统**：
- Pathfinder — A* 核心算法（HashMap + PriorityQueue）
- PathNode — 路径节点（含 hash 和 heuristic）
- PathResult — 路径结果
- PathExecutor — 路径执行器（逐步移动 + 卡住检测）
- BinaryHeapOpenSet — 二叉堆优化 A* 开放列表
- MovementHelper — 移动辅助（方块可通行性检测）
- MoveCost — 移动代价常量（多格跌落、穿墙检测、半砖/楼梯）
- ToolSet — 工具选择优化（挖掘速度计算）
- Goal 系统 — GoalBlock, GoalXZ, GoalYLevel, GoalComposite

**新增命令系统**：
- DirectCommandHandler — 绕过 LLM 直接创建任务
- 16 个子命令（spawn, task, task_all, status, stop, cancel, pause, resume, goto, mine, follow, gather, craft, say, give, equip, help）
- 所有面向用户字符串使用 Component.translatable() 支持 i18n

**新增 LLM 解析器**：
- LLMResponseParser — 独立的响应解析器（宽松解析 + 正则回退）
- ActionDescriptor — 动作描述符，封装 JSON 参数

**新增配方索引**：
- RecipeIndex — O(1) 配方查找，支持 Tag 匹配、催化剂/消耗物区分（参考 EMI）

**新增 Profile 持久化**：
- BotProfileStore — GameProfile 持久化（name 到 UUID，存储到 config/aimod/bots.json）（参考 AI-Player）

**构建修复**：
- isSolidRender() 1.21.1 API 适配（需要 BlockGetter, BlockPos）
- EnchantmentHelper.getItemEnchantmentLevel 参数顺序修复
- UTF-8 编码问题修复
- BlockPos double-to-int 类型转换修复

---

### 移动系统与寻路全面升级

**问题根因**：
1. FakePlayer.tick() 调用 super.tick() 执行原版 travel()（重力+物理），然后 AI tick 又调用 navigateTo() 导致双重移动抖动
2. MoveCost 只支持正负 1 格移动，不支持多格跌落；对角移动无穿墙检测；半砖不可行走
3. 假人遇到障碍物时无法绕路或破坏，一直卡住

**修复内容**：

| 文件 | 变更 |
|------|------|
| FakePlayer.java | 重写 travel() — AI 活动时仅应用重力，跳过原版输入移动 |
| Action.java | 重写 navigateTo() — 正确处理重力、step-up 跳跃、朝向同步 |
| FollowAction.java | isComplete() 改用 navigateTo() |
| AttackAction.java | moveToward() 改用 navigateTo() |
| MoveCost.java | 完全重写 — 多格跌落、对角穿墙检测、canWalkOn 支持半砖/楼梯 |
| Pathfinder.java | 新增 expandMultiBlockFalls() — 每个方向额外尝试 2-4 格跌落 |
| GatherResourceAction.java | 新增 tryBreakObstacle() — 卡住 2 秒后破坏障碍物 |

---

### P0 开发完成

**配方系统重构**（参考 EMI/JEI）：
- RecipeIndex.java — byOutput/byInput 索引，O(1) 查找
- CraftAction.java — 使用 RecipeIndex + Tag 感知 + 催化物区分
- 支持 Tag 匹配（如 #minecraft:planks 匹配任意木板）
- 智能配方选择：根据库存选择最佳配方

**FakePlayer 持久化**（参考 AI-Player）：
- BotProfileStore.java — 保存 name 到 UUID 到 config/aimod/bots.json
- FakePlayerManager.java — 使用持久化 UUID
- FakePlayer.java — 接受 persistentUUID 参数 + 死亡后自动重生

---

### LLM 解析器提取 + 单元测试扩展

**LLMResponseParser 提取**：
- 从 LLMService 提取 JSON 解析逻辑到独立的 LLMResponseParser
- 新增 ActionDescriptor 不可变动作描述符
- 所有解析方法无 Minecraft 运行时依赖，完全可单元测试

**测试扩展**：
- 新增 LLMResponseParserTest.java（25 个测试，5 个嵌套类）
- 新增 LLMResponseParserRobustnessTest.java
- 新增 LLMServiceImprovementTest.java
- 修复 MoveCostTest 适配 4 元素 OFFSETS 格式
- 总计 87 个单元测试全部通过

---

## 寻路系统详情

### A* 寻路算法（Pathfinder）
- HashMap 存储 PathNode，O(1) 查找
- PriorityQueue（二叉堆）作为开放列表
- 超时搜索（默认 2 秒），返回最佳路径
- 欧几里得距离启发函数
- 16 种移动类型：4 基本方向 + 4 对角 + 4 上升 + 4 下降
- 多格跌落支持（1-4 格）
- 对角穿墙检测

### 移动代价（MoveCost）
- 基础移动代价：1.0（基本方向）、1.414（对角）
- 跌落代价：FALL_N_BLOCK(n) — 2-4 格跌落
- 跌落伤害阈值：3 格（超过 3 格计算伤害代价）
- 岩浆/虚空：VOID_COST（不可通行）
- 半砖/楼梯：canWalkOn 支持
- 破坏方块代价：基于硬度

### Goal 系统
- GoalBlock — 到达指定方块坐标
- GoalXZ — 到达 XZ 平面
- GoalYLevel — 到达指定 Y 高度
- GoalComposite — 组合多个目标

---

## 已知问题

- FakeClientConnection 使用反射设置 EmbeddedChannel，NeoForge patched 字段名可能不同
- 无持久化（机器人背包和任务不会在服务器重启后保存，GameProfile 已持久化）
- WorldScanner 暴力扫描方块区域（O(n^3)）
- AIBotEntity 和 FakePlayer 之间的位置同步依赖 tick 顺序
- GatherResourceAction 搭柱功能在复杂地形中可能失败

---

## 测试状态

- **单元测试**：10 个文件，87 个测试，全部通过
- **测试框架**：JUnit 5.10.2 + Mockito 5.11.0
- **覆盖模块**：Task、LLMResponse、LLMResponseParser、BinaryHeapOpenSet、MoveCost、PathNode、Goals、DevLog
- **无集成测试**或游戏测试

---

## 构建产物

- 构建产物：build/libs/aimod-1.0.0.jar
- 构建状态：BUILD SUCCESSFUL