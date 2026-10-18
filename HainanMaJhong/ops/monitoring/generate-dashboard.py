"""Generate the importable Grafana capacity dashboard using bounded aggregate labels."""
import json
from pathlib import Path
panels = []
cursor = 0
column = 0
datasource = {'type': 'prometheus', 'uid': '${DS_PROMETHEUS}'}
def row(title):
    global cursor, column
    if column: cursor += 7
    column = 0
    panels.append({'id': len(panels)+1, 'type': 'row', 'title': title, 'collapsed': False,
                   'gridPos': {'x': 0, 'y': cursor, 'w': 24, 'h': 1}, 'panels': []})
    cursor += 1
def chart(title, queries, unit='short', description=''):
    global cursor, column
    panels.append({'id': len(panels)+1, 'type': 'timeseries', 'title': title, 'description': description,
        'datasource': datasource, 'gridPos': {'x': column * 12, 'y': cursor, 'w': 12, 'h': 7},
        'fieldConfig': {'defaults': {'unit': unit, 'min': 0}, 'overrides': []},
        'options': {'legend': {'displayMode': 'table', 'placement': 'bottom', 'calcs': ['lastNotNull']},
                    'tooltip': {'mode': 'multi', 'sort': 'desc'}},
        'targets': [{'refId': chr(65+i), 'expr': query, 'legendFormat': label, 'datasource': datasource}
                    for i, (label, query) in enumerate(queries)]})
    column = 1 - column
    if column == 0: cursor += 7
def m(name, extra=''):
    return name + '{job="hainan-mahjong"' + (',' + extra if extra else '') + '}'
def rate(name, extra=''): return 'rate(' + m(name, extra) + '[2m])'
def quantiles(name, labels=''):
    group = 'le' + (','+labels if labels else '')
    return [(f'P{int(q*100)} ' + ('{{'+labels+'}}' if labels else ''),
             f'histogram_quantile({q}, sum by ({group}) ({rate(name + "_bucket")}))') for q in [.5,.95,.99]]

row('1 · 业务容量')
chart('活跃、在线、空闲及占座人数', [(label, m('mahjong_players_'+key)) for label,key in
    [('活跃真人','active'),('在线真人','online'),('空闲真人','idle'),('真人占座','occupied')]],
    description='每 10 秒采样近似值。活跃指正在对局、非托管且游戏连接在线的真人；连接数另列。')
chart('进行中、等待、局间房间', [(key,m('mahjong_rooms_'+key)) for key in ['playing','waiting','between']])
chart('动作接收与已提交结算 / 秒', [('动作 {{action}}',rate('mahjong_game_actions_received_total')),
    ('结算提交',rate('mahjong_game_rounds_committed_total'))], 'ops', '动作是收到的指令，并不代表引擎接受。结算只在事务提交后计数，重复结算不重复计入。')
chart('结束、结算与快照失败 / 秒', [('事务 {{operation}}',rate('mahjong_db_transaction_seconds_count','outcome="error"')),
    ('快照 {{operation}}',rate('mahjong_room_snapshot_seconds_count','outcome="error"')),
    ('快照被拒 {{operation}}',rate('mahjong_room_snapshot_seconds_count','outcome="rejected"'))], 'ops')
chart('场次结束写入失败', [('关闭失败/秒',rate('mahjong_room_session_close_failures_total')),
    ('关闭失败累计',m('mahjong_room_session_close_failures_total'))], 'ops',
    '收口时幂等重试 3 次仍失败才自增；启动即注册为 0，因此 >0 就代表真有场次没写结束时间。对应告警 MahjongSessionCloseFailed，需按日志里的 room/session/generation 人工核对。')
row('2 · HTTP 接口')
chart('各接口请求率', [('{{method}} {{route}} {{outcome}}',rate('mahjong_http_response_seconds_count'))], 'reqps')
chart('HTTP 服务端完整处理 P50/P95/P99', quantiles('mahjong_http_response_seconds','route'), 's',
    '服务端处理到响应写入完成；包含业务码失败，标签使用路由模板。不能替代客户端完整接收耗时。')
chart('HTTP 首次写入 P50/P95/P99', quantiles('mahjong_http_first_byte_seconds','route'), 's',
    '首次写入应用响应流；并非经公网到达客户端的 TTFB。没有正文时采用服务端处理耗时。')
chart('HTTP 真失败与主动拒绝比例', [('真失败比例', 'sum('+rate('mahjong_http_response_seconds_count','outcome!~"success|rate_limited"')+') / sum('+rate('mahjong_http_response_seconds_count')+')'),
    ('主动拒绝(429)', 'sum('+rate('mahjong_http_response_seconds_count','outcome="rate_limited"')+') / sum('+rate('mahjong_http_response_seconds_count')+')')], 'percentunit',
    '真失败＝既非 success 也非 rate_limited，与告警 MahjongHttpErrors 同口径，包含 HTTP 错误与 HTTP 200 中 code!=200 的业务失败。主动拒绝是准入保护削峰，与应用故障无关，单列一条。')
