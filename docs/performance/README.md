# 运维压测入口

这是可单独安装的压测项目，不依赖前端 node_modules、本机路径或 Windows DPAPI。需要 Node.js 22/24、pnpm 11，以及能访问测试目标的独立发压机。游戏服务器仍按单实例部署。

## 先确认版本和数据

P2 已有 10/12 的上线与验收记录；后续源码和历史补丁 jar 不自动共享验收结论。新环境若压测 P2 优化路径，先按[实施说明](../../HainanMaJhong/docs/战绩P2查询与缓存说明.md)完成双写、回填、比对、promote，并开启 `RECORDS_P2_READ_ENABLED=true`。仅把接口切到 v2 不代表数据库查询已切到 P2。记录完整构建 jar SHA256、三个 P2 开关、MySQL 版本、服务器规格、带宽、初始账号历史和配置。

历史基线见 `results-2026-10-11-retest/online-comparison.csv` 和 `history/index.csv`。原 gzip 首测和 V2 收尾失败都保留；不能把补充读取成功改记为原轮通过。脱敏历史 JSON 为派生数据，原始文件和原 SHA256 留在本地归档中。

历史本地目录有 15 份派生运行记录与 3 份采样 JSONL；这些内容默认忽略，公开只留索引及[逐轮完整汇总（152 轮）](../../HainanMaJhong/docs/压测记录汇总.md)。索引中的路径用于对照本地归档，不承诺公开仓库含原始文件，也不重新判定历史通过状态。

当前结论、准入额度和保护策略见[运行边界](../../HainanMaJhong/docs/运行边界与线上额度_2026-10-16.md)。普通 CPU 保护默认 `>0.95` 持续有效观测至少 60 秒，断样重置 CPU 窗口；实际阈值写入每轮 `guard-policy.json`。`pnpm check` 会运行测试，本次整理未执行它。

当前 `measurementVersion=3`：正式 GET 默认 `formalReadRetries=0`，准备/收尾 `preparationReadRetries=4`；分别记录原始尝试/429、逻辑耗时、单次成功耗时与退避等待。旧版默认重试会覆盖正式查询，旧结果未采集完整尝试数时不得宣称没有发生限流。私有脚本可留作本机便利入口，公开复现以这里的参数化 runner 为准。

新增 `acceptanceProfile=overload`，专供预期拒绝实验：必须提供 metricsUrl、固定速率；允许预期 429，但要求成功客户端/服务端 P95≤500 ms、P99≤2 s、足够样本、无其它失败且收尾正常。普通 p2 的错误率门槛保留。`rateSteps` 与建房节拍用于独立冷启动实验，不能与 overload 混用以稀释超载窗口。

可用 `node generate-boundary-plan.mjs --config config.local.json --out private/新计划目录` 生成候选速率、重试对照、恢复与冷启动配置；只写文件，不连接服务器。细节及邮件模板见[后续实施](../../HainanMaJhong/docs/运行边界闭环后续实施_2026-10-16.md)。

## 安装和配置

```bash
cd load-test  # 仓库内为 docs/performance
pnpm install --frozen-lockfile --ignore-scripts
pnpm check
```

复制 `config.example.json` 为 `config.local.json`，填写真实目标 `baseUrl` 和专用账号文件。配置相对路径以配置文件所在目录为基准。

账号 JSON 格式为 `[{"username":"专用账号","password":"本地填写","id":123}, ...]`，必须每个玩家一个独立账号。账号数量至少为最大桌数×4。真实凭据放 `private/`，通过受控渠道另行提供；交付包没有现成账号或生产凭据。`accounts.example.json` 仅展示结构，不是可登录账号。

若尚无测试账号，明确设置 `allowRegistration=true`，保留 1 桌、30 秒，首次运行会注册缺少的专用账号，并保存到账号文件；不会删除这些账号和测试战绩。后续换大档位时如需继续注册也必须显式开启。完成建号后关闭该开关，所有对照轮复用同一批账号。Linux 私有文件为 0600；Windows 文件权限由运维限制，不能放共享目录或跟结果一起发送。

```bash
node run-capacity.mjs --config config.local.json --check  # 只校验配置，不连接服务器
node run-capacity.mjs --config config.local.json          # 实际建房、对局并发压
node summarize-results.mjs runs/run-001 runs/run-002     # 生成 run-summary.csv
```

