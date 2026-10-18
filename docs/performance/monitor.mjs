import fs from 'node:fs';
import path from 'node:path';
import { cpuGuard } from './cpu-guard.mjs';
const args=process.argv.slice(2);
const arg=name=>args[args.indexOf(name)+1];
if(!args.includes('--url')||!args.includes('--out'))throw Error('Usage: node monitor.mjs --url http://127.0.0.1:19091/actuator/prometheus --out runs/run-001');
const url=new URL(arg('--url')),out=path.resolve(arg('--out'));
if(!['http:','https:'].includes(url.protocol)||url.username||url.password)throw Error('Invalid metrics URL');
fs.mkdirSync(out,{recursive:true});
const stop=path.join(out,'resource-stop.json'),done=path.join(out,'monitor-done.json');
// 资源保护策略（可用环境变量覆盖）：旧策略是 0.9 × 连续 3 个 5 秒采样（15 秒）即强停，
// 实测过于灵敏——多次在 CPU 均值仅 0.37~0.71、且全部验收判据满足的情况下被掐停（400/480 档尤其明显）。
// 2026-10-16 定稿 0.95 × 实际持续 60 秒；内存保护维持 100 MiB，断样重置 CPU 窗口。
const guard=cpuGuard(process.env,'GUARD',100);
const {cpuThreshold,cpuSeconds,memoryMb}=guard.policy;
const memFloorBytes=memoryMb*1024*1024;
fs.writeFileSync(path.join(out,'guard-policy.json'),JSON.stringify(guard.policy,null,2));
let finished=false,misses=0;
process.on('SIGINT',()=>finished=true);
function halt(reason) { if(!fs.existsSync(stop))fs.writeFileSync(stop,JSON.stringify({reason,at:new Date().toISOString()})); }
while(!finished&&!fs.existsSync(done)) {
  const sample={at:new Date().toISOString(),metrics:{}};
  try {
    const response=await fetch(url,{signal:AbortSignal.timeout(5000)});
    if(!response.ok)throw Error('metrics-http-'+response.status);
    const text=await response.text();
    if(text.length>4e6)throw Error('metrics-response-too-large');
    for(const line of text.split('\n')) {
      const match=line.match(/^(\w+)(?:\{([^}]*)\})?\s+([\d.eE+-]+)/);
      if(!match||!/^(system_|process_|jvm_|hikaricp_|mahjong_)/.test(match[1]))continue;
      const labels=match[2]||'';
      let name=match[1];
      if(name.startsWith('mahjong_admission_')) {
        for(const label of ['group','outcome','scope']) {
          const value=labels.match(new RegExp(label+'="([a-z_]+)"'));if(value)name+='_'+value[1];
        }
      }
      if(name.startsWith('jvm_memory_')) { if(!labels.includes('area="heap"'))continue;name+='_heap'; }
      if(name.startsWith('mahjong_records_cache_requests')) {
        const outcome=labels.match(/outcome="([a-z_]+)"/); if(outcome)name+='_'+outcome[1];
      }
      sample.metrics[name]=(sample.metrics[name]||0)+Number(match[3]);
    }
    const m=sample.metrics;
    if(!Number.isFinite(m.system_cpu_usage)||!Number.isFinite(m.mahjong_host_memory_available))throw Error('required-host-metrics-missing');
    const cpuExceeded=guard.observe(m.system_cpu_usage);
    if(m.mahjong_host_memory_available<memFloorBytes)halt(`available-memory-under-${Math.round(memFloorBytes/1024**2)}MiB`);
    if(cpuExceeded)halt(`cpu-over-${Math.round(cpuThreshold*100)}-percent-for-${cpuSeconds}-seconds`);
    if(m.mahjong_capacity_sample_timestamp&&Date.now()/1000-m.mahjong_capacity_sample_timestamp>30)halt('host-metrics-stale');
    sample.ok=true;misses=0;
  } catch(e) { guard.reset();sample.ok=false;sample.error=e.message;if(++misses>=3)halt('monitor-unavailable-three-samples'); }
  fs.appendFileSync(path.join(out,'server-metrics.jsonl'),JSON.stringify(sample)+'\n');
  await new Promise(resolve=>setTimeout(resolve,5000));
}
