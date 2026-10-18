# 2026-10-12 线上 192 人档位复测（原始结果目录）

目标：`http://jiangpahnmj.cn`（39.107.247.119），单实例、2 vCPU / 1608 MiB。
发压机：本机 Windows + Node v24.17.0；服务端指标经 SSH 隧道读 `127.0.0.1:9091/actuator/prometheus`。
口径：`recordsVersion=v2`、`encoding=gzip`、`acceptanceProfile=p2`、每桌 2.5 次 GET/秒、48 桌 = 192 人、120 GET/秒。

**该目录的 `results.json` 含测试账号标识（256 个专用账号），不要外发、不要入库。** 对外只用 `run-summary.csv` 和仓库里的复测报告。

## 轮次

| 目录 | 桌/人 | 计划秒 | 实际 | 结果 | 停止原因 |
|---|---:|---:|---:|---|---|
| `smoke-1-table` | 1/4 | 30 | 34.0 | 未通过 | `ws-message-timeout`（HTTP 窗口跑满，收尾等待超时） |
| `preflight-v2-2tables` | 2/8 | 30 | 30.0 | 通过 | — |
| `warmup-16tables` | 16/64 | 60 | 60.0 | 通过 | — |
| `experiment-16tables-db` | 16/64 | 60 | 60.0 | 通过 | —（用于前后对比 `performance_schema` 摘要做数据库归因） |
| `formal-v2-192` | 48/192 | 300 | 16.3 | 未通过 | `cpu-over-90-percent-for-three-samples`（新读路径关闭） |
| `formal-v2-192-attempt2` | 48/192 | 300 | 74.4 | 未通过 | `cpu-over-90-percent-for-three-samples`（新读路径关闭） |
| `experiment-p2read-16tables` | 16/64 | 60 | 60.0 | 通过 | —（P2 新读开启后的同规模对照） |
| `formal-p2read-192` | 48/192 | 300 | **300.0** | **通过** | —（P2 新读路径开启） |
| `formal-p2read-256` | 64/256 | 300 | **300.0** | **通过** | —（P2 新读路径开启，160 次 GET/秒） |
| `repeat-256-round1-aborted-pathfix` | 64/256 | 1800 | 153.0 | 未通过（作废） | `external-stop`（输出路径写高一层，操作者主动停轮后重跑，不计入容量结论） |
| `repeat-256-round1` | 64/256 | 1800 | **1800.0** | **通过** | —（目标档位重复验证第 1 轮） |
| `repeat-256-round2` | 64/256 | 1800 | **1800.0** | **通过** | —（第 2 轮；期间有 2 次孤立的瞬时 gauge >0.9，未连续三次，保护未触发） |
| `repeat-256-round3` | 64/256 | 1800 | 1194.4 | 未完成 | `external-stop`（按操作者要求中止，已跑 66%，0 失败、64/64 房间正常解散） |
| `provision-512-accounts` | 128/512 | 30 | — | 无效应（发压侧干扰） | `ws-open-Opening handshake has timed out`（本机代理 TUN 生效期间建桌，握手未达服务器；账号注册成功，见下） |
| `ladder-320-80tables` | 80/320 | 300 | — | 无效应（发压侧干扰） | 同上（代理，40 桌即失败） |
| `probe-80tables-directip` | 80/320 | 30 | — | 无效应（发压侧干扰） | `closed-while-waiting`（代理期间直连 IP 也一样） |
| `probe-4tables-smoke` | 4/16 | 30 | 30.0 | 无效应（监控隧道断） | `monitor-unavailable-three-samples` |
| `probe-4tables-smoke-2` | 4/16 | 30 | 30.0 | 通过 | —（代理退出 + 隧道重建后的环境校验） |
| `ladder-320-80tables-retry` | 80/320 | 300 | **300.0** | **通过** | —（2026-10-14 扩展阶梯第 1 档，200 次 GET/秒） |
| `ladder-384-96tables` | 96/384 | 300 | **300.0** | **通过** | —（第 2 档，240 次 GET/秒） |
| `ladder-448-112tables` | 112/448 | 300 | 178.2 | 未通过 | `cpu-over-90-percent-for-three-samples`，且伴随 180 次 `ECONNRESET`（0.36%） |
| `p3-384-round1` | 96/384 | 1800 | 1017.0 | 未通过 | `available-memory-under-100MiB`（17 分钟后可用内存跌破 100 MiB；期间 244,178 请求 / 56 失败） |
| `p3-320-round1` / `p3-256-round1` | 80/320、64/256 | 1800 | 58.0 / 17.0 | 未通过 | 同因（前一档耗尽内存后未恢复，起跑即被内存保护停止） |
| `p3-384-round2` / `p3-320-round2` / `p3-256-round2` | 96/384、80/320、64/256 | 1800 | 37.0 / 0.0 / 28.0 | 未通过 | 同因（整机可用内存始终 <100 MiB） |
| `p3-384-round3` / `p3-320-round3` / `p3-256-round3` | 96/384、80/320、64/256 | 1800 | 0.0 / 0.0 / 0.0 | 未通过 | 同因 |
| `p4-360-round1` | 90/360 | 1800 | 16.4 | 无效应（冷启动） | `cpu-over-90-percent-for-three-samples`：应用重启后仅 15 秒就开压，CPU 在**尚未建房的登录阶段**已 0.88–0.93（JIT/类加载 + 360 并发登录），建房后 0.97/0.99/0.99 连续三拍触发 |
| `p4-warmup-16tables` | 16/64 | 60 | **60.0** | **通过** | —（冷启动失败后的预热轮：2,401 请求 0 失败） |
| `p4b-360-round1` | 90/360 | 1800 | 542.0 | 未通过 | `available-memory-under-100MiB`：预热后重跑，第 9 分钟可用内存跌破 100 MiB；该轮 121,841 请求 **0 失败**，但 P95 从 29 ms 涨到 259 ms、max 3,529 ms |
| `p5-after-cnftune-16tables` | 16/64 | 60 | **60.0** | **通过** | —（MySQL 内存压缩后的同规模 A/B：list-v2 P95 28.6 ms，改前同规模 36.7 ms；P2 索引路径单次成本 0.42 ms 不变，物理读仅 +119 页） |
| `p6-400-round1` | 100/400 | 1440 | 113.0 | 未通过 | `cpu-over-90-percent-for-three-samples`（0.92/0.96/0.93 连续三拍）；28,204 请求 0 失败；**内存余量 384 MiB，与内存无关** |
| `p7-384-round1` | 96/384 | 1200 | 151.0 | 未通过 | 同因（CPU 峰值 0.98、均值 0.68、6/41 采样 >0.9）；36,324 请求 0 失败；内存 362–394 MiB。**下午 `ladder-384-96tables` 的 300 秒"通过"为临界通过**（峰值 0.92、3 个 >0.9 但不连续） |
| `p8-320-round1` | 80/320 | 900 | **900.0** | **通过** | 180,001 请求 0 失败；CPU 均值 0.66、峰值 1.00（>0.9 仅 2/190 采样）；内存最低 361 MiB |
| `p8-320-round2` | 80/320 | 900 | **900.0** | **通过** | 180,000 请求 0 失败；CPU 均值 0.66、峰值 0.91（1/190）；内存最低 359 MiB |
| `p8-320-round3` | 80/320 | 900 | **900.0** | **通过** | 180,001 请求 0 失败；CPU 均值 0.66、峰值 1.00（2/190）；内存最低 346 MiB |

