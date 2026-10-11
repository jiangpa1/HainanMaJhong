# 海南麻将

支持四人联网、机器人托管、断线重连和历史战绩的海南麻将项目。后端使用 Java 17、Spring Boot 2.7.18、MyBatis-Plus、MySQL 与 Redis；前端使用 Vue 3、Pinia 与 Vite，支持手机虚拟横屏。

胡牌判定参考开源 `mjlib_java` 查表实现，并扩展至吃碰杠后的 2/5/8/11/14 张暗牌。HTTP 和 WebSocket 使用 JWT 认证，access token 有效期 30 分钟，refresh token 有效期 7 天。

## 最近更新（2026-10-10～11）

- **3.1 房间生命周期**：关闭终态、代际与版本保护、Redis 原子更新和任务取消，防止解散后迟到任务重写快照；协调结束与结算。
- **3.2 容量监控**：Actuator/Micrometer、Prometheus、Grafana 面板、告警规则和 Windows 一键启动脚本，观察 HTTP、WebSocket、JVM、数据库、Redis 与主机资源。
- **4.1 战绩 V2**：拆分列表、总览、局摘要分页与单局详情，首屏默认 10 局，固定分页截止点。
- **4.2 前端按需加载**：合并重复请求，详情按需读取，结算后增量刷新；隐藏暂停、关闭取消任务、限制失败重试。
- **4.3 JSON 压缩**：战绩路由使用 Nginx gzip 级别 1，设置 `Vary` 和 `private, no-store`，提供安装、验证与回退工具。

## 真实压测结果

2026-10-11 复测使用 48 桌、192 个独立测试账号，每桌 2.5 次 GET/秒，正式窗口 300 秒。

| 场景 | HTTP 总数 / 失败 | 最慢接口 P95 | CPU 均值 | 整机出网 | 完整验收 |
|---|---:|---:|---:|---:|---|
| 10/09 基线 | 36,000 / 0 | 39.0 ms | 48.3% | 7.93 Mbps | 通过 |
| 10/11 原接口无压缩 | 36,001 / 15 | 1,179.3 ms | 55.7% | 5.47 Mbps | 通过 |
| 10/11 原接口 gzip 复测 | 36,001 / 10 | 892.0 ms | 53.4% | 3.57 Mbps | 通过 |
| 10/11 V2 + gzip | 36,001 / 8 | 1,052.8 ms | 60.1% | 3.22 Mbps | 未通过 |

原接口 gzip 复测完整响应体从解码后的 101.42 MiB 压至 24.16 MiB，减少 **76.2%**。V2 性能与结算检查达标，但两次收尾 pending 查询连接重置，严格验收未通过；补充读取确认房间已清理，仍保留失败结果。本轮没有升至 256 人。

**流量下降，但尚未证明容量上限提升。** 低负载公网延迟、账号历史和广播确认行为与旧基线不同，不能把延迟或 CPU 变化全部归因于代码。另一次 gzip 首测触发保护停止，完整报告保留该失败记录。

![完整 300 秒轮次出网对比](docs/performance/results-2026-10-11-retest/charts/netMbps.svg)

- [线上复测完整报告](HainanMaJhong/docs/线上压测对比_2026-10-11.md) · [PDF](output/pdf/海南麻将线上压测对比_2026-10-11.pdf) · [CSV](docs/performance/results-2026-10-11-retest/online-comparison.csv)
- [两日优化对比：构造样本与线上配置核对](HainanMaJhong/docs/优化前后对比_2026-10-10至11.md) · [PDF](output/pdf/海南麻将优化前后对比_2026-10-10至11.pdf)

## 构建与部署

```bash
cd HainanMaJhong/frontend
pnpm install --frozen-lockfile
pnpm build
pnpm check
pnpm check:records-v2
pnpm check:record-refresh
cd ..
mvn clean package
```

使用 JDK 17。前端构建写入 `src/main/resources/static`，产物随代码入库；打包使用 `clean`，避免旧资源残留。数据库、Redis 和 JWT 凭据参照模块 `.env.example` 配置，实际 `.env` 不入库。生产使用 `SPRING_PROFILES_ACTIVE=prod`。

房间运行态仍在应用内存，当前按**单实例**部署。Redis 快照用于恢复和生命周期保护，不应直接增加应用副本分流房间。

## 监控访问

运维电脑安装并配置 Prometheus/Grafana 后，双击 [start-monitoring.cmd](HainanMaJhong/ops/monitoring/start-monitoring.cmd)，健康检查完成后打开 `http://127.0.0.1:3000/d/hainan-capacity`。默认安装根目录 `F:\MahjongMonitoring`，可通过 PowerShell 启动器参数更改。

管理端口默认只监听 `127.0.0.1:9091`，通过 SSH 隧道采集。面板配置和首次导入步骤见 [容量监控说明](HainanMaJhong/docs/容量监控面板说明.md)。

## 文档与目录

- [模块 README：规则、协议、运行与部署](HainanMaJhong/README.md)
- [前端开发说明](HainanMaJhong/frontend/README.md)
- [并发能力提升计划书](HainanMaJhong/docs/海南麻将并发能力提升计划书.md)
- [房间生命周期修复](HainanMaJhong/docs/房间生命周期修复说明.md)
- [战绩 V2 接口](HainanMaJhong/docs/战绩V2接口说明.md)
- [战绩前端按需加载](HainanMaJhong/docs/战绩前端按需加载说明.md)
- [HTTP JSON 压缩](HainanMaJhong/docs/HTTP_JSON压缩说明.md)
- `HainanMaJhong/src/`：后端源码、测试与静态资源；`frontend/`：Vue 源码；`ops/`：监控与运维工具。
- `docs/performance/`：测试与报告生成脚本、公开汇总数据和图表。原始线上采样和运行日志保留在本机，凭据、私钥不上传。
