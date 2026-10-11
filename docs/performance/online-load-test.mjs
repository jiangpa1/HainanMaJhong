import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import { acceptEncoding, decodeHttpBody } from './http-json-body.mjs';

// Credentials arrive through stdin and are never written to reports.
const base = process.env.LOAD_TEST_BASE_URL || 'http://jiangpahnmj.cn';
const responseEncoding = acceptEncoding(process.env.LOAD_TEST_ACCEPT_ENCODING);
const out = path.resolve('docs/performance/results-2026-10-08', process.env.LOAD_TEST_RUN_NAME || '.');
fs.mkdirSync(out, { recursive: true });
const agent = new http.Agent({ keepAlive: true, maxSockets: 32, maxFreeSockets: 32 });
const tlsAgent = new https.Agent({ keepAlive: true, maxSockets: 2 });
const report = { startedAt: new Date().toISOString(), base, client: process.version, acceptEncoding: responseEncoding,
  limits: { maxInflight: 32, requestTimeoutMs: 5000, stageP95LimitMs: 3000,
    errorRateStop: 0.01, maxRate: 100 }, probes: [], stages: [], notes: [] };
let token;
let credentials;
let stopping = false;
function save() { fs.writeFileSync(path.join(out, 'http-results.json'), JSON.stringify(report, null, 2)); }
function emit(x) { console.log(JSON.stringify(x)); save(); }
const percentile = (a, p) => a.length ? [...a].sort((x, y) => x-y)[Math.max(0, Math.ceil(a.length*p)-1)] : null;
function request(route, { method = 'GET', body, auth = false, origin = base, timeoutMs = 5000 } = {}) {
  return new Promise(resolve => {
    const started = performance.now();
    const url = new URL(route, origin);
    const headers = { 'User-Agent': 'HainanMahjong-authorized-load-test/1.0', Accept: 'application/json,text/html' };
    headers['Accept-Encoding'] = responseEncoding;
    const payload = body ? JSON.stringify(body) : undefined;
    if (payload) { headers['Content-Type'] = 'application/json'; headers['Content-Length'] = Buffer.byteLength(payload); }
    if (auth) headers.Authorization = `Bearer ${token}`;
    let done = false;const phases={};
    const finish = data => { if (!done) { done = true; clearTimeout(timer); resolve({ ...data, phases, ms: performance.now()-started }); } };
    const req = (url.protocol === 'https:' ? https : http).request(url,
      { method, headers, family:4, agent: url.protocol === 'https:' ? tlsAgent : agent }, res => {
        phases.responseHeadersMs=performance.now()-started;
        let bytes = 0; const chunks = [];
        res.on('data', b => { bytes += b.length; if (bytes <= 2_000_000) chunks.push(b); else req.destroy(new Error('response-too-large')); });
        res.on('error', e => finish({ status: res.statusCode, error: e.code || e.message, bytes }));
        res.on('end', () => {
          let decoded;
          try { decoded = decodeHttpBody(Buffer.concat(chunks), res.headers['content-encoding']); }
          catch (e) { finish({ status: res.statusCode, bytes, error: e.code || e.message }); return; }
          const { text } = decoded;
          let json; try { json = JSON.parse(text); } catch {}
          finish({ status: res.statusCode, bytes, ...decoded, json, reusedSocket: req.reusedSocket });
        });
      });
    const timer = setTimeout(() => req.destroy(new Error('timeout')), timeoutMs);
    req.on('socket',socket=>{
      phases.socketAssignedMs=performance.now()-started;
      if(!socket.connecting) phases.connectedMs=performance.now()-started;
      else {
        socket.once('lookup',()=>{phases.lookupMs=performance.now()-started;});
        socket.once('connect',()=>{phases.connectedMs=performance.now()-started;});
      }
    });
    req.on('error', e => finish({ status: 0, error: e.code || e.message, bytes: 0 }));
    if (payload) req.write(payload);
    req.end();
  });
}
function valid(r, kind) {
  if (r.error || r.status !== 200) return false;
  if (kind === 'home') return r.text?.includes('<div id="app">') && r.text?.includes('/assets/index-');
  if (kind === 'login') return r.json?.code === 200 && typeof r.json?.data?.accessToken === 'string';
  return r.json?.code === 200 && r.json?.data !== undefined;
}
async function probe(name, route, opts, kind) {
  const r = await request(route, opts);
  const summary = { name, route, status: r.status, code: r.json?.code, message: r.json?.message,
    ms: +r.ms.toFixed(2), bytes: r.bytes, decodedBytes: r.decodedBytes,
    contentEncoding: r.contentEncoding, error: r.error, phases:r.phases, ok: kind ? !!valid(r, kind) : undefined };
  report.probes.push(summary); emit({ probe: summary }); return r;
}
async function stage(name, route, rate, duration, cap, opts, kind) {
  const lag = monitorEventLoopDelay({ resolution: 20 }); lag.enable();
  const started = performance.now();
  const s = { name, route, targetRps: rate, durationSeconds: duration, cap,
    sent: 0, completed: 0, successful: 0, failed: 0, skipped: 0, peakInflight: 0,
    bytes: 0, decodedBytes: 0, encodingCounts: {}, statusCounts: {}, businessCodeCounts: {}, errorCounts: {}, failureSamples:[], reusedConnections: 0 };
  const latencies = []; const successfulLatencies = []; const jobs = new Set();
  let aborted; let consecutiveFailures = 0; let lastTick = started;
  emit({ starting: name, rate, duration, cap });
  const interval = 1000/rate;
  for (let slot=0; performance.now()-started < duration*1000 && !aborted && !stopping; slot++) {
    const wait = started+slot*interval-performance.now();
    if (wait>0) await new Promise(r => setTimeout(r, wait));
    if (performance.now()-started >= duration*1000) break;
    if (jobs.size >= cap) { s.skipped++; continue; }
    s.sent++; s.peakInflight = Math.max(s.peakInflight, jobs.size+1);
    const job = request(route, opts).then(r => {
      s.completed++; s.bytes += r.bytes; latencies.push(r.ms);
      s.decodedBytes += r.decodedBytes || 0;
      if (r.contentEncoding) s.encodingCounts[r.contentEncoding] = (s.encodingCounts[r.contentEncoding] || 0) + 1;
      s.statusCounts[r.status] = (s.statusCounts[r.status] || 0)+1;
      if (r.json?.code !== undefined) s.businessCodeCounts[r.json.code] = (s.businessCodeCounts[r.json.code] || 0)+1;
      if (r.reusedSocket) s.reusedConnections++;
      if (valid(r, kind)) { s.successful++; successfulLatencies.push(r.ms); consecutiveFailures=0; }
      else { s.failed++; consecutiveFailures++;
        const error = r.error || `HTTP-${r.status}/business-${r.json?.code ?? 'invalid-body'}`;
        s.errorCounts[error]=(s.errorCounts[error] || 0)+1;
        if(s.failureSamples.length<8)s.failureSamples.push({error,ms:r.ms,phases:r.phases,reusedSocket:r.reusedSocket});
      }
      if (consecutiveFailures >= 3) aborted='three-consecutive-failures';
      if (s.completed >= 100 && s.failed/s.completed > 0.01) aborted='error-rate-over-1-percent';
      if (latencies.length >= 100 && percentile(latencies.slice(-100), .95)>3000) aborted='recent-p95-over-3000ms';
    }).finally(() => jobs.delete(job));
    jobs.add(job);
    if (performance.now()-lastTick > 10000) {
      lastTick=performance.now(); emit({ progress:name, rate, completed:s.completed, failed:s.failed,
        p95Ms:+(percentile(latencies,.95) || 0).toFixed(2), inflight:jobs.size, skipped:s.skipped });
    }
  }
  s.emissionSeconds = +(performance.now()-started).toFixed(1)/1000;
  await Promise.allSettled([...jobs]);
  lag.disable();
  s.elapsedSeconds = +(performance.now()-started).toFixed(1)/1000;
  s.actualRps = +(s.completed/s.elapsedSeconds).toFixed(2);
  s.successRps = +(s.successful/s.elapsedSeconds).toFixed(2);
  s.errorRate = s.completed ? s.failed/s.completed : 0;
  s.p50Ms = percentile(latencies,.50); s.p95Ms=percentile(latencies,.95); s.p99Ms=percentile(latencies,.99);
  s.successP95Ms=percentile(successfulLatencies,.95);
  s.maxMs=latencies.length ? Math.max(...latencies) : null;
  s.clientEventLoopP99Ms=lag.percentile(99)/1e6;
  s.aborted=aborted || (stopping ? 'interrupted' : null);
  s.pass = !s.aborted && s.skipped === 0 && s.errorRate<.01 && s.p95Ms<1500;
  report.stages.push(s); emit({ result:s });
  return s;
}
process.on('SIGINT', () => { stopping=true; });
try {
  console.log('READY_FOR_CREDENTIALS');
  if (process.env.LOAD_TEST_CREDENTIAL_FILE) {
    const credentialPath=process.env.LOAD_TEST_CREDENTIAL_FILE;
    credentials=JSON.parse(fs.readFileSync(credentialPath,'utf8'));
    fs.unlinkSync(credentialPath);
  } else {
    const rl=readline.createInterface({ input:process.stdin });
    credentials=JSON.parse(await new Promise(resolve => rl.once('line', resolve))); rl.close();
  }
  let home=await probe('homepage', '/', {timeoutMs:15000}, 'home');
  if (!valid(home,'home')) home=await probe('homepage-retry','/',{timeoutMs:15000},'home');
  await probe('tls-reachability', '/', { origin:'https://jiangpahnmj.cn', timeoutMs:10000 }, 'home');
  await probe('unauthenticated-response', '/api/user/me', {});
  if (!valid(home,'home')) throw new Error('Homepage baseline failed');
  const login=await probe('login-baseline', '/api/login', { method:'POST', body:credentials }, 'login');
  if (!valid(login,'login')) throw new Error('Login baseline failed; no business load attempted');
  token=login.json.data.accessToken;
  const me=await probe('user-baseline', '/api/user/me', { auth:true }, 'api');
  const records=await probe('records-baseline', '/api/records?pageNum=1&pageSize=10', { auth:true }, 'api');
  if (!valid(me,'api') || !valid(records,'api')) throw new Error('Authenticated baseline failed');
  report.notes.push('Single supplied account; read-only authenticated tests reuse its token. This is endpoint capacity, not independent-player capacity.');
  for (const [name,route,kind,opts] of [
    ['homepage','/','home',{}], ['user-info','/api/user/me','api',{auth:true}],
    ['records','/api/records?pageNum=1&pageSize=10','api',{auth:true}] ]) {
    if(process.env.LOAD_TEST_SCENARIOS && !process.env.LOAD_TEST_SCENARIOS.split(',').includes(name))continue;
    const rates=(process.env.LOAD_TEST_RATES || '5,10,25,50,100').split(',').map(Number);
    for (const rate of rates) {
      const s=await stage(name,route,rate,30,32,opts,kind);
      if (s.aborted) { stopping=true; break; }
      if (!s.pass) { report.notes.push(`${name}: stopped increasing after failed acceptance at ${rate} RPS`); break; }
      await new Promise(r=>setTimeout(r,1500));
    }
    if (stopping) break;
  }
  if (!stopping) {
    for (const rate of (process.env.LOAD_TEST_LOGIN_RATES || '1,2,5,10').split(',').map(Number)) {
      const s=await stage('login','/api/login',rate,20,4,{method:'POST',body:credentials},'login');
      if (s.aborted || !s.pass) break;
      await new Promise(r=>setTimeout(r,1500));
    }
    // Only the latest token is valid after the login scenario.
    const again=await probe('recovery-login','/api/login',{method:'POST',body:credentials},'login');
    if (valid(again,'login')) token=again.json.data.accessToken;
  }
  await probe('post-test-home','/',{},'home');
  await probe('post-test-user','/api/user/me',{auth:true},'api');
} catch(e) { report.notes.push(e.message); emit({ stopped:e.message }); }
finally { credentials=undefined; token=undefined; agent.destroy(); tlsAgent.destroy(); report.finishedAt=new Date().toISOString(); save(); console.log(`REPORT ${path.join(out,'http-results.json')}`); }
