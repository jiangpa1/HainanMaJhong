"""Generate the two-day comparison from retained logs and measured JSON.
No production writes or benchmark runs. ReportLab vector charts, Chinese font.
"""
import csv, hashlib, json, math, re
from datetime import datetime, timezone, timedelta
from pathlib import Path
from xml.sax.saxutils import escape
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_CENTER
from reportlab.platypus import SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak
from reportlab.graphics.shapes import Drawing, String, Rect
from reportlab.graphics.charts.lineplots import LinePlot
from reportlab.graphics.charts.axes import LogXValueAxis, LogYValueAxis
from reportlab.graphics.charts.barcharts import HorizontalBarChart
from reportlab.graphics import renderSVG

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'HainanMaJhong'
EVIDENCE = ROOT / 'docs/performance/results-2026-10-11-summary'
OUT = ROOT / 'output/pdf'
OUT.mkdir(parents=True, exist_ok=True)
ASSETS = EVIDENCE / 'charts'
ASSETS.mkdir(exist_ok=True)
font_path = Path('C:/Windows/Fonts/simhei.ttf')
pdfmetrics.registerFont(TTFont('CN', str(font_path)))
pdfmetrics.registerFont(TTFont('CN-Bold', str(font_path)))
BLUE = colors.HexColor('#176B8B')
TEAL = colors.HexColor('#12806A')
GRAY = colors.HexColor('#667581')
PALE = colors.HexColor('#EFF4F7')
WIDTH = 487


def read(path):
    return path.read_text(encoding='utf-8-sig', errors='replace')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def total(path):
    matches = re.findall(r'Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)', read(path))
    assert matches
    count, fail, err, skip = map(int, matches[-1])
    assert fail == err == skip == 0, path
    return count


sql_source = MODULE / 'records-v2-package.log'
matches = re.findall(r'\[V2 SQL\] rounds=(\d+) listBytes=(\d+) overviewBytes=(\d+) firstPageBytes=(\d+) legacyBytes=(\d+)', read(sql_source))
assert len(matches) == 3
sql = []
for row in matches:
    n, li, overview, first, legacy = map(int, row)
    current = overview + first
    sql.append(dict(rounds=n, listBytes=li, overviewBytes=overview, firstPageBytes=first,
                    legacyBytes=legacy, firstScreenBytes=current,
                    savedPercent=round(100 * (1 - current / legacy), 4)))