`smoke-1-table`、`formal-v2-192`、`formal-v2-192-attempt2` 是脚本或保护机制产出的失败轮，按交付约定保留，不删不改。两轮 192 人失败后完成了 P2 回填与 promote，再跑第三轮通过。

**`p4*` 这两轮（2026-10-14 20:35 应用重启后）说明"重启应用只买到一次性内存"**：重启把可用内存从 103 → 366 MiB、java RSS 467 → 242 MiB；但 360 人那轮**准备阶段 80 秒就吃掉 187 MiB**（366 → 179），因为冷 JVM 要重建工作集（堆 committed 62 → 163 → 181 MiB、线程 46 → 226 → 320、代码缓存/metaspace 重新加载），随后仍以约 2.4–3 MiB/分钟增长，第 9 分钟撞线。全程 CPU 只有 0.72–0.83——**这轮是内存墙，不是 CPU**。要跑 360 人 × 30 分钟，启动时需 ≈370–380 MiB 可用且 JVM 已热；仅"重启应用"不解决，必须做结构性回收（mysqld ~200–300 MiB）或加内存。

**2026-10-14 的 `provision-512-accounts`、`ladder-320-80tables`、`probe-80tables-directip`、`probe-4tables-smoke` 是发压机侧事故（本机 Clash Verge 代理 TUN + SSH 隧道断开）造成的失败，不是服务器容量结论**：服务器侧 WS 握手只有 success、无 error，nginx 无报错，失败时服务器 CPU 仅 0.15–0.23。代理退出后同一个 80 桌配置由“40 桌即失败”变为 320 人通过。详见复测报告。

**`p3-*` 这 9 轮是服务器内存墙，不是 CPU**：384 人 30 分钟跑到 17 分钟时可用内存从 167 MiB 一路降到 ~101 MiB 触发保护（同期 CPU 只有 0.75–0.80、Hikari 排队 0）。之后 mysqld（RSS 631 MiB）与 java（RSS 477 MiB）常驻不释放，整机可用内存再没回到 100 MiB 以上，所以 320/256 的回退轮全部在起跑线被同一保护停掉——**不代表 320/256 不行**（它们在 300 秒档已两次通过）。环境背景：`game.log` 已 542 MiB、`Max_used_connections=713`、`performance_schema=ON`、`innodb_buffer_pool_size=128 MiB`、JVM 堆上限约 396 MiB。

## 2026-10-14 扩展阶梯与扩容准备