chart('各接口平均响应正文大小', [('{{route}}', 'sum by(route)('+rate('mahjong_http_response_body_bytes_sum')+') / sum by(route)('+rate('mahjong_http_response_body_bytes_count')+')')], 'bytes', 'UTF-8 应用正文，Nginx 压缩前，不包括响应头。')
chart('准入拒绝率（按 group）', [('{{group}} 拒绝率', 'sum by (group) ('+rate('mahjong_admission_requests_total','outcome!="admitted"')+') / sum by (group) ('+rate('mahjong_admission_requests_total')+')')], 'percentunit',
    '分母是该组的全部准入判定。某组拒绝率 >5% 且流量 >1 次/秒持续 2 分钟触发 MahjongAdmissionRejecting。拒绝不等于故障：它是用"少收"换"已收的延迟"，判读时必须和第 2 行各接口 P95 一起看。')
chart('准入在途并发与限流键', [('在途 {{group}}',m('mahjong_admission_inflight')),('限流键 {{group}}/{{scope}}',m('mahjong_admission_keys'))], 'short',
    '在途＝已通过闸门但尚未释放的请求数；顶到额度（登录 1、战绩 48、legacy 8）说明该组并发已打满。限流键数与每分钟键膨胀相关，上限 TRAFFIC_MAX_KEYS=10000。')
row('3 · WebSocket')
chart('房间、游戏连接与断线率', [('连接 {{endpoint}}',m('mahjong_ws_connections')),('断线/秒 {{endpoint}}',rate('mahjong_ws_closed_total'))])
chart('握手 P50/P95/P99', quantiles('mahjong_ws_handshake_seconds','endpoint'), 's')
chart('发送等待与关键广播未确认数', [('发送中/等锁',m('mahjong_ws_send_pending')),('广播待确认',m('mahjong_ws_delivery_pending'))],
    description='当前发送采用同步写入。pending 包含正在发送及等待 session 锁的调用，不是底层 TCP 发送队列长度。')
chart('发送完成 P50/P95/P99', quantiles('mahjong_ws_send_seconds'), 's', '包含序列化、session 锁等待和 sendMessage；完成不代表客户端收到。')
chart('四名真人关键广播确认 P50/P95/P99', quantiles('mahjong_ws_delivery_confirmation_seconds','event'), 's',
    '只有四名在线真人且客户端支持 ACK 才产生数据。从广播到最后一人处理消息并 ACK 的往返耗时，是送达耗时上界，含网络回程和客户端调度，不代表画面渲染。')
chart('确认超时、跟踪丢弃与发送错误', [('ACK 超时 {{event}}',rate('mahjong_ws_delivery_expired_total')),
    ('跟踪容量满',rate('mahjong_ws_delivery_dropped_total')),('发送失败',rate('mahjong_ws_send_seconds_count','outcome="error"'))], 'ops', '确认跟踪最多 2048 个广播，15 秒过期；旧客户端无 ACK 会显示超时。')
row('4 · JVM 与执行队列')
chart('堆与非堆使用', [('{{area}} {{id}}',m('jvm_memory_used_bytes'))], 'bytes')
chart('GC 暂停 P95、累计暂停占比', [('GC P95', 'histogram_quantile(0.95, sum by(le)('+rate('jvm_gc_pause_seconds_bucket')+'))'),
    ('暂停秒/秒', 'sum('+rate('jvm_gc_pause_seconds_sum')+')')], 's')
chart('线程、阻塞与定时任务', [(key,m(name)) for key,name in [('线程总数','jvm_threads_live_threads'),
    ('阻塞线程','mahjong_jvm_threads_blocked'),('引擎线程','mahjong_engine_threads'),('定时器线程','mahjong_engine_timer_threads'),('定时任务等待','mahjong_engine_timer_pending')]])
chart('进程 CPU、打开文件描述符', [('进程 CPU',m('process_cpu_usage')),('FD',m('process_files_open_files')),('FD 上限',m('process_files_max_files'))])
chart('Tomcat 线程池饱和', [('忙线程',m('tomcat_threads_busy_threads')),('当前线程',m('tomcat_threads_current_threads')),('上限',m('tomcat_threads_config_max_threads'))], 'short',
    '忙线程顶到上限（线上为 200）＝请求已在排队，是"延迟爆炸"的前兆；此时准入拒绝通常已救不回已接纳请求的延迟。accept 队列长度 Tomcat 不暴露，队列积压只能从客户端延迟倒推。')
