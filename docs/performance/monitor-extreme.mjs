// 极限测试用监控：与 docs/performance/monitor.mjs 采样口径完全一致（每 5 秒抓同一批指标、
// 写同一份 server-metrics.jsonl），但保护阈值放宽且可配，用于观察"越过 0.9"之后的饱和曲线。
//   EX_CPU          触发保护的 system_cpu_usage 阈值（默认 0.95）
//   EX_CPU_SECONDS  持续有效观测时长（默认 60 秒）；旧 EX_CPU_SAMPLES 映射为 samples*5 秒
//   EX_MEM_MB       可用内存低于该值即保护停（默认 80）
import fs from 'node:fs';
import path from 'node:path';
import { cpuGuard } from './cpu-guard.mjs';
const args = process.argv.slice(2);
const arg = name => args[args.indexOf(name) + 1];
if (!args.includes('--url') || !args.includes('--out')) throw Error('Usage: node monitor-extreme.mjs --url <prometheus> --out <dir>');
const url = new URL(arg('--url')), out = path.resolve(arg('--out'));
if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) throw Error('Invalid metrics URL');
fs.mkdirSync(out, { recursive: true });
const guard = cpuGuard(process.env, 'EX', 80);
const { cpuThreshold, cpuSeconds, memoryMb } = guard.policy;
const memFloor = memoryMb * 1024 * 1024;
fs.writeFileSync(path.join(out, 'guard-policy.json'), JSON.stringify(guard.policy, null, 2));
const stop = path.join(out, 'resource-stop.json'), done = path.join(out, 'monitor-done.json');
let finished = false, misses = 0, peakCpu = 0, cpuOver90 = 0, samples = 0;
process.on('SIGINT', () => finished = true);
function halt(reason) { if (!fs.existsSync(stop)) fs.writeFileSync(stop, JSON.stringify({ reason, at: new Date().toISOString(), peakCpu, cpuOver90, samples })); }
while (!finished && !fs.existsSync(done)) {
  const sample = { at: new Date().toISOString(), metrics: {} };
  try {
    const response = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw Error('metrics-http-' + response.status);
    const text = await response.text();
    for (const line of text.split('\n')) {
      const match = line.match(/^(\w+)(?:\{([^}]*)\})?\s+([\d.eE+-]+)/);
      if (!match || !/^(system_|process_|jvm_|hikaricp_|mahjong_)/.test(match[1])) continue;
      const labels = match[2] || '';
      let name = match[1];
      if (name.startsWith('mahjong_admission_')) {
        for (const label of ['group', 'outcome', 'scope']) {
          const value = labels.match(new RegExp(label + '="([a-z_]+)"')); if (value) name += '_' + value[1];
        }
      }
      if (name.startsWith('jvm_memory_')) { if (!labels.includes('area="heap"')) continue; name += '_heap'; }
      if (name.startsWith('mahjong_records_cache_requests')) { const o = labels.match(/outcome="([a-z_]+)"/); if (o) name += '_' + o[1]; }
      sample.metrics[name] = (sample.metrics[name] || 0) + Number(match[3]);
    }
    const m = sample.metrics;
    if (!Number.isFinite(m.system_cpu_usage) || !Number.isFinite(m.mahjong_host_memory_available)) throw Error('required-host-metrics-missing');
    samples++;
    peakCpu = Math.max(peakCpu, m.system_cpu_usage);
    if (m.system_cpu_usage > 0.9) cpuOver90++;
    const cpuExceeded = guard.observe(m.system_cpu_usage);
    if (m.mahjong_host_memory_available < memFloor) halt('extreme-memory-floor');
    if (cpuExceeded) halt('extreme-cpu-sustained');
    if (m.mahjong_capacity_sample_timestamp && Date.now() / 1000 - m.mahjong_capacity_sample_timestamp > 30) halt('host-metrics-stale');
    sample.ok = true; misses = 0;
  } catch (e) { guard.reset(); sample.ok = false; sample.error = e.message; if (++misses >= 3) halt('monitor-unavailable-three-samples'); }
  fs.appendFileSync(path.join(out, 'server-metrics.jsonl'), JSON.stringify(sample) + '\n');
  await new Promise(resolve => setTimeout(resolve, 5000));
}
fs.writeFileSync(path.join(out, 'extreme-summary.json'), JSON.stringify({ samples, peakCpu, samplesOver90: cpuOver90, ...guard.policy }));
