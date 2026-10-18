# 海南麻将：并发工程实践

支持四人联网、机器人托管、断线重连和历史战绩。后端 Java 17 / Spring Boot 2.7.18 / MyBatis-Plus / MySQL / Redis，前端 Vue 3 / Pinia / Vite。项目用于实践并发正确性、性能归因和过载保护。

## 从这里阅读

| 关注点 | 材料 |
|---|---|
| 八天如何从压测走到优化与反证 | [方法论与实测证据](HainanMaJhong/docs/八天并发优化方法论_2026-10-16.md) |
| 具体实现了哪些并发模式 | [失效条件、保护方法与源码入口](HainanMaJhong/docs/并发模式与失效清单.md) |
| 正常、超载与异常的边界 | [运行额度、CPU 护栏与回退](HainanMaJhong/docs/运行边界与线上额度_2026-10-16.md) |
| 最新闭环验证与后续改动 | [320 人两轮 30 分钟及超载/回退证据](HainanMaJhong/docs/运行边界闭环证据_2026-10-16.md) · [告警、测量与复验入口](HainanMaJhong/docs/运行边界闭环后续实施_2026-10-16.md) |
| 查设计和历史报告 | [文档总索引](HainanMaJhong/docs/README.md) |
| 查看实现、规则与启动方式 | [项目说明](HainanMaJhong/README.md) |
| 复现负载与查看公开结果 | [压测入口](docs/performance/README.md) · [关键结果与来源](docs/performance/public/README.md) |
| 从源码交付 | [构建与数据规范](HainanMaJhong/docs/工程交付与数据规范.md) · [本次构建核验](HainanMaJhong/docs/干净构建核验_2026-10-16.md) |

## 当前能证明的结果

- P2 受控 64 人窗口：数据库累计语句耗时 **14.3→4.1 秒（−71.3%）**；P2 后 192/256 人各 300 秒完成 **36,001/48,001 请求、0 失败**，最慢接口 P95 **32.6/34.9 ms**。
- 256 人完成两轮 30 分钟，第三轮中止；320 人先有三轮 15 分钟，10/16 干净包 `44f94228…` 又完成同进程两轮 30 分钟、720,001 请求零失败。后续新包需单独复验。
- 512 人热实例、放大准入额度下完成 **96,001 请求、0 最终失败、P95 261 ms**；该轮按当时的"未匹配广播率"门槛判为未通过——该计数器已查明为**工具跨连接记账伪影**（协议序号 273 万帧零缺口），2026-10-16 起判据改为**协议序号缺口（`maxSequenceGaps`）**，历史轮次不改判。见[工具已知偏差](HainanMaJHong/docs/性能测试工具已知偏差.md)。
- WS delta 在成功轮发送约 **−46%**、字节约 **−36%**，重复实验没有可分辨 CPU 收益，320 人仍有间歇收尾失败，**保留默认 full**。
- 登录与查询已实现信号量、分层令牌桶、429/Retry-After、有限退避抖动。战绩并发保留 **48**；CPU 压测护栏默认 **>0.95 持续至少 60 秒**。

以上是历史实测摘录。环境经历带宽 10→20 Mbps、数据库配置、保护策略与预热变化，不能把人数增长全部归因于代码。结论、失败轮与限制见对应报告和方法论。

## 完整构建

准备 JDK 17、Maven、Node.js ≥22、pnpm，复制 `HainanMaJhong/.env.example` 并在本地填写环境配置。数据库初始化及 P2 切换见项目文档。

```powershell
cd HainanMaJhong/frontend
pnpm install --frozen-lockfile --ignore-scripts
cd ..
./ops/build-release.ps1 -JavaHome '<JDK17目录>' -Maven '<mvn.cmd路径>'
```

生成 `target/HainanMaJhong2-1.0-SNAPSHOT.jar` 及 SHA256/源码清单。脚本重建前端、执行 Maven clean package、编译但不运行测试，并核对全部编译类入包。运行服务前按[项目说明](HainanMaJhong/README.md)配置 MySQL、Redis 与环境变量。本次构建不代表运行时和性能验收。

## 目录与公开边界

`HainanMaJhong/` 是应用；`docs/performance/` 是参数化压测工具；`docs/performance/public/` 是人工复核的公开结果。真实账号、原始结果、私有脚本和数据库备份保持本地忽略；jar、交付 zip 与诊断转储不入库。发布前从源码完整构建，并通过内容 hash 确认版本。

判胡查表实现参考开源 mjlib_java，并针对海南麻将规则与吃碰杠后的暗牌数量扩展；细节见项目说明。历史运维包的生成日期与当前源码不同，请以完整构建和内容 hash 确认版本。
