# AI-Player (shasankp000) 项目分析报告

> 参考项目：https://github.com/shasankp000/AI-Player (Fabric 1.21.1, 124 stars)
> 分析日期：2026-05-23
> 分析目的：提取可借鉴的架构模式和代码逻辑

---

## 项目概况

| 项目 | AI-Player | AIMod (我们) |
|------|-----------|-------------|
| 加载器 | Fabric | NeoForge |
| MC版本 | 1.21.1 | 1.21.1 |
| Stars | 124 | - |
| 进度 | 75.5% | ~70% |
| AI方案 | NLP管线 + LLM + Q-Learning | 直接LLM规划 + 动作系统 |
| 假人实现 | Carpet mod FakeClientConnection | 自实现 FakePlayerNetHandler |
| LLM支持 | Ollama/OpenAI/Claude/Gemini/Grok | OpenAI兼容API |

---

## 核心架构差异

### AI-Player 的 AI 管线

用户消息 -> NLP意图分类(BERT+CART+LIDSNet) -> DecisionResolver(LLM仲裁)
  - GENERAL_CONVERSATION -> RAG对话(LLM + 记忆检索)
  - ASK_INFORMATION -> Web搜索 + LLM回答
  - REQUEST_ACTION -> 动作执行(Q-Learning/命令)

### AIMod 的 AI 管线

用户消息 -> LLM(含世界上下文) -> 结构化响应 -> 动作序列 -> 逐步执行

**关键区别**：AI-Player 用多层本地模型做意图分类，我们直接用 LLM 端到端处理。各有利弊。

---

## 可借鉴的模式（按优先级排序）

### 高价值

#### 1. GameProfile 持久化
文件：Entity/createFakePlayer.java

AI-Player 保存假人的 GameProfile（UUID）到配置文件，重启后复用同一身份。
每次 spawn 时检查是否有已保存的 UUID，有则复用，无则新建并保存。

我们目前：每次 spawn 生成新 UUID，重启后假人身份丢失。
建议：在 FakePlayerManager 中添加 GameProfile 持久化，保存到 config/aimod/bots.json。

#### 2. 危险区域检测
文件：DangerZoneDetector/ (LavaDetector, CliffDetector, DangerZoneDetector)

- LavaDetector：扫描附近岩浆方块，计算最近距离
- CliffDetector：前方悬崖检测（向下扫描是否有实心方块）
- DangerZoneDetector：综合危险评分

我们目前：路径规划不考虑危险。
建议：集成到 PathExecutor，在路径执行中自动避开岩浆和悬崖。可在 MoveCost 中增加危险惩罚。

#### 3. 战斗 AI 系统
文件：Entity/AutoFaceEntity.java, PlayerUtils/CombatStrategyUtils.java

- 自动面向最近敌对实体
- 投射物防御（盾牌格挡 + 闪避）
- 预测性威胁检测（检测拉弓动作）
- 盾牌持续格挡状态管理
- 威胁等级评估（MobThreatEvaluator）
- 玩家反击追踪（PlayerRetaliationTracker）

我们目前：AttackAction 只是简单移动+攻击。
建议：
- 为 AttackAction 添加威胁评估
- 新增 DefendAction 处理投射物防御
- 在 BotAIManager 的 tick 中添加自动威胁响应

#### 4. 工具智能选择
文件：PlayerUtils/ToolSelector.java, PlayerUtils/MiningTool.java

根据方块类型自动选择最佳工具（考虑附魔、耐久等）。

我们目前：ToolSet 有基础实现，但不够完善。
建议：扩展 ToolSet，添加耐久度检查和附魔优先级。

### 中价值

#### 5. 聊天消息分割
文件：ChatUtils/ChatUtils.java

长消息自动分割为多条（每条最长100字符），带打字延迟模拟。
按句子分割 -> 按词分割 -> 控制长度。

建议：在 SayAction 中添加消息分割逻辑，避免超长消息被截断。

#### 6. 记忆/RAG 系统
文件：ChatUtils/Helper/RAG2.java, Database/SQLiteDB.java

- SQLite + 向量嵌入存储对话记忆
- BERT 嵌入做语义检索
- 新查询时检索相关记忆作为上下文

我们目前：无记忆系统，每次对话独立。
建议（长期）：可先用简单的关键词匹配记忆，后期再升级为向量检索。

#### 7. 死亡/重生处理
文件：Entity/createFakePlayer.java, AIPlayer.java

- 死亡时保存状态 + 触发学习
- 重生时更新状态

我们目前：未处理假人死亡。
建议：在 FakePlayer 中监听死亡事件，自动重生并恢复任务。

#### 8. tick() 优化
文件：Entity/createFakePlayer.java

每 10 tick 才同步一次位置和区块，减少性能开销。
```java
if (this.getServer().getTicks() % 10 == 0) {
    this.networkHandler.syncWithPlayerPosition();
    this.getServerWorld().getChunkManager().updatePosition(this);
}
```

### 低价值（了解即可）

#### 9. NLP 意图分类管线
文件：ChatUtils/NLPProcessor.java (40KB)

三模型投票 + LLM仲裁：BERT、CART决策树、LIDSNet。
需要下载预训练模型（约200MB），依赖 DJL 框架。

评估：对于我们"LLM直接规划"的架构，本地NLP管线是多余的。LLM本身就能理解意图。
但"意图分类"的概念可以简化应用：用一个轻量prompt先判断是任务还是对话。

#### 10. Q-Learning 强化学习
文件：GameAI/RLAgent.java, Database/QTable.java

Q表存储状态-动作值，从死亡事件中学习。

评估：与我们的LLM规划架构冲突。LLM已经能做高层决策，RL更适合底层反应式行为（如战斗闪避）。

#### 11. 多LLM Provider 抽象
文件：ServiceLLMClients/ (LLMClient interface + 多实现)

评估：我们已有 OpenAI 兼容 API，覆盖了大部分 provider。可考虑添加 Ollama 本地模型支持。

---

## 我们已有的优势

| 能力 | 我们 | AI-Player |
|------|------|-----------|
| 任务规划 | LLM端到端规划动作序列 | 需要多步NLP+命令 |
| 动作系统 | 14种高层动作 | 基础命令式 |
| 路径规划 | Baritone风格A* | 基础寻路 |
| 世界上下文 | 丰富上下文发送给LLM | 较少上下文 |
| 合成系统 | 接入原版配方 | 无 |
| 流式输出 | 支持 | 不支持 |

---

## 推荐实施计划

### 短期（1-2天）
1. GameProfile 持久化 - 小改动，高价值
2. 死亡/重生处理 - 防止假人死亡后卡住
3. 危险区域检测 - 集成到路径规划

### 中期（3-5天）
4. 战斗 AI 增强 - 威胁评估 + 投射物防御
5. 工具智能选择 - 扩展 ToolSet
6. 消息分割 - 改进 SayAction

### 长期
7. 记忆系统 - 简单版对话记忆
8. Ollama 本地模型支持 - 无需API Key