"""Build a factual online comparison from retained raw reports and SSH samples."""
import json,csv,hashlib
from pathlib import Path
from datetime import datetime
from xml.sax.saxutils import escape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,Table,TableStyle,PageBreak
from reportlab.graphics.shapes import Drawing,String
from reportlab.graphics.charts.barcharts import HorizontalBarChart
from reportlab.graphics import renderSVG
ROOT=Path(__file__).resolve().parents[2]
E=ROOT/'docs/performance/results-2026-10-11-retest'
OUT=ROOT/'output/pdf';OUT.mkdir(exist_ok=True,parents=True)
pdfmetrics.registerFont(TTFont('CN','C:/Windows/Fonts/simhei.ttf'))
BLUE=colors.HexColor('#176B8B');TEAL=colors.HexColor('#12806A');RED=colors.HexColor('#B54B4B')
def load(p):return json.loads(p.read_text(encoding='utf-8-sig'))
def instant(s):return datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()
old=load(E/'baseline-2026-10-09.json')
samples=[json.loads(x) for x in (E/'server-metrics-direct.jsonl').read_text().splitlines()]
rows=[];preparations=[];checks=[];sources=[E/'baseline-2026-10-09.json',E/'baseline-192-raw.json',E/'baseline-256-raw.json',E/'server-metrics-direct.jsonl',E/'deployment-evidence.txt',E/'nginx-window-diagnostic.json']
for n in ['public-boundary','public-calibrated']:
 b=next(x for x in old if x['run']==n)
 rows.append(dict(name='10/09 '+str(b['players'])+'人',date='2026-10-09',players=b['players'],version='legacy',encoding='identity',seconds=b['seconds'],http=b['http'],failed=b['failed'],errorRate=b['errorRate'],httpP95=b['httpP95MaxMs'],wsP95=b['wsP95MaxMs'],tailP95=b['tailP95Ms'],tailErrorRate=b['tailErrorRate'],hands=b['hands'],cpuMean=b['cpuMean'],cpuMax=b['cpuMax'],netMbps=b['netMeanMbps'],memMin=b['memMinMiB'],monitorSamples=b['monitorSamples'],pass_=b['pass'],stable=b['stableReview'],cleanup=b['cleanupCount'],abort=b['abort'],source='baseline-2026-10-09.json'))