assert [r['rounds'] for r in sql] == [10, 100, 1000]
gzip_source = ROOT / 'docs/performance/results-2026-10-11-gzip/gzip-results.json'
gz = json.loads(read(gzip_source))
levels = gz['levels']
assert [r['level'] for r in levels] == [0, 1, 2, 3]
g1 = {r['route']: r for r in levels[1]['cases']}
gzip_cases = [g1[route] for route in ['/api/v2/records/42/rounds', '/api/v2/records/42/rounds/1', '/api/records/42']]
logs = [MODULE / 'capacity-build.log', sql_source, MODULE / 'records-4.2-regression.log']
counts = [total(p) for p in logs]
assert counts == [69, 82, 79]
metrics_text = read(EVIDENCE / 'online-status.txt')
metrics = {name: float(value) for name, value in re.findall(r'^(\w+)\{[^\n]*\}\s+([\d.Ee+-]+)', metrics_text, re.M)}
live = read(EVIDENCE / 'online-config-check.txt')
assert 'Content-Encoding: gzip' in live and 'Cache-Control: private, no-store' in live
live_at = re.search(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\+08:00', live).group()
jar_source = MODULE / 'target/HainanMaJhong2-1.0-SNAPSHOT.jar'
local_hash = digest(jar_source)
online_hash = read(EVIDENCE / 'online-jar-sha256.txt').split()[0].lower()
assert local_hash == online_hash
check_source = EVIDENCE / 'frontend-refresh-check.txt'
assert '4.2 通过' in read(check_source)
data = dict(period=['2026-10-10', '2026-10-11'], timezone='Asia/Shanghai',
            sql=sql, gzip=levels, regressionCounts=counts,
            online=dict(at=live_at, metrics=metrics, recordGzipHeader=True, jarSha256=local_hash),
            sources=[dict(path=str(p.relative_to(ROOT)), sha256=digest(p)) for p in
                logs + [gzip_source, check_source, EVIDENCE/'online-status.txt', EVIDENCE/'online-config-check.txt', EVIDENCE/'online-jar-sha256.txt']])
(EVIDENCE / 'comparison-data.json').write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
with (EVIDENCE / 'record-size-comparison.csv').open('w', encoding='utf-8-sig', newline='') as f:
    writer = csv.DictWriter(f, fieldnames=list(sql[0])); writer.writeheader(); writer.writerows(sql)
with (EVIDENCE / 'gzip-level-comparison.csv').open('w', encoding='utf-8-sig', newline='') as f:
    writer = csv.writer(f); writer.writerow(['level','firstPageBodyBytes','sampleBodyBytes','p95Ms','workerCpuSeconds','loopbackTxBytes','eth0TxBytes'])
    for r in levels:
        case = next(c for c in r['cases'] if c['route'].endswith('/rounds'))
        writer.writerow([r['level'],case['bodyBytes'],r['sampleBodyBytes'],r['p95Ms'],r['nginxWorkerCpuSeconds'],r['interfaceTxBytes'].get('lo'),r['interfaceTxBytes'].get('eth0')])

styles = {
    'title': ParagraphStyle('title', fontName='CN', fontSize=25, leading=32, spaceAfter=12),
    'subtitle': ParagraphStyle('subtitle', fontName='CN', fontSize=11, leading=17, textColor=GRAY, spaceAfter=18),
    'h1': ParagraphStyle('h1', fontName='CN', fontSize=17, leading=24, spaceAfter=14),
    'h2': ParagraphStyle('h2', fontName='CN', fontSize=12, leading=18, spaceBefore=11, spaceAfter=7),
    'body': ParagraphStyle('body', fontName='CN', fontSize=10, leading=16.5, spaceAfter=8),
    'small': ParagraphStyle('small', fontName='CN', fontSize=8.5, leading=13, textColor=GRAY, spaceAfter=8),
    'cell': ParagraphStyle('cell', fontName='CN', fontSize=8.7, leading=13),
    'header': ParagraphStyle('header', fontName='CN', fontSize=9, leading=13, textColor=colors.white),
}


def p(text, style='body'):
    return Paragraph(escape(str(text)).replace('\n','<br/>'), styles[style])


def table(rows, widths):
    cells = [[p(v, 'header' if n == 0 else 'cell') for v in row] for n,row in enumerate(rows)]
    result = Table(cells, colWidths=widths, repeatRows=1, hAlign='LEFT')
    result.setStyle(TableStyle([
        ('BACKGROUND',(0,0),(-1,0),BLUE),('ROWBACKGROUNDS',(0,1),(-1,-1),[colors.white, PALE]),
        ('GRID',(0,0),(-1,-1),.45,colors.HexColor('#D9D9D9')),
        ('VALIGN',(0,0),(-1,-1),'MIDDLE'), ('TOPPADDING',(0,0),(-1,-1),8),
        ('BOTTOMPADDING',(0,0),(-1,-1),8),('LEFTPADDING',(0,0),(-1,-1),9),('RIGHTPADDING',(0,0),(-1,-1),9)]))
    return result


def legend(d, x, y, color, label):
    d.add(Rect(x,y,12,4,fillColor=color,strokeColor=color))
    d.add(String(x+18,y-2,label,fontName='CN',fontSize=9,fillColor=GRAY))


def history_chart():
    d = Drawing(WIDTH,245)
    chart = LinePlot(); chart.x=57; chart.y=43; chart.width=WIDTH-88; chart.height=167
    chart.xValueAxis = LogXValueAxis(); chart.yValueAxis = LogYValueAxis()
    chart.data = [[(r['rounds'],r['legacyBytes']) for r in sql],[(r['rounds'],r['firstScreenBytes']) for r in sql]]
    for axis in [chart.xValueAxis,chart.yValueAxis]:
        axis.labels.fontName='CN'; axis.labels.fontSize=8.5; axis.strokeColor=colors.HexColor('#CBD5DC')
    chart.xValueAxis.valueMin=10; chart.xValueAxis.valueMax=1000; chart.xValueAxis.valueSteps=[10,100,1000]
    chart.xValueAxis.labelTextFormat='%g'
    chart.yValueAxis.valueMin=1000; chart.yValueAxis.valueMax=1_000_000
    chart.yValueAxis.valueSteps=[1000,10000,100000,1000000]
    chart.yValueAxis.labelTextFormat=lambda v: {1000:'1,000',10000:'10,000',100000:'100,000',1000000:'1,000,000'}.get(v,str(v))
    chart.yValueAxis.visibleGrid=True; chart.yValueAxis.gridStrokeColor=colors.HexColor('#E6ECF0')
    chart.lines[0].strokeColor=GRAY; chart.lines[1].strokeColor=TEAL
    chart.lines[0].strokeWidth=2.4; chart.lines[1].strokeWidth=2.4
    d.add(chart)
    legend(d,57,230,GRAY,'旧版整场详情'); legend(d,235,230,TEAL,'新版首屏 总览加10局摘要')
    d.add(String(57,215,'纵轴 JSON 字节 B  横纵轴均为对数坐标',fontName='CN',fontSize=8,fillColor=GRAY))
    d.add(String(WIDTH/2,12,'会话总小局数',textAnchor='middle',fontName='CN',fontSize=9,fillColor=GRAY))
    renderSVG.drawToFile(d,str(ASSETS/'history-size.svg'))
    return d


def compression_chart():
    d = Drawing(WIDTH,190)
    chart = HorizontalBarChart(); chart.x=104; chart.y=32; chart.width=WIDTH-137; chart.height=125
    chart.categoryAxis.categoryNames=['10局摘要','单局详情','旧版1000局']
    chart.categoryAxis.labels.fontName='CN'; chart.categoryAxis.labels.fontSize=9
    chart.data=[[100]*3, [100*c['bodyBytes']/c['originalBytes'] for c in gzip_cases]]
    chart.valueAxis.valueMin=0; chart.valueAxis.valueMax=100; chart.valueAxis.valueStep=20
    chart.valueAxis.labels.fontName='CN'; chart.valueAxis.labels.fontSize=8.5; chart.valueAxis.labelTextFormat='%d%%'
    chart.valueAxis.visibleGrid=True; chart.valueAxis.gridStrokeColor=colors.HexColor('#E6ECF0')
    chart.bars[0].fillColor=GRAY; chart.bars[1].fillColor=TEAL
    chart.bars.strokeColor=None
    d.add(chart)
    legend(d,104,178,GRAY,'identity 原始大小100%'); legend(d,295,178,TEAL,'gzip级别1')
    d.add(String(104,9,'横轴 每份样本相对自身原始大小的比例',fontName='CN',fontSize=8.5,fillColor=GRAY))
    renderSVG.drawToFile(d,str(ASSETS/'gzip-relative-size.svg'))
    return d


story=[]
def add(text, style='body'): story.append(p(text,style))
def space(n=10): story.append(Spacer(1,n))
def page(title): story.append(PageBreak()); add(title,'h1')

add('海南麻将两日优化前后对比','title')
add('2026年10月10日至11日  |  实测证据与上线状态','subtitle')
add('两天的改造已解决房间关闭后的迟到写入风险，建立容量监控，并减少战绩首次读取及重复请求。实测最明显的收益是长历史战绩首屏字节数下降，以及 JSON 压缩后的传输正文缩小。')
add('线上复核时间 '+live_at.replace('T',' ').replace('+08:00',' 北京时间')+'。战绩路由实际返回 gzip 响应头，监控可读，线上 JAR 文件与今天的优化产物 SHA256 一致。复核没有登录或发起容量压测，不能据此宣称同时在线人数或牌桌上限提高。 [S6-S8]')
add('已经测得的变化','h2')
story.append(table([
    ['对比场景','优化前','优化后','可支持的结论'],
    ['1000局战绩首次读取','860,326 B 整场详情','6,319 B 总览加10局摘要','业务对象 JSON 少99.27% [S2]'],
    ['gzip 测试的10局摘要','5,751 B identity','585 B 级别1','该合成样本正文少89.83% [S4]'],
    ['并发点击同一总览','原逻辑没有统一请求合并','2次并发调用只发1次 HTTP','组件测试通过；未测生产节省率 [S5]'],
], [115,115,125,132]))
add('两日实施范围','h2')
story.append(table([
    ['日期','阶段','改动与验证口径'],
    ['10月10日','3.1 房间生命周期','关闭终态、代际和版本保护、Redis CAS、重复结算防护；含真实 Redis 并发回归。'],
    ['10月10日','3.2 容量监控','HTTP、WS、JVM、数据库、Redis和主机指标；上线后可采集，尚无同负载前后时间序列。'],
    ['10月11日','4.1 战绩 V2','列表、总览、固定截止点分页和单局详情；MySQL 8.4 临时表实测10/100/1000局。'],
    ['10月11日','4.2 前端加载','首屏按需读取、请求合并、有界缓存、隐藏暂停和有限重试；真实 Vue 组件加模拟接口验证。'],
    ['10月11日','4.3 JSON 压缩','独立 Nginx 对比级别1/2/3；线上路由已观察到 gzip，但真实账号正文收益尚待验收。'],
], [67,112,308]))
space(); add('图表中的“真实数据”指实际执行测试得到的数值。数据库和 gzip 样本均为构造数据，不是线上真实玩家数据；线上只读现状单独列示。','small')

page('战绩首屏大小随历史增长的变化')
add('同一组固定四人小局数据，在本机 MySQL 8.4 临时表执行真实 Mapper，并调用新旧服务序列化。新版首屏计入“总览加第一页10局摘要”，不只计算摘要，便于比较实际打开详情的读取量。 [S2]')
story.append(history_chart())
story.append(table([['总局数','旧整场 B','新总览 B','新摘要 B','新首屏 B','减少'],
    *[[f"{r['rounds']:,}",f"{r['legacyBytes']:,}",str(r['overviewBytes']),f"{r['firstPageBytes']:,}",f"{r['firstScreenBytes']:,}",f"{r['savedPercent']:.2f}%"] for r in sql]],
    [63,92,81,81,85,85]))
space()
add('历史从10局增加到1000局时，旧响应从9,004 B增加到860,326 B；新版首屏仅从6,296 B增加到6,319 B。第一页摘要从5,589 B变为5,592 B，变化来自字段数字位数。')
add('测量边界','h2')
add('这些是序列化业务对象的 UTF-8 JSON 字节，不含 Result 外壳、HTTP头、TLS或 gzip。新版首屏是两个请求，旧版是一份整场详情；详情展开、继续翻页和网络往返另计。读完全部历史并不等于只传6,319 B。')
add('缓存冷读仍可能扫描会话历史，未完成持久化汇总及生产规模执行计划优化。此次没有测得数据库 CPU、查询耗时或用户打开页面的完整耗时变化，不能由字节数推导这些收益。')
add('公式 新首屏字节 = 总览字节 + 第一页字节；减少比例 = 1 - 新首屏字节 / 旧整场字节。数据取14:14最终完整回归日志中的三条 V2 SQL 记录。','small')

page('HTTP JSON 压缩的字节与成本')
add('使用服务器上的 Nginx 1.18.0 独立进程，只监听 loopback；上游为按 V2 字段构造的 Python JSON 服务。每级别200次10局摘要请求，并发4，另外验证编码协商与逐字节解压一致性。 [S4]')
story.append(compression_chart())
story.append(table([['合成样本','原始 B','gzip级别1 B','减少'],
    *[[label,f"{case['originalBytes']:,}",f"{case['bodyBytes']:,}",f"{case['savedPercent']:.2f}%"] for label,case in zip(['10局摘要','单局详情','旧版1000局'],gzip_cases)]],
    [154,111,111,111]))
add('压缩级别对比','h2')
story.append(table([['级别','每份正文 B','200份正文 B','P95 ms','worker CPU s'],
    *[[str(r['level']) if r['level'] else '关闭', str(next(c for c in r['cases'] if c['route'].endswith('/rounds'))['bodyBytes']),
       f"{r['sampleBodyBytes']:,}", f"{r['p95Ms']:.3f}", f"{r['nginxWorkerCpuSeconds']:.3f}"] for r in levels]],
    [65,105,117,94,106]))
space()
add('级别2/3相比级别1，每份主路径响应仅再减少5/8 B，初始选级别1。CPU差异只有时钟 tick 粒度，不能认为某一级别更快；P95包含合成上游成本，不能与10月8日公网压测延迟横向比较。')
add('已知长度的小响应不压缩；41 B分块响应仍可压缩，说明1KB阈值依赖 Content-Length。缓存策略为 private, no-store。gzip不压缩 WebSocket 帧。')
add('这组摘要是5,751 B，和 MySQL 测试的5,592 B不同。不得将两组节省率相乘作为已测得的整体收益。网卡 loopback 计数还包含上游跳转，不能代表公网账单减少。','small')

page('前端请求与房间正确性')
add('前端基于真实挂载的 Vue 组件、Pinia 消息和模拟 fetch 验证。下表“改造前”描述代码行为，“改造后”来自断言；没有把预期请求节省伪装成生产测量。 [S5]')
story.append(table([
    ['场景','改造前','改造后实测'],
    ['同一总览并发调用','没有统一的同键在途请求池','2次同时调用仅1次HTTP请求。'],
    ['详情展开再收起再展开','再次请求详情','在测试中详情接口共1次请求，第二次展开命中缓存。'],
    ['局内弹窗打开','openModal和watch均调用load','快速点击两次，byRoom接口1次请求。旧行为的2路调用是代码证据。'],
    ['提交事件重复到达','缺少提交版本驱动的统一增量刷新','两个相同coins提交事件触发2次请求：一页增量加同截止点总览。'],
    ['隐藏后累计17局再恢复','没有对应的受控增量方案','隐藏期间0次刷新，恢复后2次请求，当前页最多10局，不追拉17局全部明细。'],
    ['临时失败','无统一退避任务管理','初次加4次重试，共5次；间隔0.5/1/2/4秒，耗尽停止。'],
    ['关闭或返回','旧请求可能晚到更新页面','关闭停止后续刷新；返回后旧详情不覆盖列表。'],
], [92,157,238]))
add('生命周期与缓存边界','h2')
add('真实 Redis 测试中的保存与关闭并发循环30轮，测试套件通过；关闭后快照不复活。代际/版本检查覆盖迟到写入、复用房间码和旧任务关闭；已经结算的小局不会被重复覆盖。没有保留旧程序同条件故障发生率，所以不绘制“泄漏减少百分比”。 [S3、S9]')
add('后端总览统计缓存最多512场、60秒；前端快照缓存最多128条、30秒，并按账号及会话版本区分。这些是实现上限，未测得真实命中率或内存下降。')
add('生命周期修复不等于任意重启无损：数据库已提交而快照落后时会拒绝恢复；持久关闭和数据库结束写入同时失败后重启，仍需依据日志核对修复。','small')
add('原前端没有被证实存在固定高频轮询，本报告不把“关闭轮询”作为优化收益。')

page('线上现状与尚未取得的容量对比')
add('只读复核 '+live_at.replace('T',' ').replace('+08:00',' 北京时间')+'。未登录的战绩路由返回 HTTP 200、Content-Encoding: gzip、Vary: Accept-Encoding、Cache-Control: private, no-store。未登录业务可能仍是401，因此只证明压缩链路生效，不证明私有战绩读成功。 [S6、S7]')
add('服务器 JAR 与本地4.2产物 SHA256 一致，文件大小55,017,821 B。磁盘文件校验不代替真实账号业务验收。 [S8]')
story.append(table([
    ['线上只读指标','采集值','解释'],
    ['主机CPU采样值',f"{metrics['system_cpu_usage']*100:.2f}%",'单点采样，非同负载前后对比。'],
    ['Java进程CPU采样值',f"{metrics['process_cpu_usage']*100:.2f}%",'单点采样，不能评价峰值余量。'],
    ['主机内存',f"{metrics['mahjong_host_memory_total']/1024**2:.2f} MiB总量\n{metrics['mahjong_host_memory_available']/1024**2:.2f} MiB可用",'不能由单点可用内存推导每桌消耗。'],
    ['JVM存活线程',str(int(metrics['jvm_threads_live_threads'])),'没有同桌数下的旧版本对照。'],
    ['活跃真人与进行中房间',f"{int(metrics['mahjong_players_active'])} 人 / {int(metrics['mahjong_rooms_playing'])} 桌",'当前为空闲样本，不是对局压力样本。'],
    ['主机与网卡采集可用',f"{int(metrics['mahjong_host_available'])} / {int(metrics['mahjong_host_network_available'])}",'采集可用；累计发送字节不能直接当成当前速率。'],
], [123,129,235]))
add('旧线上压测只能作为背景','h2')
add('10月8日公网战绩列表在目标5 RPS时：150个响应、0失败、P95约1,345 ms；目标10 RPS时：300个响应、1失败、P95约1,582 ms，未达当轮标准。这是同一账号的短时请求测试，未模拟四人对局。它早于本报告两天实施窗口。 [S10]')
add('目前没有相同账号、历史规模、网络路径和混合负载的优化后公网压测，也没有两天前后的主机CPU、GC、数据库读行数、网卡速率时间序列。因此不能写“5 RPS变成多少RPS”“支持多少在线玩家”或“牌桌容量提升几倍”。')
add('后续对比使用同一测试矩阵，同步采集成功率、客户端P95/P99、真实网卡发送速率、CPU、GC、SQL耗时和四人ACK，并覆盖长历史与持续对局。')

page('证据来源与数据核对')
add('本报告选择完整最终日志，不拼接不同版本的数字。所有关键数据和源文件 SHA256 已另存 comparison-data.json；两份 CSV 可直接用于后续复测对照。')
story.append(table([
    ['回归批次','实测总数','失败 错误 跳过','口径'],
    ['10月10日15:22监控构建','69','0 / 0 / 0','以capacity-build.log汇总为准。'],
    ['10月11日14:14 V2最终构建','82','0 / 0 / 0','79项常规加3项MySQL实库。'],
    ['10月11日14:41前端刷新改造回归','79','0 / 0 / 0','常规回归，未重复MySQL；不是测试减少失败。'],
], [162,58,94,173]))
space()
add('历史说明有两处口径差异：监控说明写70项，但保留构建日志为69项，本报告采用69；早期SQL日志的旧响应为8,944/86,260/860,266 B，最终完整构建为9,004/86,320/860,326 B，本报告统一使用最终值。3.1说明记录64项，但没有单独的原始运行日志用于本报告数字图表。')
add('来源索引','h2')
sources = [
    ('S1','HainanMaJhong/capacity-build.log','10月10日69项常规构建汇总'),
    ('S2','HainanMaJhong/records-v2-package.log','V2 SQL三组字节与82项回归'),
    ('S3','HainanMaJhong/records-4.2-regression.log','79项回归及Redis测试通过'),
    ('S4','docs/performance/results-2026-10-11-gzip/gzip-results.json','4级压缩、字节、CPU、延迟、NIC'),
    ('S5','frontend/tools/check-record-refresh.mjs；frontend-refresh-check.txt','前者在HainanMaJhong下；后者在报告证据目录'),
    ('S6','online-status.txt','线上监控值及nginx -T摘要'),
    ('S7','online-config-check.txt','实际响应头和服务器时间'),
    ('S8','online-jar-sha256.txt','线上JAR SHA256，与本地校验一致'),
    ('S9','HainanMaJhong/src/test/java/hainanMahjong/service/RoomLifecycleRedisTest.java','保存/关闭并发30轮测试源码'),
    ('S10','docs/performance/results-2026-10-08/压测报告.md','旧公网背景，不是两日容量复测'),
]
for num,path,description in sources:
    add(num+'  '+path+'\n'+description,'small')
add('未给出完整路径的 txt 均位于 docs/performance/results-2026-10-11-summary。原始日志可能包含应用运行信息，不直接嵌入分享版PDF；本报告只摘取不含账号、令牌或战绩内容的必要统计。','small')


def footer(canvas, doc):
    canvas.saveState(); canvas.setFont('CN',8); canvas.setFillColor(GRAY)
    canvas.drawString(54,30,'海南麻将优化对比  2026年10月10日至11日  |  以测试与来源口径为准')
    canvas.drawRightString(541,30,str(doc.page)); canvas.restoreState()


pdf_path = OUT / '海南麻将优化前后对比_2026-10-10至11.pdf'
SimpleDocTemplate(str(pdf_path), pagesize=(595.28,841.89), leftMargin=54,rightMargin=54,
                  topMargin=48,bottomMargin=48, title='海南麻将两日优化前后对比',author='项目优化记录').build(story,onFirstPage=footer,onLaterPages=footer)

# Editable counterpart retains the same source values and measurement boundaries.
md = ['# 海南麻将两日优化前后对比\n',
      '范围：2026年10月10日至11日，北京时间。来源为实际日志、构造样本测试和只读线上现状；没有优化后公网容量复测。\n',
      '## 结论\n',
      f"1000局旧整场业务对象为 {sql[-1]['legacyBytes']:,} B，新首屏总览加10局摘要为 {sql[-1]['firstScreenBytes']:,} B，少 {sql[-1]['savedPercent']:.2f}%。",
      '独立 Nginx 合成样本10局摘要从5,751 B压至585 B，少89.83%；与MySQL样本不同，不能将两组节省率直接相乘。',
      f'线上在 {live_at} 复核：战绩路由已返回gzip，监控可读，JAR SHA256与今天产物一致。未登录业务不能作为私有战绩验收。\n',
      '## 两日改动\n',
      '- 10月10日 3.1：关闭终态、房间代际/版本和Redis原子保护，迟到任务不能复活关闭快照；30轮并发场景位于真实Redis测试。',
      '- 10月10日 3.2：建立HTTP、WS、JVM、数据库、Redis和主机容量监控；提升的是可观测性，没有已测得的容量增幅。',
      '- 10月11日 4.1：按截止点分页读取战绩，默认每页10局，单局按需读取；冷统计仍可能扫描历史。',
      '- 10月11日 4.2：合并请求、有界缓存、提交后增量刷新、隐藏暂停、关闭清理和最多4次退避。',
      '- 10月11日 4.3：战绩JSON压缩、Vary/private/no-store、配置检查/备份/平滑重载/回退及双编码压测客户端。\n',
      '## 战绩首屏实测\n',
      '本机临时MySQL 8.4，四名固定玩家，10/100/1000局构造数据；真实Mapper及新旧业务对象UTF-8序列化，不含Result外壳、HTTP/TLS/gzip。新版首屏计总览加第一页。\n',
      '| 总局数 | 旧整场 B | 列表 B | 总览 B | 第一页 B | 新首屏 B | 减少 |',
      '| ---: | ---: | ---: | ---: | ---: | ---: | ---: |']
for r in sql:
    md.append(f"| {r['rounds']} | {r['legacyBytes']:,} | {r['listBytes']} | {r['overviewBytes']} | {r['firstPageBytes']:,} | {r['firstScreenBytes']:,} | {r['savedPercent']:.2f}% |")
md += ['\n![战绩大小曲线](../../docs/performance/results-2026-10-11-summary/charts/history-size.svg)\n',
       '图采用对数坐标。新版首屏需要两次请求，不含后续详情与翻页；读完全部历史不是只传6KB。字节数不能证明延迟、CPU或容量相同比例改善。\n',
       '## gzip实测\n',
       '服务器Nginx 1.18.0独立实例，loopback监听，Python合成上游；各级200请求，并发4。正文逐字节解压一致，校验gzip;q=0、Via/Authorization、Vary、最小长度及登录路径不进入战绩压缩范围。\n',
       '| 样本 | 原始 B | gzip级别1 B | 减少 |','| --- | ---: | ---: | ---: |']
for label,c in zip(['10局摘要','单局详情','旧版1000局'],gzip_cases):
    md.append(f"| {label} | {c['originalBytes']:,} | {c['bodyBytes']:,} | {c['savedPercent']:.2f}% |")
md += ['\n![gzip相对正文大小](../../docs/performance/results-2026-10-11-summary/charts/gzip-relative-size.svg)\n',
       '| 级别 | 摘要 B | 200份正文 B | P95 ms | worker CPU s | lo实际发送增量 B | eth0增量 B |',
       '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
for r in levels:
    case = next(c for c in r['cases'] if c['route'].endswith('/rounds'))
    md.append(f"| {r['level']} | {case['bodyBytes']} | {r['sampleBodyBytes']:,} | {r['p95Ms']:.3f} | {r['nginxWorkerCpuSeconds']:.3f} | {r['interfaceTxBytes'].get('lo',0):,} | {r['interfaceTxBytes'].get('eth0',0):,} |")
md += ['\n级别2/3仅比级别1再少5/8 B，先选1。CPU分辨率是Linux tick；P95包含Python上游，不能用来证明生产延迟收益。lo包括Nginx到上游的另一跳，eth0含其他流量，均不能当成公网战绩节省率。',
       '1KB阈值按Content-Length判断；41 B分块正文也被压缩。WebSocket不包含在HTTP gzip收益中。\n',
       '## 前端行为验证\n',
       '| 场景 | 优化后实测 |','| --- | --- |',
       '| 并发总览 | 同时调用2次，HTTP1次 |','| 详情展开收起再展开 | 详情HTTP1次 |',
       '| 两次相同提交通知 | 增量加同截止点总览共2次 |','| 隐藏期间累计17局 | 隐藏时0次请求，恢复时2次；当前页最多10局 |',
       '| 临时失败 | 初次加最多4次重试，总5次；间隔0.5/1/2/4秒 |','| 关闭与返回 | 后续任务停止，旧响应不能覆盖新页面 |',
       '\n环境为真实Vue挂载、Pinia事件及模拟fetch；旧弹窗openModal/watch两路load来自代码比较，不是生产流量抽样。没有证实旧前端有固定高频轮询，不计算“关闭轮询”收益。后端缓存512场/60秒、前端128条/30秒为设计上限，没有真实命中率数据。\n',
       '生命周期修复不等于任意重启无损：数据库已提交而快照落后时拒绝恢复；持久关闭与数据库结束写入同时失败后重启仍需核对日志。\n',
       '## 回归和数据核对\n',
       '| 批次 | 总数 | 失败/错误/跳过 |','| --- | ---: | --- |','| 10月10日15:22构建 | 69 | 0/0/0 |','| 10月11日14:14 V2完整构建 | 82 | 0/0/0 |','| 10月11日14:41常规回归 | 79 | 0/0/0 |',
       '\n82=79常规+3实库；79批次没有重复MySQL，不代表回归失败。监控说明70与保留日志69不一致，本报告用日志69。早期SQL日志旧响应少60 B，统一取最终完整日志9004/86320/860326 B。3.1说明记录64项，没有单独原始运行日志用于本报告图表。\n',
       '## 线上现状与容量边界\n',f'采集时间 {live_at}。gzip/Vary/private/no-store实际响应头已观察到；私有战绩未登录验收。线上JAR与本地SHA256一致：`{local_hash}`。',
       f"主机CPU采样 {metrics['system_cpu_usage']*100:.2f}%，进程CPU采样 {metrics['process_cpu_usage']*100:.2f}%，可用内存 {metrics['mahjong_host_memory_available']/1024**2:.2f} MiB / 总量 {metrics['mahjong_host_memory_total']/1024**2:.2f} MiB，JVM线程 {int(metrics['jvm_threads_live_threads'])}，活跃真人 {int(metrics['mahjong_players_active'])}，进行中房间 {int(metrics['mahjong_rooms_playing'])}。这是空闲单点，不是容量对照。",
       '10月8日旧公网战绩列表：目标5 RPS，150响应/0失败/P95约1345ms；目标10 RPS，300响应/1失败/P95约1582ms，未达标准。它早于两日实施窗口，且没有同条件优化后复测。',
       '不能得出在线玩家上限、牌桌倍数、生产P95或公网流量的改善比例。下一轮用相同账号/历史规模/负载/网络路径，采集客户端延迟、成功率、CPU/GC、SQL、实际网卡速率和四人ACK。\n',
       '## 来源\n']
for num,path,desc in sources: md.append(f'- [{num}] {path}：{desc}')
md += ['\n未写完整路径的证据txt位于 `docs/performance/results-2026-10-11-summary`。结构化数据及源文件哈希在 `comparison-data.json`；原始CSV可用于复测。',
       '\nPDF由 `docs/performance/build-optimization-comparison.py` 生成，分享版不嵌入账号、令牌或真实战绩。']
md_path = MODULE / 'docs/优化前后对比_2026-10-10至11.md'
md_path.write_text('\n'.join(md)+'\n', encoding='utf-8')
print(pdf_path)
print(md_path)
print('Source checks passed; report and CSV generated')
