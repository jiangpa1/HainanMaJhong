# 4.3 HTTP JSON 压缩

## 当前状态

2026-10-11 已完成战绩专用 Nginx 配置、安装/回退脚本、隔离 Nginx 验收和压测客户端解码改造。同日部署后已核对线上战绩路由返回 gzip；完整容量复测见 [线上压测对比](线上压测对比_2026-10-11.md)。 本阶段改的是代理配置，不需要重新打包 Java JAR。

只读核对线上 Nginx 1.18.0：`/etc/nginx/nginx.conf` 开启了 `gzip on`，但 `gzip_types` 的 JSON 配置被注释。麻将代理在 `/etc/nginx/conf.d/mahjong.conf`，只有 `location /` 转发到 `127.0.0.1:8080`。

## 配置行为

`ops/nginx/records-gzip-location.conf` 放入麻将的 server 块，匹配 `/api/records`、`/api/v2/records` 及子路径：

- 明确压缩 `application/json`，初始级别 1，已知 Content-Length 的响应至少 1,024 字节才压缩。
- `gzip_vary on`，可压缩响应携带 `Vary: Accept-Encoding`；拒绝 gzip 或使用 identity 的客户端仍收到原始数据。
- Nginx 清空发给上游的 Accept-Encoding，由代理负责压缩，避免重复压缩。浏览器自行管理这个请求头，Vue 不需要手动设置。
- 禁用共享代理缓存，统一返回 `Cache-Control: private, no-store`。鉴权仍由现有应用完成。
- 独立战绩 access log 记录压缩比、正文/HTTP 字节和耗时，不记录令牌、Cookie、请求参数或响应正文。
- 保留原来的 `/` 代理及 WebSocket Upgrade；登录、房间操作和 WebSocket 帧不在新增的 JSON 压缩范围内。