for p in sorted(E.glob('*/results.json')):
 r=load(p)
 if not r.get('finishedAt'):continue
 sources.append(p)
 if not r.get('stages'):preparations.append(dict(run=p.parent.name,reason=r.get('stopReason'),roomsClosed=len(r.get('cleanup',[]))))
 for s in r.get('stages',[]):
  if not s.get('windowStartedAt'):
   preparations.append(dict(run=p.parent.name,reason=r.get('stopReason'),roomsClosed=len(r.get('cleanup',[]))))
   continue
  if s['players']<192:
   checks.append(dict(run=p.parent.name,players=s['players'],seconds=s.get('emissionSeconds'),http=s['http']['completed'],failed=s['http']['failed'],pass_=s['pass'],abort=s.get('abort'),reconnect=s.get('reconnect')))
   continue
  start,end=instant(s['windowStartedAt']),instant(s['windowEndedAt'])
  ss=[x for x in samples if start<=instant(x['at'])<=end and x.get('ok')]
  h=s['http'];tail=h.get('lastRecentWindow',{});cl=r['cleanup']
  stable=bool(s['pass'] and tail.get('n',0)>=100 and tail.get('errorRate',1)<.01 and tail.get('p95Ms',99999)<3000)
  good=all(not x.get('error') and x.get('sessionEnded') and x.get('disbanded') and not x.get('remainingConnections') and all(not c['errors'] for c in x.get('recordChecks',[])+x.get('listChecks',[])) and all(c.get('code')==200 and c.get('data',{}).get('exists') is False for c in x.get('pending',[])) for x in cl)
  net=None
  if len(ss)>1:
   delta=ss[-1]['metrics']['mahjong_host_network_transmit_bytes']-ss[0]['metrics']['mahjong_host_network_transmit_bytes']
   net=delta*8/(instant(ss[-1]['at'])-instant(ss[0]['at']))/1e6
  rows.append(dict(name='10/11 '+r['recordsVersion']+' '+r['acceptEncoding']+' '+str(s['players'])+'人',date='2026-10-11',players=s['players'],version=r['recordsVersion'],encoding=r['acceptEncoding'],seconds=s['emissionSeconds'],http=h['completed'],failed=h['failed'],errorRate=h['errorRate'],httpP95=max(x['timing']['p95Ms'] for x in h['endpoints'].values()),wsP95=max(x['broadcast']['p95Ms'] for x in s['rooms']),tailP95=tail.get('p95Ms'),tailErrorRate=tail.get('errorRate'),hands=sum(x['settledHands'] for x in s['rooms']),cpuMean=sum(x['metrics']['system_cpu_usage'] for x in ss)/len(ss)*100 if ss else None,cpuMax=max(x['metrics']['system_cpu_usage'] for x in ss)*100 if ss else None,netMbps=net,memMin=min(x['metrics']['mahjong_host_memory_available'] for x in ss)/1024**2 if ss else None,monitorSamples=len(ss),pass_=s['pass'],stable=stable,cleanup=len(cl),cleanupOK=good,abort=s.get('abort'),source=str(p.relative_to(E)),bodyBytes=sum(x.get('bodyBytes',0) for x in h['endpoints'].values()),decodedBytes=sum(x.get('decodedBytes',0) for x in h['endpoints'].values()),endpoints=h['endpoints'],traffic=s.get('traffic'),startedAt=s['windowStartedAt'],endedAt=s['windowEndedAt']))
  rows[-1]['rawStopReason']=r.get('stopReason')
  rows[-1]['pendingReadFailures']=[dict(roomId=x['roomId'],userId=c['userId'],error=c.get('error')) for x in cl for c in x.get('pending',[]) if c.get('code')!=200 or c.get('data',{}).get('exists') is not False]
  rows[-1]['performancePass']=bool(not s.get('abort') and s['emissionSeconds']>=s['plannedSeconds'] and not h['skipped'] and h['errorRate']<.01 and all(ep['timing']['p95Ms']<3000 for ep in h['endpoints'].values()) and all(room['settledHands']>0 and not room['failures'] and not room['pendingDiscardDeliveries'] and room['broadcast']['p95Ms']<3000 for room in s['rooms']))
  if not s['pass'] and not rows[-1]['abort']:rows[-1]['abort']='收尾 pending 读取连接重置（2次）' if len(rows[-1]['pendingReadFailures'])==2 and rows[-1]['performancePass'] else r.get('stopReason') or 'stage-acceptance-failed'
sources += [ROOT/'docs/performance'/n for n in ['online-capacity-retest.mjs','run-capacity-retest.ps1','online-retest-ssh-monitor.mjs','online-retest-final-audit.mjs','http-json-body.mjs']]
sources += [ROOT/'HainanMaJhong/ops/inspect-lifecycle.py',E/'final-server-health.txt']
if (E/'lifecycle-audit.json').exists():sources.append(E/'lifecycle-audit.json')
supplement=load(E/'supplemental-pending-audit.json') if (E/'supplemental-pending-audit.json').exists() else None
if supplement:sources.append(E/'supplemental-pending-audit.json')
storage=load(E/'lifecycle-audit.json') if (E/'lifecycle-audit.json').exists() else []
if storage:assert all(x['closed']=='1' and x['snapshotExists']=='0' and x['noLateSnapshot'] for x in storage)
assert any(x['date']=='2026-10-11' for x in rows),'No completed formal run'
rows=rows[:2]+sorted(rows[2:],key=lambda x:x['startedAt'])
new=[x for x in rows if x['date']=='2026-10-11'];passed=[x for x in new if x['stable']]
highest=max((x['players'] for x in passed),default=0)
observations=[]
identity=next((r for r in new if r['players']==192 and r['encoding']=='identity' and r['stable']),None)
legacy_gzip=next((r for r in reversed(new) if r['players']==192 and r['version']=='legacy' and r['encoding']=='gzip' and r['stable']),None)
v2_192=next((r for r in new if r['players']==192 and r['version']=='v2' and r.get('performancePass')),None)
if legacy_gzip:
 saved=(1-legacy_gzip['bodyBytes']/legacy_gzip['decodedBytes'])*100
 observations.append(f"192 人原接口 gzip 重测：完整响应体压缩后 {legacy_gzip['bodyBytes']/1024**2:.2f} MiB，解码后 {legacy_gzip['decodedBytes']/1024**2:.2f} MiB，实际体积减少 {saved:.1f}%。")