row('5 · MySQL 与 Redis')
chart('SQL 调用 P50/P95/P99', quantiles('mahjong_db_query_seconds','operation'), 's', 'Mapper 方法耗时包含获取连接、执行和映射；不采集 SQL 参数。定位慢 SQL 仍需受控 slow log 与 EXPLAIN。')
chart('结算及关闭事务 P50/P95/P99', quantiles('mahjong_db_transaction_seconds','operation'), 's', '环绕事务代理，包括提交和锁等待。')
chart('Hikari 活跃、空闲及等待连接', [('{{pool}} '+key,m('hikaricp_connections_'+key)) for key in ['active','idle','pending','max']])
chart('连接获取 P50/P95/P99', quantiles('hikaricp_connections_acquire_seconds'), 's')
chart('MySQL 慢查询及行锁等待增长', [(key,rate('mahjong_mysql_'+key)) for key in ['slow_queries','innodb_row_lock_waits','innodb_row_lock_time']],
    description='服务器全局累计指标，包含其他 MySQL 客户端；行锁 time 单位毫秒。MySQL 探测失效时看 probe.up，旧值不能当实时值。')
chart('Redis/快照访问 P50/P95/P99', quantiles('mahjong_room_snapshot_seconds','operation'), 's', '快照仓储端到端耗时；包含 Redis 请求及返回。')
chart('Redis 内存', [('Redis used memory',m('mahjong_redis_memory_used'))], 'bytes')
chart('Redis 命中率与过期、淘汰', [('命中率',rate('mahjong_redis_hits')+' / ('+rate('mahjong_redis_hits')+' + '+rate('mahjong_redis_misses')+')'),
    ('过期/秒',rate('mahjong_redis_expired')),('淘汰/秒',rate('mahjong_redis_evicted'))], description='INFO 全局统计；与快照写入拒绝分开统计。')
chart('采集与外部探测状态', [('应用采集',m('up')),('Redis',m('mahjong_redis_probe_up')),('MySQL',m('mahjong_mysql_probe_up'))], description='0 表示失败或 MySQL 池无空闲连接而跳过。没有指标表示未启用/尚未采到，不填成 0。')
row('6 · 主机与带宽')
chart('主机 CPU 使用率', [('CPU','1 - '+rate('mahjong_host_cpu_ticks_idle')+' / '+rate('mahjong_host_cpu_ticks_total'))], 'percentunit')
chart('主机可用内存', [('MemAvailable',m('mahjong_host_memory_available')),('MemTotal',m('mahjong_host_memory_total'))], 'bytes')
chart('网卡出入流量与出网带宽占比', [('出网 bit/s',rate('mahjong_host_network_transmit_bytes')+' * 8'),
    ('入网 bit/s',rate('mahjong_host_network_receive_bytes')+' * 8'),('出网限制 bit/s','vector($outbound_bps)')], 'bps',
    '配置所选网卡累计字节。限制线取面板变量 outbound_bps，当前 20000000 = 20Mbps（2026-10-14 由 10Mbps 升级）；变量存在 Grafana 库里，改仓库 JSON 不会自动更新。告警阈值为 15000000 bit/s（20Mbps 的 75%）。不是云厂商计费或限速遥测。')
chart('TCP 重传与磁盘 I/O', [('TCP 重传/秒',rate('mahjong_host_tcp_retransmits')),('磁盘读 bytes/s',rate('mahjong_host_disk_read_bytes')),
    ('磁盘写 bytes/s',rate('mahjong_host_disk_write_bytes')),('磁盘忙碌 ms/s',rate('mahjong_host_disk_io_milliseconds'))])
chart('主机采集可用与采样滞后', [('主机可用',m('mahjong_host_available')),('网卡可用',m('mahjong_host_network_available')),
    ('采样滞后秒','time() - '+m('mahjong_capacity_sample_timestamp'))])
if column: cursor += 7
panels.append({'id':len(panels)+1,'type':'text','title':'测量口径与待补证据','gridPos':{'x':0,'y':cursor,'w':24,'h':5},
    'options': {'mode':'markdown','content':'客户端 HTTP TTFB / 完整接收请使用受控测试记录；不能由服务端写入计时代替。\n\n云带宽限速/丢弃须接入云平台遥测；当前未取得数据，不能据此判为 0。慢 SQL / EXPLAIN 和 MySQL 活跃事务明细需要故障时按需采集，避免定时扫描明细。\n\n新指标无请求时显示 No data；成功样本分位数须和失败率及 ACK 超时一起看。基线记录见容量监控说明文档。'}})
dashboard = {'uid':'hainan-capacity','title':'海南麻将 · 容量监控','schemaVersion':38,'version':1,'editable':True,
    'timezone':'browser','refresh':'10s','time':{'from':'now-30m','to':'now'},'tags':['mahjong','capacity'],
    '__inputs':[{'name':'DS_PROMETHEUS','label':'Prometheus','type':'datasource','pluginId':'prometheus','pluginName':'Prometheus'}],
    'templating':{'list':[{'name':'outbound_bps','label':'出网带宽上限 bit/s','type':'constant','query':'20000000','current':{'text':'20000000','value':'20000000'},'hide':0}]},
    'panels':panels}
Path(__file__).with_name('capacity-dashboard.json').write_text(json.dumps(dashboard,ensure_ascii=False,indent=2),encoding='utf-8')
print('Generated', len(panels), 'panels')
