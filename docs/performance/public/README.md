# 可公开关键结果

[key-results.csv](key-results.csv) 摘录报告中的关键标量，包含失败与中止轮；原始运行记录保留在忽略目录和本地归档，不随仓库公开。字段为空表示没有在此汇总采集，不代表 0。

`originalResult` 保留报告判定，`scope` 说明可用范围。不同日期、带宽、账号历史、预热及保护阈值不可直接比较。此表不覆盖所有轮次，也不按新门槛重判旧轮次；请求数是历史报告口径，不保证包含中间 429 重试。

`httpReportedCount` 对普通轮为报告请求总数，R1–R4 为准入报告的成功数；R2/R3 另列最终失败，不能将成功数视为全部尝试。此汇总不用于重新计算错误率。Phase 1 行没有摘录 HTTP 数，因此留空。

```bash
node docs/performance/public/write-manifest.mjs
```

脚本只读取本目录公开文件和白名单来源报告，生成 `manifest.json`；不访问服务器或私有结果。完整说明见[方法论](../../../HainanMaJhong/docs/八天并发优化方法论_2026-10-16.md)与[证据规范](../../../HainanMaJhong/docs/工程交付与数据规范.md)。
