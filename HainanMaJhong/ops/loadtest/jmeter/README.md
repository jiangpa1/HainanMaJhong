# JMeter 容量读压测（HTTP 读路径）

本目录是海南麻将项目的 **JMeter 测试计划**，用于对 **HTTP 读路径**做容量压测与独立复核。

## 定位（务必先读）

| 由谁负责 | 内容 |
|---|---|
| **JMeter（本计划）** | 登录节拍（尊重 `LOGIN_IP_RPS=1`）、`/api/v2/records` 分页、`/api/room/pending` 等 **HTTP 读接口**；输出吞吐、延迟、**429 比例** |
| **自研 Node 工具**（`docs/performance/online-capacity-retest.mjs`） | **WebSocket 对局循环**（4 个座位摸牌/出牌/结算）、逐房间手数记账、广播序号连续性、收尾核对 |

> 为什么不把对局循环放进 JMeter：JMeter 原生不支持 WebSocket（需插件），且要按座位重建整套牌局状态机 —— 那等于重写一个专用工具。**两个工具分工，并互相印证 HTTP 侧数字**，比单用任何一个更可信。

## 前置

1. **JMeter 5.6.3**（本机：`E:\jmeter\apache-jmeter-5.6.3`），Java 17 可用。
2. **账号清单**：由私有测试账号文件生成 CSV（账号是测试资产，**不入库**）：
   ```powershell
   HainanMaJHong\ops\loadtest\jmeter\make-accounts-csv.cmd
   # 默认输出 docs\performance\private\jmeter-accounts.csv（320 行 + 表头）
   ```
3. 网络可直连 `jiangpahnmj.cn`（JMeter 走公网，不需要 SSH 隧道）。

## 运行

**A. 演示用（GUI，看聚合报告）**

```powershell
& 'E:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -t HainanMaJHong\ops\loadtest\jmeter\capacity-read.jmx
```

**B. 正式跑（CLI，无界面，推荐）**

```powershell
& 'E:\jmeter\apache-jmeter-5.6.3\bin\jmeter.bat' -n `
  -t HainanMaJHong\ops\loadtest\jmeter\capacity-read.jmx `
  -l docs\performance\private\jmeter-320read.jtl `
  -e -o docs\performance\private\jmeter-320read-report `
  -JLOGIN_COUNT=320 `
  -JTABLES=80 `
  -JLOOPS=900 `
  -JTHINK_MS=333
```

上文参数的含义：80 个线程（每线程代表一张桌的读取节奏）、每线程 900 次迭代、每次 333 ms → 约 **240 GET/s 总计、持续约 300 秒**，与自研工具"3 请求/秒/桌"档位对齐。

### 参数

| 参数 | 默认 | 说明 |
|---|---|---|
| `PROTOCOL` / `DOMAIN` / `PORT` | `http` / `jiangpahnmj.cn` / `80` | 目标 |
| `LOGIN_COUNT` | 320 | 登录准备阶段的数量（每 1.1 秒一个，受 IP 限流约束） |
| `LOGIN_DELAY_MS` | 1100 | 登录节拍（`LOGIN_IP_RPS=1` 时不要低于 1000） |
| `TABLES` | 80 | 读线程数 |
| `LOOPS` | 900 | 每线程迭代次数 |
| `THINK_MS` | 333 | 每线程两次读之间的间隔 |
| `ACCOUNTS` / `TOKENS` | `private\jmeter-accounts.csv` / `private\jmeter-tokens.csv` | 账号与登录令牌（均由本计划生成/消费） |

## 结果判读（关键）

1. **不要把 JMeter 自己的 `elapsed` 直接当作服务端延迟** —— 它包含网络与（若开启）重试等待。对比容量结论请用**服务端口径**：
   ```bash
   curl -s http://127.0.0.1:9091/actuator/prometheus | grep 'outcome="success"'   # 需先开 9091 隧道
   ```
2. **429 是"按设计拒绝"，不是失败**：在 JTL 里按响应码拆分，而不是看 `Err%`：
   ```powershell
   Import-Csv docs\performance\private\jmeter-320read.jtl |
     Group-Object responseCode | Select-Object Name, Count
   ```
   与自研工具同期数据对照：`429 比例`、`服务端已接纳 P95`、`CPU 均值`。
3. 参考基线（同日、同画像、自研工具测得）：`240 GET/s` 报价下拒绝率约 0%–13%、服务端已接纳 P95 约 **19–24 ms**、CPU 均值约 **0.40–0.45**（2 vCPU）。

## 实测结果（2026-10-17，生产 2 vCPU，额度 800/100）

| 指标 | 数值 |
|---|---|
| 登录准备 | 320 个账号全部通过，按 1.1 秒节拍 ≈6 分钟（P95 123 ms，含密码哈希） |
| **业务断言** | `$.code == 200`，**72,320 / 72,320 通过、0 失败** |
| 稳态读速率 | **219.4 GET/s**（80 线程 × 333 ms × 900 迭代的实际达成值，窗口 **328 s**） |
| 拒绝 / 429 | **0**（额度 800/100 下不应出现） |
| 客户端读延迟 | P50 **25 ms**、**P95 34–37 ms**、P99 ≤ 52 ms（三条路由一致） |
| 服务端同期 | `success` 计数：records 48,066 / pending 23,971 / login 328；与自研工具同画像（服务端已接纳 P95 24 ms、拒绝 0%、CPU 均值 0.449）一致 ✓ |

> ⚠️ 历史记录：本计划**第一次跑（无业务断言）时，71,999 个读请求全部是"HTTP 200 + 业务码非 200"**（登录缺 `Content-Type: application/json` → 无 token → 401），客户端只看 HTTP 码会被**完全骗过**。加上 `$.code == 200` 断言后才暴露并修复。**JMeter 压测必须带业务断言，HTTP 200 不能当成功。**

复现方式见上文命令；JTL 用 `Group-Object responseCode` 拆 200/429（429 是准入保护按设计拒绝，不算失败）。

## 已知边界

- 本计划**不含** `sessionId` 级战绩接口（`/api/v2/records/{id}/overview|rounds`），因为这些 `sessionId` 来自 WebSocket 对局流程，JMeter 侧无法在无对局的情况下取得；这部分由自研工具覆盖。
- 登录阶段是**串行**的（受 `LOGIN_IP_RPS=1` 约束），320 个账号约需 6 分钟；正式压测请把这段视为准备耗时，不要计入稳态窗口。