Nginx 的 `gzip_min_length` 根据 **Content-Length** 判断。Spring 返回未知长度/分块响应时，小于 1KB 的正文仍可能被压缩，不能把该值当作所有传输形式的严格字节门槛。[官方 gzip 文档](https://nginx.org/en/docs/http/ngx_http_gzip_module.html)

## 隔离验收结果

使用服务器上同版本 Nginx 的**独立进程**，仅监听动态分配的 `127.0.0.1` 端口。上游是按项目 V2 字段构造的合成 JSON，没有访问线上数据库或真实玩家战绩。每级别 200 次第一页请求，并发 4；测试后所有独立进程均已停止。

| 级别 | 10 局摘要正文 | 节省 | 200 次正文总字节 | P95 | Nginx worker CPU 时间 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 关闭 | 5,751 B | 0% | 1,150,200 B | 5.478 ms | 0.040 s |
| 1 | 585 B | 89.83% | 117,000 B | 4.756 ms | 0.040 s |
| 2 | 580 B | 89.91% | 116,000 B | 4.687 ms | 0.050 s |
| 3 | 577 B | 89.97% | 115,400 B | 5.499 ms | 0.050 s |

级别 1 下，1,418 B 的单局详情压至 366 B；合成旧版 1,000 局明细从 570,195 B 压至 22,820 B。已知长度的 136 B 列表、813 B 总览和 41 B 空列表没有压缩；3,053 B 的登录样本也没有进入该压缩范围。

级别 2/3 在主路径每次只再节省 5/8 B，先选级别 1。CPU 只有时钟 tick 粒度，样本较小，**不能根据这些差值判断某个级别 CPU 更快**；延迟也包含合成 Python 上游成本，不能替代生产延迟验收。

identity 与 gzip 解压后正文逐字节相同；验证了中文、`gzip;q=0`、Via/Authorization、Vary、大小阈值及禁止共享缓存。也验证了相同 41 B 正文在分块、未知 Content-Length 时仍会压缩，确认上述阈值限制。原始报告：`../../docs/performance/results-2026-10-11-gzip/gzip-results.json`。

报告同时记录 `/proc/net/dev` 的真实接口发送计数，但本测试流量经过 loopback，`lo` 还包含 Nginx 到上游的那一跳，`eth0` 增量不能代表战绩公网下载节省。**真实账号的压缩比、生产公网出流量及业务峰值下 CPU/延迟仍待启用后观察。**

## 上线与回退

PowerShell 将文件复制到服务器的运维目录：

```powershell
$taskKey = 'C:\Users\ASUS\.ssh\mahjong_ops'
ssh -i $taskKey root@39.107.247.119 'mkdir -p /root/mahjong/ops/nginx'
scp -i $taskKey F:\HainanMaJhong2\HainanMaJhong\ops\nginx\*.conf F:\HainanMaJhong2\HainanMaJhong\ops\nginx\*.py root@39.107.247.119:/root/mahjong/ops/nginx/
ssh -i $taskKey root@39.107.247.119 'python3 /root/mahjong/ops/nginx/install-records-gzip.py'
```

最后一条命令默认仅检查：用当前配置的副本加上拟安装片段执行 `nginx -t`，不改线上配置、不 reload。本次已完成这个检查。

正式启用：

```powershell
ssh -i $taskKey root@39.107.247.119 'python3 /root/mahjong/ops/nginx/install-records-gzip.py --apply'
```

脚本备份三个受影响文件，安装 http 日志格式和 server 内战绩 location，先执行 `nginx -t` 再平滑 reload；失败会恢复备份。成功输出备份目录和精确的回退命令。不要删除备份目录；回退使用 `--rollback <输出的备份目录>`，恢复后同样先检查再平滑 reload。

脚本针对已核对的一份 `mahjong.conf`。若服务器未来增加 records location、多个 server 块或改变 conf.d 引入方式，会停止并要求人工审查。不要把 server/location 片段直接作为 conf.d 顶层文件。

## 启用后的真实响应验证

用专用测试账号访问战绩，从浏览器开发者工具取临时 access token，放入当前 PowerShell 进程的 `RECORDS_GZIP_TOKEN` 环境变量；不要写入脚本、版本库或报告。过期或未参与该场次时，探针会直接失败。

```powershell
# 先在当前终端设置 RECORDS_GZIP_TOKEN（不在聊天中发送令牌）。
$taskNode = 'C:\Users\ASUS\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe'
& $taskNode F:\HainanMaJhong2\docs\performance\verify-records-gzip.mjs `
  'http://jiangpahnmj.cn' `
  '/api/v2/records/实际场次ID/rounds?afterRoundNum=0&pageSize=10&readThroughRoundNum=实际截止局号'
Remove-Item Env:RECORDS_GZIP_TOKEN
```

探针每个路径串行执行 3 对 identity/gzip 请求，比对解压后的 SHA256，检查 gzip、Vary、private/no-store，输出字节数和耗时。最多 8 个路径/48 次请求；不发送写请求，不保存响应内容或令牌。用带固定 `readThroughRoundNum` 的分页或已完成小局详情，避免把两次查询之间的新结算当成数据不一致。

## 两种压测基线及生产监控

原 `docs/performance/online-load-test.mjs` 现在默认明确发送 `Accept-Encoding: identity`。设置 `LOAD_TEST_ACCEPT_ENCODING=gzip` 可测试支持压缩的客户端；脚本使用原始 HTTP 客户端统计**接收的压缩正文 bytes**，解压后校验业务 JSON，并另记 decodedBytes 和 encodingCounts。损坏压缩包、未知编码、解压超过 2MB 会判失败。

两种模式使用不同 `LOAD_TEST_RUN_NAME` 保存报告，避免覆盖；凭据沿用原流程。该脚本包含登录和阶梯压测，运行前使用专用账号并单独安排测试窗口。4.3 没有重新对生产执行阶梯压测。旧报告未声明编码，不更名为“无压缩基线”。

上线后按相同时间窗口比较：

1. `/var/log/nginx/records-gzip.log` 的 bodyBytes、httpBytes、gzipRatio、requestSeconds。日志 gzipRatio 是“原始/压缩”比值，`-` 表示本次没有压缩。
2. 现有 Grafana 的“网卡出入流量与 10Mbps 出网占比”面板：`rate(mahjong_host_network_transmit_bytes{job="hainan-mahjong"}[2m]) * 8`，单位 bit/s。这是配置所选网卡的累计字节换算，包含其他业务流量，不能只按 JSON 正文比例推断公网账单。
3. 主机 CPU、现有 HTTP P95/P99 与错误率；Nginx access log 的 requestSeconds 补充代理耗时。监控端口不加入该压缩 location。

gzip 正文减少不代表 TCP/TLS 开销、静态资源、WebSocket 或总公网流量同比减少。原始 JSON 类型、大小和实际请求占比决定最终收益。

## 可重复的本地检查

```powershell
& $taskNode F:\HainanMaJhong2\docs\performance\check-http-json-body.mjs
& $taskNode F:\HainanMaJhong2\docs\performance\check-records-gzip-probe.mjs
```

Linux 上重新运行独立 Nginx 测试：

```bash
python3 verify-gzip.py --requests 200 --output gzip-results.json
```
