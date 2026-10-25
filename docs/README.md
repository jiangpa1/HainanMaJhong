# 文档索引

本仓库文档分三处，职责不同：

| 位置 | 内容 | 读者 |
|---|---|---|
| `README.md`（根） | 项目总览、快速开始、容量结论 | 所有人 |
| `docs/`（本目录） | **代码阅读指南** + 压测工具使用 | 要读代码的人 |
| `HainanMaJHong/docs/` | **全部压测与优化报告**（40+ 份） | 要查数据的人 |

## 一、代码阅读指南

按从浅到深的顺序读：

| 顺序 | 文件 | 讲什么 |
|---:|---|---|
| 1 | [CODE-READING-GUIDE.md](CODE-READING-GUIDE.md) | 分层结构、请求链路、关键类职责，通读代码的起点 |
| 2 | [MULTIPLAYER-READING-GUIDE.md](MULTIPLAYER-READING-GUIDE.md) | 多人对局协议：房间、座位、事件、推送的完整链路 |
| 3 | [ROOMMANAGER-READING-GUIDE.md](ROOMMANAGER-READING-GUIDE.md) | 房间管理器与牌局状态机，并发改造的核心区域 |
| 4 | [HTTP-LAYER-ALIGNMENT.md](HTTP-LAYER-ALIGNMENT.md) | HTTP 层规范：准入、限流、压缩、错误码、路由 |

> `README-legacy.md` 是旧版说明，保留仅为追溯，**不要作为入口**。

## 二、压测工具

目录：[`performance/`](performance/)

| 文件 | 用途 |
|---|---|
| `run-capacity.mjs` | 容量压测入口，读 JSON 配置 |
| `online-capacity-retest.mjs` | 真实玩家行为压测（注册、建房、打牌、读战绩） |
| `monitor.mjs` | 压测期间的指标采集 |
| `test-config.mjs` | 配置校验 |
| `compare-p2-reads.mjs` | v1 / v2 战绩接口对照（保留此对照脚本属有意为之）|
| `build-*.mjs` | 由 results.json 生成报告 |

**关键约束**：`private/accounts.json`、`private/*.json`、`private/*.log` 均被 `.gitignore` 排除，**不得提交**。

## 三、压测与优化报告（在 `HainanMaJHong/docs/`）

完整清单见 [`HainanMaJHong/docs/README.md`](../HainanMaJHong/docs/README.md)。按主题挑重点：

### 必读（结论性）

| 文件 | 内容 |
|---|---|
| [容量上限与优化验证_2026-10-25.md](../HainanMaJHong/docs/容量上限与优化验证_2026-10-25.md) | **最新**：960 人上限确定、JDK21 虚拟线程验证、30 分钟长跑、v1 接口移除 |
| [JDK21与虚拟线程迁移_2026-10-25.md](../HainanMaJHong/docs/JDK21与虚拟线程迁移_2026-10-25.md) | 迁移方案、pinning 审计、预期指标、回滚步骤 |
| [事件驱动状态机改造_2026-10-24.md](../HainanMaJHong/docs/事件驱动状态机改造_2026-10-24.md) | 阻塞模型 → 事件驱动的设计决策 |
| [八天并发优化方法论_2026-10-16.md](../HainanMaJHong/docs/八天并发优化方法论_2026-10-16.md) | 整体方法论：怎么定位、怎么验证 |
| [压测记录汇总.md](../HainanMaJHong/docs/压测记录汇总.md) | **全部 150+ 轮次的原始记录**（108 KB），查历史数据用 |

### 数据与工具

| 文件 | 内容 |
|---|---|
| [性能测试工具已知偏差.md](../HainanMaJHong/docs/性能测试工具已知偏差.md) | 压测工具本身的局限，读数据前必看 |
| [容量监控面板说明.md](../HainanMaJHong/docs/容量监控面板说明.md) | Grafana 面板各指标含义 |
| [运行边界与线上额度_2026-10-16.md](../HainanMaJHong/docs/运行边界与线上额度_2026-10-16.md) | 准入、限流、连接池的线上配置 |

### 专项

| 文件 | 内容 |
|---|---|
| [并发模式与失效清单.md](../HainanMaJHong/docs/并发模式与失效清单.md) | 已识别并修复的并发缺陷清单 |
| [战绩P2查询与缓存说明.md](../HainanMaJHong/docs/战绩P2查询与缓存说明.md) | 战绩读模型与缓存策略 |
| [房间收尾Outbox_2026-10-17.md](../HainanMaJHong/docs/房间收尾Outbox_2026-10-17.md) | 房间关闭的可靠投递 |
| [事故记录_2026-10-15_线上冻死与恢复.md](../HainanMaJHong/docs/事故记录_2026-10-15_线上冻死与恢复.md) | 唯一一次线上故障的完整复盘 |

## 四、阅读顺序建议

**要改代码** → 第一节 1→4，再读 `并发模式与失效清单`、`事件驱动状态机改造`

**要跑压测** → 第二节全部，再读 `性能测试工具已知偏差`、`容量监控面板说明`

**要查容量数据** → 第三节"必读"的前两份，再按需翻 `压测记录汇总`

**面试前回顾** → `容量上限与优化验证_2026-10-25`（结论）+ `八天并发优化方法论`（过程）
