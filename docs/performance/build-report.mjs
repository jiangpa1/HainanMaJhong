import fs from 'node:fs';
import path from 'node:path';
const root=path.resolve('docs/performance/results-2026-10-08');
const read=file=>JSON.parse(fs.readFileSync(path.join(root,file),'utf8'));
const first=read('http-results.json');
const auth=read('authenticated/http-results.json');
const ws=fs.existsSync(path.join(root,'websocket-results.json')) ? read('websocket-results.json') : null;
const diagnostic=fs.existsSync(path.join(root,'websocket-diagnostic.json')) ? read('websocket-diagnostic.json') : null;
const health=fs.existsSync(path.join(root,'final-health.json')) ? read('final-health.json') : null;
const stages=[...first.stages,...auth.stages];
const names={'homepage':'首页 HTML','user-info':'个人信息','records':'战绩列表','login':'登录'};
const n=x=>x===null || x===undefined ? '—' : Number(x).toFixed(0);
const pct=x=>(100*x).toFixed(2)+'%';
const localTime=iso=>new Date(iso).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai',hour12:false});
const rows=stages.map(s=>`| ${names[s.name]} | ${s.targetRps} | ${s.successRps.toFixed(2)} | ${s.completed} | ${s.failed} | ${pct(s.errorRate)} | ${n(s.p95Ms)} | ${s.elapsedSeconds.toFixed(1)} | ${s.aborted ? '提前停止' : s.pass ? '达标' : '未达标'} |`).join('\n');
const wsRows=ws?.stages.map(s=>`| ${s.targetConnections} | ${s.openConnections} | ${s.holdSeconds.toFixed(1)} | ${s.successfulPings} | ${s.failedPings} | ${n(s.pingP95Ms)} | ${s.unexpectedCloses} |`).join('\n') || '';
const lastPass=name=>stages.filter(s=>s.name===name && s.pass).slice(-1)[0];
const maxWs=ws?.stages.filter(s=>!s.failure && s.openConnections===s.targetConnections).slice(-1)[0];
const lines=[
  '# 海麻线上压测报告', '',
  `- 地址：http://jiangpahnmj.cn`,
  `- 测试开始：${localTime(first.startedAt)}（北京时间）`,
  `- 负载测试结束：${localTime(diagnostic?.finishedAt || ws?.finishedAt || auth.finishedAt)}（北京时间）`,
  health ? `- 最后可用性复核记录：${localTime(health.checkedAt)}（北京时间）` : '',
  '- 测试来源：当前 Windows 电脑；请求经本机实际网络路径到站点。',
  '- 使用一个提供的账号；报告与脚本不保存账号密码、token 或战绩内容。', '',
  '## 结论', '',
  `- 本轮完整达标的最高已测试速率：首页 ${lastPass('homepage')?.targetRps ?? '无'} 次/秒；个人信息 ${lastPass('user-info')?.targetRps ?? '无'} 次/秒；战绩列表 ${lastPass('records')?.targetRps ?? '无'} 次/秒。`,
  '- 首页升至 25 次/秒时出现超时并自动停止；这不是已经确认的服务器极限。',
  '- 登录在低速测试中仍出现超时，因此本轮没有得到达标的登录容量。',
  maxWs ? `- 同一个账号的 ${maxWs.openConnections} 条空闲 /room 连接完成 ${maxWs.holdSeconds.toFixed(0)} 秒观察；这不等于 ${maxWs.openConnections} 个独立玩家。` : '- 未完成可用于确认空闲连接容量的 WebSocket 阶段。',
  '- 真实在线用户上限、同时打牌桌数、服务器本身的最大吞吐量仍未测得。', '',
  health ? `- 所有负载停止后，两次低频首页复核均返回 200，但分别耗时 ${health.probes[0].totalSeconds.toFixed(2)} 秒和 ${health.probes[1].totalSeconds.toFixed(2)} 秒。服务能够响应，但最终延迟明显偏高，不能确认恢复到最初响应水平。` : '',
  '## HTTP 实测数据', '',
  '每个查询档位计划持续 30 秒，登录档位计划持续 20 秒。实际时长包含最后一批请求等待结束；提前停止的档位不可视为完成持续测试。', '',
  '| 场景 | 目标请求/秒 | 实际成功请求/秒 | 完成请求 | 失败 | 失败率 | P95 毫秒 | 实际秒数 | 本轮验收 |',
  '|---|---:|---:|---:|---:|---:|---:|---:|---|',rows,'',
  'P95 表示 95% 的已完成请求耗时不超过该值；包含失败请求。一次 timeout 计为失败，不会因为 HTTP 200 或业务外壳而算成功。', '',
  '## 空闲 WebSocket', '',
  ws ? `- 端点：${ws.endpoint}` : '- 尚无测试数据。',
  '- 一个账号复用同一个 token；逐个建立连接。没有创建房间，没有发出出牌或其他业务指令。',
  '- 使用协议级 ping/pong 测量往返时间，这不代表出牌广播延迟。',
  '- 每档连接数为累计数量；最高计划 100 条，每档观察 30 秒。', '',
  '| 目标连接 | 保持打开 | 观察秒数 | 成功心跳 | 失败心跳 | 心跳 P95 毫秒 | 累计异常断开 |',
  '|---:|---:|---:|---:|---:|---:|---:|',wsRows,'',
  ws?.error ? `- 停止原因：${ws.error}。停止时目标 ${ws.currentTargetConnections} 条、打开 ${ws.openConnectionsBeforeCleanup} 条。` : '',
  diagnostic ? `- 低负载连接复核：${diagnostic.error || '完成'}；当前目标 ${diagnostic.currentTargetConnections} 条，已打开 ${diagnostic.openConnectionsBeforeCleanup} 条。该复核明确记录了握手超时，不能用它证明人数已满。` : '',
  ws ? `- 清理后剩余测试连接：${ws.remainingOpenConnections}。` : '', '',
  '## 方法与停止条件', '',
  '- HTTP 使用长连接复用；最多 32 个在途请求，登录最多 4 个。本机事件循环延迟也记录在原始数据中。',
  '- 每次请求超时 5 秒。HTTP 基线和 HTTPS 连接预检可使用更长超时，不计入负载阶段。',
  '- 通过标准：完整完成档位，没有因客户端并发上限跳过请求，失败率低于 1%，P95 小于 1500 毫秒。该标准是本轮定义，不能当作所有产品通用标准。',
  '- 连续 3 次失败立即停止；累计至少 100 个响应后，失败率超过 1% 或最近 100 次 P95 超过 3000 毫秒时提前停止。完成一档后若验收不通过，也停止增加该场景负载。',
  '- 首页检查 HTTP 200、Vue 应用容器和静态资源引用；登录检查业务码 200 和 accessToken；查询检查 HTTP 200、业务码 200 和 data 字段。',
  '- 未登录探测返回业务码 401，只用于验证响应规则，没有记为业务成功。', '',
  '## 诊断证据与限制', '',
  '- 战绩和登录的已记录超时均发生在 TCP 已连接后、未收到响应头期间；仍可能受代理、网络、nginx、应用或数据库影响，不能据此指定唯一原因。',
  '- 本机普通域名解析得到 198.18.0.36；公开 DNS 查询返回 39.107.247.119。用真实 IP 指定连接后，单次首页总耗时仍约 1.12 秒，没有明显改善。此信息用于说明访问路径的限制，不是服务器资源诊断。',
  '- 没有服务器 CPU、内存、GC、线程、数据库/Redis 连接、带宽监控，无法判断瓶颈位置。',
  '- 首页只测 1305 字节 HTML，没有模拟整页 JS/CSS/图片/音频下载或浏览器渲染。',
  '- 查询只涉及一个账号的第一页战绩，无法代表不同历史数据规模、详情查询或多用户混合流量。',
  '- 各接口分别测试，没有同时混合压测；30 秒档位属于短时样本，不足以证明长期稳定性。',
  '- 空闲连接测试没有覆盖四人对局、业务消息广播、结算、断线重连或 Redis 快照恢复。',
  '- HTTP 测试结束后的首页、登录和个人信息复核成功；自动停止后没有继续提高原场景压力。',
  health ? '- 最后的单次首页复核出现等待响应约 10 秒的现象；无法证明这是压测引发，也无法确认服务已恢复稳定，需要检查同期服务器和网络指标。' : '', '',
  '## 原始结果', '',
  '- [首页及首次升压](http-results.json)',
  '- [认证业务接口](authenticated/http-results.json)',
  ws ? '- [WebSocket](websocket-results.json)' : '',
  diagnostic ? '- [WebSocket 低负载连接复核](websocket-diagnostic.json)' : '',
  health ? '- [最终低频可用性检查](final-health.json)' : '',
  '- [HTTP 汇总 CSV](http-stages.csv)', '',
  '要得到可用于运营的容量结论，后续需要服务器监控、足够的独立测试账号、四人真实对局脚本，以及更长时间的混合负载与稳定性测试。', ''
];
fs.writeFileSync(path.join(root,'压测报告.md'),lines.join('\n'));
const cols=['name','route','targetRps','successRps','completed','failed','errorRate','p50Ms','p95Ms','p99Ms','peakInflight','skipped','elapsedSeconds','aborted','pass'];
const quote=v=>'"'+String(v??'').replace(/"/g,'""')+'"';
fs.writeFileSync(path.join(root,'http-stages.csv'),[cols.join(','),...stages.map(s=>cols.map(k=>quote(s[k])).join(','))].join('\n'));
console.log(JSON.stringify({report:path.join(root,'压测报告.md'),requests:stages.reduce((a,s)=>a+s.completed,0),
  failures:stages.reduce((a,s)=>a+s.failed,0),maxIdleConnections:maxWs?.openConnections ?? 0}));
