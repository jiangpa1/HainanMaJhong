# 交接：仓库整理所需事实（2026-10-18）

> 给"整理 GitHub 仓库"那个会话读的。跨会话无法直接对话，所以所有事实落在这个文件里。
> 仓库：`F:\HainanMaJHong2`（分支 `release/v2-arch`，HEAD `c83d864 feat: add admission control, records optimization and durable room cleanup`）

## 1. 当前状态快照

| 项 | 值 |
|---|---|
| 未提交改动 | **仅 2 个文件**（`docs/performance/*`、`HainanMaJHong/src/*` 各 1）—— 整理时先 `git status` 看具体路径 |
| 线上运行包 | **`3cdf52f51f83f039`**（由当前工作区构建，92 项测试 / 0 失败 / 1 跳过） |
| 部署方式 | systemd `mahjong.service`，`ExecStart=.../HainanMaJHong-<sha>.jar`；**校验必须用** `sha256sum /proc/$(systemctl show mahjong -p MainPID --value)/fd/N` |
| 生产额度 | `RECORDS_RPS=800 / BURST=100`；IP 层 `480/60`；legacy IP `12/3` |
| 告警 | **18 条**已部署到云端 Prometheus（`/etc/prometheus/capacity-alerts.yml`） |
| outbox | `pending=0 dead=0`，表 `room_close_outbox` |

## 2. 建议写进 README / 文档的实测数字

### 容量边界阶梯（2026-10-18，80 桌 / 320 人 / 2 vCPU，每档 90 秒）

| 读速率 | CPU 均值 | 峰值 | >0.9 采样 | >0.95 采样 | 内存最低 |
|---|---|---|---|---|---|
| 80 GET/s | 0.436 | 0.603 | 0 | 0 | 460 MiB |
| 160 GET/s | 0.590 | 0.653 | 0 | 0 | 439 MiB |
| 240 GET/s | 0.772 | 1.000 | 1 | 1 | 442 MiB |
| 320 GET/s | 0.875 | 0.957 | 9 | 2 | 417 MiB |
| 400 GET/s | 0.953 | 0.984 | 14 | 11 | 388 MiB |

- 结论句：**读路径舒适区 ≤240 GET/s（CPU 0.77）；320 GET/s 逼近上限；400 GET/s 越过 95% 护栏（`cpu-over-95-percent-for-60-seconds`）。当前读路径容量边界约 320–400 GET/s。同期 429 仅 293 个（0.29%），说明卡的是 CPU 而非准入额度。**
- 口径提醒：各档房间数是逐渐建满的 → 该曲线是"整段演进的上界"，不是单点纯净值。

### 读路径分页（同轮）

- `list-page2-v2`（`/api/v2/records?pageNum=2&pageSize=10`）客户端 **P95 = 203 ms**（200:20,121 / 429:74）
- 同组服务端已接纳：`/api/v2/records` P95 **164 / P99 235 ms**
- 四条路由均进入统计：records 40,243 · overview 20,124 · rounds 20,120 · pending 20,195
- 结论句：**分页相对首页无额外延迟放大。**

### 其它已完成并有证据的项

- 320 人双轮各 30 分钟：**720,001 请求、0 失败、读 P95 23 ms**
- 准入额度对照（240/160/128）：拒绝 **0% / 13% / 27%**，CPU 与已接纳延迟基本不变
- Outbox 故障注入：停 Redis → `PENDING`（attempts 递增、`last_error=QueryTimeoutException`）→ 恢复后自动 `DONE`，无人工介入
- JMeter 同画像：80 线程 × 333 ms ≈ 219 GET/s，72,320 采样 0 错误、Avg 26 ms（`HainanMaJHong/ops/loadtest/jmeter/`）
- 部署校验：fd-SHA 与磁盘 SHA 一致；日志轮转 `/etc/logrotate.d/mahjong`（200M/天、保留 7 份、`copytruncate`）

## 3. 今天修掉的两个工具级 bug（值得单独写一段）

1. **直方图漏掉 `{id}` 路由**：`success-histogram.mjs` 的正则 `\{([^}]+)\}` 会在 route 里的第一个 `}` 截断 → 改为贪婪 `\{(.*)\}`；同时工具原先**从未保存** `coins` 消息里的 `sessionId` → 两条会话级路由从未被调用。两处都修了，现已四条路由全部进入统计。
2. **阶梯加压被误判中止**：`p2` 把 429 当"意外失败" → 阶梯加压出现预期削峰就会中止。已改为**存在 `rateSteps` 时 429 不触发窗口中止**。

## 4. 绝不能提交的内容

- `docs/performance/private/**`：测试账号文件（512 条明文账号密码）、token 文件、原始 run 数据、日志
- 任何 `.env` / `smtp-password.local` / SSH 私钥
- 建议在 `.gitignore` 里显式列一遍并在 README 注明"容量原始数据不公开，只公开方法论与结论"

## 5. 未完成事项（请在 README 的 Roadmap 或 ISSUE 里体现，不要写成已完成）

| 级别 | 事项 | 现状 |
|---|---|---|
| P0 | `round-drain-timeout` 偶发 | 埋点已上线（`hainanMahjong.diag`，默认 OFF）、分析脚本 `/tmp/analyze-engine-log.sh` 就绪；**多轮未复现**，未定位 |
| P1 | 告警送达专项 | `MahjongAdmissionRejecting` 4 封已确认收到；`MahjongCpuHigh` 未收到，尚未定因 |
| P1 | 纯净边界曲线 | 需每档「重启 → ramp 预热 → 稳态 90 秒」，未做 |
| P2 | MySQL 无法提交时的关闭补偿 | 仅有设计（本地 WAL 小账本 + 定时对账），未实现 |

## 6. 文档索引

- 容量与额度：`HainanMaJHong/docs/运行边界与线上额度_2026-10-16.md`
- 面板与告警：`HainanMaJHong/docs/容量监控面板说明.md`（**44 个面板对象含 6 个分组行，实际图表约 38 个**）
- Outbox：`HainanMaJHong/docs/房间收尾Outbox_2026-10-17.md`（顶部有"当前状态"锚点，以它为准）
- 工具偏差：`HainanMaJHong/docs/性能测试工具已知偏差.md`
- JMeter：`HainanMaJHong/ops/loadtest/jmeter/README.md`
- 日常启动：`HainanMaJHong/ops/dev-connect/start-work.ps1`（或桌面「启动开发环境.cmd」）
- 夜间/边界实测：`docs/performance/private/boundary-plan-20261016/`（私有，不入库）