if identity and legacy_gzip:
 observations.append(f"同日 192 人完整轮次，原接口无压缩出网 {identity['netMbps']:.2f} Mbps，gzip 重测 {legacy_gzip['netMbps']:.2f} Mbps，观测下降 {(1-legacy_gzip['netMbps']/identity['netMbps'])*100:.1f}%；两轮动态内容和网络状态不同，此差值不等于纯压缩收益。")
if v2_192:
 observations.append(f"192 人 V2 + gzip 完整轮次：出网 {v2_192['netMbps']:.2f} Mbps，最慢接口 P95 {v2_192['httpP95']:.1f} ms；HTTP {v2_192['http']} 次、失败 {v2_192['failed']} 次。")
for row in rows:
 if row['date']=='2026-10-11' and row['version']=='legacy' and row['encoding']=='gzip' and row['players']==192:row['name']+=' 复测' if 'retry' in row['source'] else ' 首测'
data=dict(rows=rows,preparationStops=preparations,basicAndRecoveryChecks=checks,highestStableTestedPlayers=highest,sources=[dict(path=str(p.relative_to(ROOT)),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sources],limitations=['5-minute observations do not establish all-day or maximum capacity.','External path latency differs materially from Oct 9 and changes game pace and resource use.','V2 endpoints return less content than legacy; accounts have accumulated more history.','Old host samples were /proc at 5s; new direct SSH /proc samples are ~5s plus an optional Actuator fetch.','Current clients send metricsAck; old clients did not.','Host egress includes all server traffic; HTTP body bytes exclude headers and WebSocket frames.','Settled hands include setup and drain, not only the formal 300-second window.'])
data['supplementalPendingAudit']=supplement
data['storageAuditCount']=len(storage)
(E/'online-comparison-data.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
columns=['name','players','seconds','http','failed','httpP95','wsP95','tailP95','tailErrorRate','cpuMean','netMbps','memMin','stable','abort']
with (E/'online-comparison.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=columns,extrasaction='ignore');w.writeheader();w.writerows(rows)
def num(n,d=1):return '-' if n is None else f'{n:,.{d}f}'
def result(r):return '通过' if r['stable'] else '未通过'
lines=['# 海南麻将线上复测对比报告','', '测试日期：2026-10-11；基线日期：2026-10-09。服务器：39.107.247.119。','',f'本次已测且通过完整验收的最高档位：{highest} 人。' if highest else '本次正式档位均未通过完整验收，不能据此提高线上容量上限。','', '| 档位 | 发压秒数 | HTTP 总数/失败 | 最慢接口 P95 ms | 最慢桌广播 P95 ms | 尾窗 P95 ms | CPU 均值 % | 出网 Mbps | 最低可用 MiB | 验收 |','|---|---:|---:|---:|---:|---:|---:|---:|---:|---|']
for r in rows:lines.append(f"| {r['name']} | {num(r['seconds'])} | {r['http']}/{r['failed']} | {num(r['httpP95'])} | {num(r['wsP95'])} | {num(r['tailP95'])} | {num(r['cpuMean'])} | {num(r['netMbps'],2)} | {num(r['memMin'])} | {result(r)} |")
lines+=['','## 直接观测','']+observations
if supplement:lines+=['','V2 192 人性能、游戏结算与分数检查达标，但两次收尾 pending GET 发生 ECONNRESET，因此原始整轮验收未通过。独立补充读取均确认 exists=false，不改写原结果。本轮未升级至 256 人。']
if storage:lines+=['',f'本轮 {len(storage)} 个测试房间均已通过 Redis/MySQL 存储审计：生命周期关闭、房间快照消失、MySQL 会话结束；等待五秒后也未出现晚到快照。']
lines+=['','## 验收及对比边界','','每桌四名不同测试账号，100-300 ms 操作思考时间，每桌 2.5 次 GET/秒。192 人为 48 桌、120 GET/秒；256 人为 64 桌、160 GET/秒。正式窗口目标 300 秒；登录、建房、结算收尾和解散另计。','', '通过要求：完整发压，无保护停止，无跳过请求，总失败率低于 1%，每个接口和每桌广播 P95 低于 3 秒，每桌至少一次真实结算、四人分数零和、最终战绩一致，连接关闭、会话结束和房间解散。另复核最后采集的 20 秒窗口至少 100 个响应、失败率低于 1%、P95 低于 3 秒。', '', '尾窗每 5 秒采集一次，表中是最后一次采集窗口，可能早于正式结束数秒。P95 取各接口/各桌 P95 的最大值，并非混合所有请求的 P95。','','旧 legacy 查询为两页战绩列表、房号全量战绩、会话全量战绩和 pending；V2 改为两页列表、overview、前 10 局 summaries 和 pending。频率相同，返回内容不同。gzip 字节统计是响应体压缩后/解码后的累积值，实际内容会随游戏增长，不是同一静态响应的逐字节对照。','','本次单桌公网查询已出现约 0.6-1.4 秒延迟，旧 192 人基线约 39 ms；无法把端到端延迟变化全部归因于代码。测试账号历史也比 10/09 更多，且各轮测试继续增加历史。客户端新增 metricsAck。新旧资源监控采样略有差异。出网是整机计数器差分，包含其他流量。结算局数包含准备和收尾，不能当作严格 300 秒吞吐量。']
for r in new:
 lines+=['',f"## {r['name']}",'',f"发压起止（UTC）：{r['startedAt']} 至 {r['endedAt']}。结果：{result(r)}；保护/异常原因：{r['abort'] or '无'}。清理：{r['cleanup']} 桌；业务与解散检查：{'通过' if r['cleanupOK'] else '失败，见原始记录'}。",'',f"HTTP 响应体：线上传输 {num(r['bodyBytes']/1024**2,2)} MiB，解码后 {num(r['decodedBytes']/1024**2,2)} MiB；压缩后占解码后 {num(r['bodyBytes']/max(1,r['decodedBytes'])*100)}%。监控样本 {r['monitorSamples']} 个。",'', '| 接口 | 总数 | 失败 | P50 ms | P95 ms | P99 ms | 传输 MiB | 解码 MiB |','|---|---:|---:|---:|---:|---:|---:|---:|']
 for name,ep in r['endpoints'].items():
  t=ep['timing'];lines.append(f"| {name} | {ep['sent']} | {ep['failed']} | {num(t.get('p50Ms'))} | {num(t.get('p95Ms'))} | {num(t.get('p99Ms'))} | {num(ep.get('bodyBytes',0)/1024**2,2)} | {num(ep.get('decodedBytes',0)/1024**2,2)} |")
lines+=['','## 准备阶段中止（不计入正式容量）','']+[f"- {p['run']}：{p['reason']}；已关闭 {p['roomsClosed']} 桌。" for p in preparations]
lines+=['','## 基础与恢复验证','']+[f"- {c['run']}：{c['players']} 人、{num(c['seconds'])} 秒，HTTP {c['http']} 次、失败 {c['failed']} 次；{'通过' if c['pass_'] else '未通过'}。" for c in checks]
lines+=['','## 短窗口异常的服务端证据','','legacy gzip 192 人首测仅发压约 8.7 秒，即因总失败率 >5% 保护停止。对应时间段的 Nginx 战绩请求上游 P95 为 2.208 秒，客户端各接口 P95 约 8 秒；服务端和端到端都出现了延迟。该窗口具体瓶颈尚未确认，不能把这一轮解释为 gzip 的稳定性能，也不能仅归因于公网。表格保留失败，资源对照图只包含完整 300 秒的 192 人轮次。']
lines+=['','## 证据','','原始数据与采样位于 docs/performance/results-2026-10-11-retest；online-comparison-data.json 提供汇总与来源 SHA256，online-comparison.csv 可用于重新绘图。未保存或展示密码、token 和私人手牌。','', '详细存储清理检查见 lifecycle-audit.json（若生成），只针对本次测试房间；该结果不代表全量生产房间扫描。']
lines+=['','## 指标口径补充','','HTTP 分位数取正式查询窗口；广播分位数取每桌从开局到关闭期间的样本，包含准备与收尾。查询窗口结束时间包含在途请求收齐，发压秒数单独列示。体积限完整收到的响应体，不计未收完的超时响应、HTTP 头和 WebSocket。','','本次使用真实线上接口与 WebSocket 的四人协议机器人，不测浏览器 DOM 渲染。固定每桌 2.5 GET/秒用于对照，未单独衡量前端 4.2 减少刷新次数的收益。P50 为中位数，P95/P99 表示相应百分比样本不超过该延迟。']
md=ROOT/'HainanMaJhong/docs/线上压测对比_2026-10-11.md';md.write_text('\n'.join(lines)+'\n',encoding='utf-8')
styles={n:ParagraphStyle(n,fontName='CN',fontSize=z,leading=z*1.55,textColor=colors.HexColor('#243746'),spaceAfter=9) for n,z in [('title',22),('h',15),('body',10),('small',8)]}
story=[]
def p(t,style='body'):story.append(Paragraph(escape(t),styles[style]))
def table(headers,body,widths):
 t=Table([[Paragraph(escape(str(c)),styles['small']) for c in row] for row in [headers]+body],colWidths=widths,repeatRows=1)
 t.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#E7F0F4')),('VALIGN',(0,0),(-1,-1),'TOP'),('GRID',(0,0),(-1,-1),.3,colors.HexColor('#D7E1E7')),('LEFTPADDING',(0,0),(-1,-1),6),('RIGHTPADDING',(0,0),(-1,-1),6)]));story.extend([t,Spacer(1,12)])
def chart(title,key,unit,selected):
 d=Drawing(487,85+len(selected)*24);d.add(String(0,d.height-15,title,fontName='CN',fontSize=12))
 b=HorizontalBarChart();b.x=145;b.y=30;b.width=270;b.height=len(selected)*24;b.data=[[r[key] or 0 for r in selected]];b.categoryAxis.categoryNames=[r['name'].replace('10/11 ','') for r in selected];b.categoryAxis.labels.fontName='CN';b.categoryAxis.labels.fontSize=8;b.valueAxis.labels.fontName='CN';b.valueAxis.labels.fontSize=8;b.valueAxis.valueMin=0;b.bars[0].fillColor=BLUE;b.barLabels.fontName='CN';b.barLabels.fontSize=8;b.barLabelFormat='%.1f';d.add(b);d.add(String(415,10,unit,fontName='CN',fontSize=8));(E/'charts').mkdir(exist_ok=True);renderSVG.drawToFile(d,str(E/'charts'/f'{key}.svg'));story.append(d)
p('海南麻将线上压测对比','title');p('2026-10-11 复测 / 2026-10-09 真实四人对局基线','h')
p(f'本次最高通过的已测档位为 {highest} 人。该结果仅表示本次五分钟条件下的观察，不等于最大容量或全天稳定容量。' if highest else '本次正式档位未通过完整验收，暂不应提高线上容量上限。')
if supplement:p('原接口 + gzip 的 192 人完整通过。V2 的 192 人性能达标，但两次收尾 pending 读取连接重置导致整轮未通过；补充读取已确认无遗留。本轮未升至 256 人。','small')
p('192 人 = 48 桌，每桌四名独立测试玩家，120 次查询/秒。256 人 = 64 桌、160 次查询/秒。服务器 39.107.247.119，原公网域名 Host，固定连接 IP。')
table(['档位','秒','HTTP/失败','HTTP P95','广播 P95','验收'],[[r['name'],num(r['seconds']),f"{r['http']}/{r['failed']}",num(r['httpP95']),num(r['wsP95']),result(r)] for r in rows],[155,43,82,75,75,57])
p('延迟单位 ms。HTTP P95 为最慢接口 P95；广播 P95 为最慢桌的四人弃牌广播 P95，广播样本包含准备和收尾。')
for observation in observations:p(observation,'small')
p('对比限制：本次单桌公网查询已有约 0.6-1.4 秒延迟，旧基线约 39 ms。端到端延迟变化不能全部解释为代码效果。网络往返也影响实际打牌节奏，CPU 和出网下降不等于纯代码收益。V2 返回内容更少，历史增长，客户端新增 metricsAck。')
story.append(PageBreak());p('同人数资源与延迟对照','title')
matched=[r for r in rows if r['players']==192 and r['seconds']>=299]
chart('最慢接口 P95','httpP95','ms',matched);chart('CPU 平均占用','cpuMean','%',matched);chart('整机平均出网','netMbps','Mbps',matched)
p('资源统计取正式查询窗口（含在途请求收尾）的 SSH /proc 样本。网络采用首末累计计数差分，含整机其他流量；旧基线为 5 秒采样，新样本约 5 秒另加可选监控抓取。不得将资源下降换算为最大人数提升。','small')
p('图中只纳入完整 300 秒的 192 人轮次。gzip 首测仅约 8.7 秒即保护停止，列于结果表，不与五分钟均值直接比较。','small')
story.append(PageBreak());p('验收、保护与业务正确性','title')
p('正式窗口要求完整 300 秒；总失败率 <1%，无跳过请求，各接口与各桌广播 P95 <3000 ms，每桌至少真实结算一局。最后一次采集的 20 秒窗口另要求至少 100 个响应、失败率 <1%、P95 <3000 ms。')
p('保护：请求超时 8 秒；45 秒无桌消息停止；连续两个 20 秒窗口失败率 >5% 或 P95 >5 秒停止；可用内存 <100 MiB、CPU 连续高于 90% 或资源监控失联停止。准备阶段触发保护也保留记录。')
table(['本次档位','尾窗 P95','尾窗失败 %','清理桌数','原因'],[[r['name'],num(r['tailP95']),num(r['tailErrorRate']*100 if r['tailErrorRate'] is not None else None,2),str(r['cleanup']),r['abort'] or '无'] for r in new],[155,65,65,55,147])
p('业务检查涵盖四名玩家身份、分数零和、WebSocket 结算与最终战绩一致、两页列表、会话结束、pending 清空、房间解散及连接关闭。存储侧另按本次房间检查 Redis 快照消失、生命周期关闭、MySQL 会话结束和五秒后无晚到快照。')
for x in checks:p(f"基础/恢复验证：{x['run']}，{x['players']} 人，{num(x['seconds'])} 秒，HTTP {x['http']}/{x['failed']} 次失败，{'通过' if x['pass_'] else '未通过'}。",'small')
for x in preparations:p(f"准备中止：{x['run']}，{x['reason']}；关闭 {x['roomsClosed']} 桌。",'small')
if supplement:p('V2 收尾的两次 pending GET 原始错误均为 ECONNRESET；独立补充读取均为 HTTP 200 / 业务 200 / exists=false。原始整轮验收仍为未通过。','small')
for r in new:
 story.append(PageBreak());p(r['name'],'title');p(f"发压 {num(r['seconds'])} 秒；HTTP {r['http']} 次，失败 {r['failed']} 次；验收 {result(r)}。")
 p(f"CPU 平均 {num(r['cpuMean'])}% / 最大 {num(r['cpuMax'])}%；最低可用内存 {num(r['memMin'])} MiB；出网 {num(r['netMbps'],2)} Mbps；资源样本 {r['monitorSamples']} 个。")
 table(['接口','总数/失败','P50 ms','P95 ms','P99 ms'],[[n,f"{ep['sent']}/{ep['failed']}",num(ep['timing'].get('p50Ms')),num(ep['timing'].get('p95Ms')),num(ep['timing'].get('p99Ms'))] for n,ep in r['endpoints'].items()],[155,80,84,84,84])
 table(['接口','线上体积 MiB','解码体积 MiB','响应编码'],[[n,num(ep.get('bodyBytes',0)/1024**2,2),num(ep.get('decodedBytes',0)/1024**2,2),str(ep.get('encodings',{}))] for n,ep in r['endpoints'].items()],[155,105,105,122])
 p('legacy：两页列表、房号全量战绩、会话全量战绩、pending。V2：两页列表、overview、前 10 局 summaries、pending。相同 GET 频率，但内容量不同。')
 p('体积限完整接收的响应体，不计未收完的失败响应、HTTP 头和 WebSocket。gzip 仅用于战绩 HTTP 接口。结算局数含准备和收尾，不是严格 300 秒吞吐。','small')
 p('证据：'+r['source'],'small');p('查询窗口（UTC，含在途请求收尾）：'+r['startedAt']+' 至 '+r['endedAt'],'small')
story.append(PageBreak());p('证据与使用建议','title')
p('本报告全部容量指标来自真实线上四人对局及已保留的旧基线，未用单接口请求数或本地合成测试替代在线玩家数。测试账号复用原 DPAPI 凭据，凭据文件只读。')
p('负载由四人协议机器人产生，未测浏览器 DOM。固定每桌 2.5 GET/秒用于对照，未单独衡量前端 4.2 减少刷新次数的收益。')
p('部署与 Nginx 设置未在本次测试中修改。容量结论仅适用于上述查询与游戏负载；提高正式接入上限前仍应进行更长时间、生产行为分布和故障恢复验证。')
p('原始结果、准备中止记录、资源逐点采样、CSV、图表及来源 SHA256：docs/performance/results-2026-10-11-retest。可编辑说明：HainanMaJhong/docs/线上压测对比_2026-10-11.md。')
p('gzip 192 人首测短窗：Nginx 对应时间段战绩上游 P95 为 2.208 秒，客户端 P95 约 8 秒。两侧均有延迟，具体瓶颈尚未确认；该失败保留，不能当作 gzip 的稳定性能结论。')
if storage:p(f'最终存储审计：{len(storage)} 个本轮测试房间全部关闭，Redis 快照不存在，MySQL 会话结束，等待五秒后未出现晚到快照。')
p('online-comparison-data.json 保存统一指标和来源校验值；各轮 results.json 保留接口分位数、失败样本、房间业务验收和清理证据；lifecycle-audit.json 保存存储检查。')
def footer(canvas,doc):
 canvas.setFont('CN',8);canvas.setFillColor(colors.HexColor('#667581'));canvas.drawString(54,30,'海南麻将 | 真实线上复测 | 2026-10-11');canvas.drawRightString(541,30,str(doc.page))
pdf=OUT/'海南麻将线上压测对比_2026-10-11.pdf'
SimpleDocTemplate(str(pdf),pagesize=(595,842),leftMargin=54,rightMargin=54,topMargin=46,bottomMargin=48).build(story,onFirstPage=footer,onLaterPages=footer)
print(json.dumps({'pdf':str(pdf),'markdown':str(md),'formalRuns':len(new),'highestStable':highest},ensure_ascii=False))
