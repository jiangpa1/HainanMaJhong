"""Read only the named smoke rooms; never print .env values or snapshot contents."""
import json, os, subprocess, sys, time
values = {}
for line in open('/root/mahjong/.env'):
    line = line.strip()
    if not line or line.startswith('#') or '=' not in line: continue
    key, value = line.split('=', 1); values[key.strip()] = value.strip().strip('\"\'')
env = dict(os.environ)
env['REDISCLI_AUTH'] = values.get('REDIS_PASSWORD', '')
env['MYSQL_PWD'] = values.get('DB_PASSWORD', '')
redis = ['redis-cli', '--raw', '-h', values.get('REDIS_HOST', '127.0.0.1'), '-p', values.get('REDIS_PORT', '6379'), '-n', values.get('REDIS_DB', '0')]
mysql = ['mysql', '-N', '-B', '-h', values.get('DB_HOST', '127.0.0.1'), '-P', values.get('DB_PORT', '3306'), '-u', values.get('DB_USERNAME', 'root'), values.get('DB_NAME', 'hainan_majong')]
def run(command): return subprocess.check_output(command, env=env, stderr=subprocess.DEVNULL, text=True).strip()
codes = sys.argv[1:]; assert codes and all(len(code) == 6 and code.isdigit() for code in codes)
report = []
for code in codes:
    closed = run(redis + ['HGET', 'room:lifecycle:' + code, 'closed'])
    exists = run(redis + ['EXISTS', 'room:' + code])
    ttl = run(redis + ['TTL', 'room:lifecycle:' + code])
    rows = run(mysql + ['-e', "SELECT id,status,end_time IS NOT NULL FROM game_sessions WHERE room_id='" + code + "' ORDER BY id DESC LIMIT 1"])
    report.append({'code': code, 'closed': closed, 'snapshotExists': exists, 'closureTtlSeconds': int(ttl), 'session': rows})
    assert closed == '1' and exists == '0', 'Closure storage verification failed'
    if rows: assert rows.split('\t')[1:] == ['2', '1'], 'MySQL session was not ended'
time.sleep(5)
for room in report:
    room['noLateSnapshot'] = run(redis + ['EXISTS', 'room:' + room['code']]) == '0'
    assert room['noLateSnapshot'], 'Late snapshot recreated'
print(json.dumps(report))