- **容量上限**：320 人（80 桌，60,001 请求 0 失败、P95 40.0 ms）与 384 人（96 桌，72,000 请求 0 失败、P95 59.5 ms）各 300 秒通过；**448 人（112 桌）178 秒被 CPU 保护停止并出现 180 次 `ECONNRESET`（0.36%）**；512 人未测。上限在 384 与 448 人之间，绑定资源是 CPU（2 vCPU 到 0.95）与内存（126 MiB 且随人数下降），带宽不再是约束（448 人时应用出网 6.6 Mbps）。
- **工具上限**：`test-config.mjs`、`online-capacity-retest.mjs` 由 1~64 桌放宽到 **1~128 桌**；>64 桌要求账号 ≥ 桌数×4。
- **账号**：`provision-512-accounts` 一轮显式注册后专用账号 **256 → 512**（新账号抽查登录/战绩/待处理接口均 200；账号文件本地 0600）。
- **告警**：`ops/monitoring/capacity-alerts.yml` 出网阈值 7.5 → **15 Mbps**（20 Mbps 的 75%），线上 Prometheus 需 reload 生效。

## P2 新读路径上线记录（2026-10-12 17:41–17:46）

- 备份：`/root/mahjong/backup-20261012-174147.sql.gz`，SHA256 `401946f3a87d3ddfe2f1d586c38d75f587b178296c178931a98ead8eab841a0f`；恢复到临时库后 6 张关键表行数一致，临时库已删除。
- inspect：`duplicateRounds=0 duplicateScores=0 incompleteRounds=0 orphanScores=0`；新表分页走 `idx_player_session`。
- backfill 1605/1606 场；会话 70（2026-09-06 机器人局）因 `game_round_scores.id=593` 的 seat 0 写成不存在的 user 0 而报 `Participant seat mismatch`，经确认把该行 `user_id` 由 0 改为 4 后补齐（详见复测报告）。
- verify 1606 场一致；promote 后门闩 `game_record_versions(session_id=0, through_round=0, revision=1)`，`game_session_players=6424`（=1606×4）。
- `.env` 追加 `RECORDS_P2_READ_ENABLED=true`（备份 `.env.bak-p2read-20261012-174640`），17:46:41 重启，10 秒后健康。
- 功能等价：`p2-reads-before.json` / `p2-reads-after.json`（6 个专用账号 × 16 个请求，切换前后逐字段比对差异 0），工具为 `compare-p2-reads.mjs` 与 `diff-json.mjs`。
- DB 成本：16 桌 60 秒受控实验，窗口内数据库总时间 14.3 s → 4.1 s（-71%），V2 战绩路径的 `game_sessions` 全表 JSON 扫描归零。

## 每轮文件

`results.json`（完整报告）、`run-config.json`（配置，账号文件已隐去）、`server-metrics.jsonl`（5 秒一次服务端采样）、`resource-stop.json`（保护停止原因，仅被停止的轮次有）、`monitor-done.json`。

`extract-run.mjs` 是本目录的只读提取脚本（`node extract-run.mjs <run 目录>...`），用于把 `results.json` 里的关键字段打成文本，不写任何结果。

## 192 人两轮的实测数字

第二轮（预热后，窗口 74.4 秒）HTTP 8926 次、失败 0、实际 120.01 次/秒，但最慢接口 P95 已到 684 ms、P99 787 ms、最大 1279 ms；48 桌全部解散、会话结束、记录与列表校验 0 错误、无遗留 pending。

第一轮（冷 JVM，窗口 16.3 秒）HTTP 1956 次、失败 0，最慢接口 P95 107 ms，同样被保护停止。

两轮的 CPU 曲线见 `formal-v2-192*/server-metrics.jsonl`：第一轮第 2 个采样点即 0.90，随后 0.99/0.99；第二轮先在 0.70-0.80 维持约 50 秒，再连续三点 0.99/1.00/1.00 触发停止，同时 HikariCP 活动连接从 1 涨到 10（池上限 10）、排队 20-51。

注意窗口口径：`server-metrics.jsonl` 里的 `system_cpu_usage` 是瞬时 gauge（约 10 秒刷新），保护逻辑用的就是它；运维 Grafana 面板和 `capacity-alerts.yml` 用的是 `mahjong_host_cpu_ticks` 的 2 分钟 rate，同一次饱和显示为 0.66-0.68。两者的对照表见复测报告里的「CPU 口径对齐」一节。

## 复现命令

```bash
# 账号文件放 docs/performance/private/accounts.json（已 gitignore），并在 config.local.json 指向它
cd docs/performance
node run-capacity.mjs --config config.local.json --check
node run-capacity.mjs --config config.local.json
node summarize-results.mjs results-2026-10-12-192-retest/<run 目录>
```

每轮必须用新的空 `outputDirectory`；配置里的 `metricsUrl` 会让入口同时启动 `monitor.mjs`，其 `resource-stop.json` 就是本目录里的停止原因。
