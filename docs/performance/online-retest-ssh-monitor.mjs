import fs from 'node:fs';
import path from 'node:path';
import {spawn} from 'node:child_process';
const out=path.resolve('docs/performance/results-2026-10-11-retest');
const stop=path.join(out,'resource-stop.json');
const program=String.raw`
import time,json,urllib.request
def cpu():
 a=list(map(int,open('/proc/stat').readline().split()[1:]));return sum(a[:8]),a[3]+a[4]
def net():
 t=r=0
 for l in open('/proc/net/dev'):
  if ':' not in l:continue
  n,v=l.split(':');a=v.split()
  if n.strip()=='lo':continue
  r+=int(a[0]);t+=int(a[8])
 return t,r
previous=cpu();start=time.monotonic()
while time.monotonic()-start<5400:
 time.sleep(5);now=cpu();total=now[0]-previous[0];idle=now[1]-previous[1];previous=now
 mem={l.split(':')[0]:int(l.split()[1])*1024 for l in open('/proc/meminfo')}
 tx,rx=net();m={'system_cpu_usage':1-idle/max(1,total),'mahjong_host_memory_available':mem['MemAvailable'],'mahjong_host_memory_total':mem['MemTotal'],'mahjong_host_network_transmit_bytes':tx,'mahjong_host_network_receive_bytes':rx}
 try:
  text=urllib.request.urlopen('http://127.0.0.1:9091/actuator/prometheus',timeout=2).read().decode()
  for l in text.splitlines():
   name=l.split('{')[0].split(' ')[0]
   if name in ['mahjong_rooms_playing','mahjong_players_active','mahjong_ws_send_pending','mahjong_ws_delivery_pending','jvm_threads_live_threads','hikaricp_connections_active','hikaricp_connections_pending']:
    m[name]=m.get(name,0)+float(l.split()[-1])
  error=None
 except Exception as e:error=type(e).__name__
 print(json.dumps({'at':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'ok':True,'source':'ssh-proc','metrics':m,'actuatorError':error}),flush=True)
`;
const child=spawn('ssh',['-i','C:/Users/ASUS/.ssh/mahjong_ops','-o','BatchMode=yes','-o','ServerAliveInterval=10','-o','ServerAliveCountMax=3','root@39.107.247.119','python3 -u -'],{stdio:['pipe','pipe','pipe']});
child.stdin.end(program);let buffer='',last=Date.now(),badCpu=0,done=false;
function halt(reason){if(!fs.existsSync(stop))fs.writeFileSync(stop,JSON.stringify({reason,at:new Date().toISOString()}));}
child.stdout.on('data',b=>{buffer+=b;let i;while((i=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,i);buffer=buffer.slice(i+1);try{const s=JSON.parse(line);last=Date.now();fs.appendFileSync(path.join(out,'server-metrics-direct.jsonl'),line+'\n');badCpu=s.metrics.system_cpu_usage>.9?badCpu+1:0;if(s.metrics.mahjong_host_memory_available<100*1024**2)halt('available-memory-under-100MiB');if(badCpu>=3)halt('cpu-over-90-percent-for-three-samples');}catch{halt('invalid-direct-monitor-sample');}}});
child.stderr.on('data',b=>process.stderr.write(b));
child.on('exit',code=>{if(!done)halt('direct-monitor-exited');clearInterval(timer);console.log('SSH MONITOR FINISHED',code);});
const timer=setInterval(()=>{if(fs.existsSync(path.join(out,'monitor-direct-done.json'))){done=true;child.kill();}else if(Date.now()-last>30000)halt('direct-monitor-no-sample-for-30s');},1000);
