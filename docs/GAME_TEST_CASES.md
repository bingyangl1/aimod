# AI Mod 游戏内测试用例

## 环境准备
1. 部署最新 jar 到 mods/
2. 启动 NeoForge 1.21.1
3. 日志确认: `AI Mod initializing` + `5 Mixin loaded`

## 1. 基础

| # | 步骤 | 预期 |
|---|------|------|
| 1.1 | `/ai_bot spawn tester` | 出现名为 tester 的 bot |
| 1.2 | `/ai_bot status` | 显示全部 bot + chain + state |
| 1.3 | `/ai_bot test` | 18 项全部 PASS |
| 1.4 | `/ai_bot help` | 27 条命令列表 |
| 1.5 | `/ai_bot help goto` | goto 详细帮助+示例 |

## 2. 命令

| # | 步骤 | 预期 |
|---|------|------|
| 2.1 | spawn miner1 + lumberjack + status | 2 个 bot |
| 2.2 | select miner1 + status | 只显示 miner1 |
| 2.3 | stop lumberjack | miner1 不受影响 |
| 2.4 | remove lumberjack | 从世界移除 |
| 2.5 | Tab 补全 `stop <TAB>` | 弹出活跃 bot 名 |
| 2.6 | goto ~10.5 ~ ~-3.2 | 移动到正确位置 |
| 2.7 | inventory miner1 | 9x3 箱子界面 |
| 2.8 | toggle autoFish | 值切换 |
| 2.9 | showpath | 紫色粒子 |

## 3. 本地规划器

| # | 步骤 | 预期 |
|---|------|------|
| 3.1 | task 制作一把木头镐 | Gather→PlaceTable→Craft→BreakTable |
| 3.2 | task 挖5个铁矿石 | MineBlock 或 VeinMine |
| 3.3 | task 砍3棵树 | GatherResource + 连锁 |
| 3.4 | 箱子放3钻石→task制作钻石镐 | 用箱子钻石跳过挖矿 |
| 3.5 | 重复同样task | PlanCache 0ms 复用 |

## 4. 行为链

| # | 步骤 | 预期 |
|---|------|------|
| 4.1 | bot 饥饿<14 | 自动进食 FoodChain |
| 4.2 | 引僵尸 | 自动攻击 DefenseChain |
| 4.3 | 推到岩浆边 | 后退 DangerChain，不抖动 |
| 4.4 | 困在坑里 | WAIT→JUMP→SHIMMY→PILLAR |
| 4.5 | 任务完成 idle | 无 Unstuck 刷屏 |

## 5. 连锁采集

| # | 步骤 | 预期 |
|---|------|------|
| 5.1 | veinmine on + mine iron_ore 5 | BFS 挖相连矿石 |
| 5.2 | gather WOOD 8 | 整棵树连锁 |
| 5.3 | veinmine off + mine iron_ore 5 | 单块 |
| 5.4 | veinmine undo | 撤销恢复 |

## 6. LLM（需 API Key）

| # | 步骤 | 预期 |
|---|------|------|
| 6.1 | 无法执行的任务 | 不中止，LLM 请求下一步 |
| 6.2 | 连续失败5次 | FAILED |
| 6.3 | 日志无 count=0 | 解析正常 |

## 7. 持久化

| # | 步骤 | 预期 |
|---|------|------|
| 7.1 | save testbot 测试 | 已保存 |
| 7.2 | 重启→load testbot | 恢复位置+背包 |
| 7.3 | 保存后关服→重开 | 自动加载 |
