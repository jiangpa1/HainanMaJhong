import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';

const out=path.resolve(process.env.RETEST_MONITOR_OUT||'docs/performance/results-2026-10-11-retest');
fs.mkdirSync(out,{recursive:true});
const stopFile=path.join(out,'resource-stop.json'),doneFile=path.join(out,'monitor-done.json');
const selected=/^(?:system_cpu_usage|process_cpu_usage|jvm_threads_live_threads|jvm_memory_used_bytes|jvm_memory_max_bytes|jvm_gc_pause_seconds_(?:count|sum)|hikaricp_connections_(?:active|pending|max)|mahjong_host_(?:available|network_available|memory_available|memory_total|network_transmit_bytes|network_receive_bytes|tcp_retransmits)|mahjong_(?:players_active|rooms_playing|capacity_sample_timestamp|external_probe_up|ws_send_pending|ws_delivery_pending|ws_delivery_expired_total|ws_delivery_dropped_total|db_transaction_seconds_count|room_snapshot_seconds_count))/;
let finished=false,badCpu=0,misses=0;
process.on('SIGINT',()=>{finished=true});
const read=()=>new Promise((resolve,reject)=>{
  const req=http.get('http://127.0.0.1:19091/actuator/prometheus',res=>{let text='';res.on('data',b=>{text+=b;if(text.length>2e6)req.destroy(Error('monitor-response-too-large'))});res.on('error',reject);res.on('end',()=>res.statusCode===200?resolve(text):reject(Error('monitor-http-'+res.statusCode)))});
  const timer=setTimeout(()=>req.destroy(Error('monitor-timeout')),5000);req.on('close',()=>clearTimeout(timer));req.on('error',reject);
});
const started=Date.now();
while(!finished&&Date.now()-started<90*60*1000&&!fs.existsSync(doneFile)){
  const began=Date.now();let sample={at:new Date().toISOString(),metrics:{}};
  try {
    const text=await read();
    for(const line of text.split('\n')){
      if(!selected.test(line))continue;
      const m=line.match(/^(\w+)(?:\{([^}]*)\})?\s+([\d.eE+-]+)/);if(!m)continue;
      let name=m[1];const labels=m[2]||'';
      if(name.startsWith('jvm_memory_')){if(!labels.includes('area="heap"'))continue;name+='_heap';}
      if(name==='mahjong_db_transaction_seconds_count'||name==='mahjong_room_snapshot_seconds_count'){if(!labels.includes('outcome="error"'))continue;name+='_errors';}
      sample.metrics[name]=(sample.metrics[name]||0)+Number(m[3]);
    }
    sample.ok=true;misses=0;
    const mem=sample.metrics.mahjong_host_memory_available,cpu=sample.metrics.system_cpu_usage;
    badCpu=cpu>.9?badCpu+1:0;
    const stale=Date.now()/1000-(sample.metrics.mahjong_capacity_sample_timestamp||Date.now()/1000);
    const reason=mem<100*1024**2?'available-memory-under-100MiB':badCpu>=3?'cpu-over-90-percent-for-three-samples':stale>30?'host-sample-stale-over-30s':null;
    if(reason&&!fs.existsSync(stopFile)){fs.writeFileSync(stopFile,JSON.stringify({reason,at:sample.at}));console.log('RESOURCE STOP '+reason);}
  } catch(e) {
    sample.ok=false;sample.error=e.message;misses++;
    if(misses>=3&&!fs.existsSync(stopFile))fs.writeFileSync(stopFile,JSON.stringify({reason:'monitor-unavailable-three-samples',at:sample.at}));
  }
  fs.appendFileSync(path.join(out,'server-metrics.jsonl'),JSON.stringify(sample)+'\n');
  if(!sample.ok)console.log('Monitor failed: '+sample.error);
  const wait=5000-(Date.now()-began);if(wait>0)await new Promise(r=>setTimeout(r,wait));
}
console.log('MONITOR FINISHED');