每轮使用新 `outputDirectory`，不覆盖旧结果。中断时用 Ctrl+C，让脚本完成收尾；强杀后必须依据私有账号检查 pending 和本轮房间，不能直接开始下一轮。同账号同时运行两轮会触发单点登录踢线。

## 人数、时长和验收

|阶段|tables|seconds|说明|
|---|---|---|---|
|连通与账目检查|[1]|30|4 人；确认权限、协议与收尾|
|短测阶梯|[36,48,56,64]|300|144→192→224→256 人；首个失败停止|
|扩展阶梯（需要更多账号）|[80,96,112,128]|300|320→384→448→512 人；首个失败停止|
|目标档位重复验证|[64]|1800|每轮新目录，做 3 次独立 30 分钟；先确保短测通过|

默认每桌 2.5 次 GET/秒、每次行动等待 100～300 ms；192 人对应 120 GET/秒，256 人对应 160 GET/秒，512 人对应 320 GET/秒。支持 1～128 桌、30～1800 秒、每桌 >0 且 ≤5 GET/秒（1～64 桌是 2026-10-11 交付时验证过的范围，>64 桌需要账号数 ≥ 桌数×4，且必须在同一台发压机上评估 WebSocket 与内存开销）。HTTP token 临近过期时合并刷新，不重试失败刷新。8 小时连续长测尚无专用入口，不能用多轮 30 分钟替代连续长测。

`recordsVersion=legacy/v2` 选择接口，`encoding=identity/gzip` 选择传输。`acceptanceProfile=baseline` 保留旧基本门槛，`p2` 额外要求各接口 P95≤500ms、P99≤2s，四人广播 P95≤150ms、P99≤500ms，握手 P95≤1s、P99≤3s，总失败率≤0.1%，采样的 1/5 分钟窗口分别≤0.5%/0.1%。短烟测不具备完整 5 分钟窗口。报告同时保存 baselinePass、p2Pass 和所选口径的 pass，不可混用。

P2 混合场景查询列表、总览、小局第一页、列表第二页、pending；**不持续压单局大详情，也不模拟浏览器 DOM**。10/100/1,000 局长历史 SQL 的正确性测试在后端 `RecordsP2SqlIT`，这套混合脚本不会自动往真实库导入长历史。运维需要另外准备隔离长历史样本和单局详情专项，才能完成计划书全部验收。

## 资源监控与停止

优先使用 SSH 隧道采集回环管理端口，不公开 Actuator。运维提供实际 SSH 用户、主机和密钥：

```bash
ssh -N -L 19091:127.0.0.1:9091 USER@SERVER
node monitor.mjs --url http://127.0.0.1:19091/actuator/prometheus --out runs/run-001
```

监控输出目录必须与配置中的 `outputDirectory` 相同。先用 `--check` 校验新空目录；启动压测后立即启动监控，或让监控与发压启动协调，避免提前写入使空目录校验失败。运行中 CPU连续三次>90%、可用内存<100MiB、采样过期或连续三次采集失败，会写 `resource-stop.json` 通知发压停止。压测完成写 `monitor-done.json`，监控自动结束。

推荐在本地配置加 `"metricsUrl":"http://127.0.0.1:19091/actuator/prometheus"`：先建立 SSH 隧道，入口会自动启动同目录监控，无需第二次手动启动 monitor。配置不接受密码、令牌等额外字段；凭据只允许在单独账号文件中。

已有监控文件不能被新轮覆盖；资源停止后核查原因和测试房间，保留停止证据。HTTP 最近 20 秒的失败率>5%或 P95>5s 连续两次触发停止；另有累计错误、游戏停滞、协议和账目异常保护。最多 2048 个 HTTP 在途请求。

## 运维应回传

- 每轮 `results.json`、`run-config.json`、`server-metrics.jsonl`、标准输出日志和汇总 CSV。
- jar SHA256、P2开关/迁移状态、服务器与网络规格、MySQL索引和代表性执行计划。
- 账号初始历史规模、实际人数/请求率、准备与正式窗口时刻、停止原因。
- 分接口 P95/P99、失败率、广播延迟、CPU/内存/GC/连接等待/出网和缓存命中情况。
- 收尾是否完成：四家账目一致、会话结束、pending清空、房间解散。只审计本轮房间，不能批量清理业务数据。

不要回传密码、token、私钥、真实用户表或私有账号文件。`results.json` 可能包含测试账号和房间标识；对外分享用脱敏汇总。历史汇总和新轮结果不能在不同历史规模、接口组合、带宽和发压机条件下直接宣称容量提升。
