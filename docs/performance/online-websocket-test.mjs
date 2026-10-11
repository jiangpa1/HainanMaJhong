import { createRequire } from 'node:module';
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { performance } from 'node:perf_hooks';
const require = createRequire(import.meta.url);
const WebSocket = require('../../HainanMaJhong/frontend/node_modules/.pnpm/ws@8.21.3/node_modules/ws');
const out=path.resolve('docs/performance/results-2026-10-08',process.env.LOAD_TEST_WS_REPORT || 'websocket-results.json');
const report={startedAt:new Date().toISOString(), endpoint:'ws://jiangpahnmj.cn/room',
  notes:['One account opens multiple idle /room connections. This does not simulate distinct users or active games.',
    'No room is created and no game action is sent. Ping/pong measures protocol roundtrip only.'],
  limits:{maxConnections:100,openingConcurrency:1,handshakeTimeoutMs:5000},stages:[]};
const sockets=[]; const unexpected=[]; let closing=false; let token; let failure;
const pause=ms=>new Promise(r=>setTimeout(r,ms));
const pct=(a,p)=>a.length ? [...a].sort((x,y)=>x-y)[Math.ceil(a.length*p)-1] : null;
const save=()=>fs.writeFileSync(out,JSON.stringify(report,null,2));
function login(credentials) {
  return new Promise((resolve,reject)=>{
    const data=JSON.stringify(credentials); const start=performance.now();
    const req=http.request('http://jiangpahnmj.cn/api/login',{method:'POST',family:4,
      headers:{'Content-Type':'application/json','Content-Length':Buffer.byteLength(data),
        'User-Agent':'HainanMahjong-authorized-load-test/1.0'}},res=>{
      let body='';res.on('data',b=>body+=b);res.on('end',()=>{
        clearTimeout(timer);
        try { const j=JSON.parse(body); if(res.statusCode!==200 || j.code!==200 || !j.data?.accessToken) throw Error('login-unsuccessful');
          report.loginMs=performance.now()-start;resolve(j.data.accessToken);
        } catch(e) {reject(e);}
      });
    });
    const timer=setTimeout(()=>req.destroy(new Error('login-timeout')),10000);
    req.on('error',e=>{clearTimeout(timer);reject(e);});req.end(data);
  });
}
function open() {
  return new Promise((resolve,reject)=>{
    const start=performance.now();let opened=false;
    const ws=new WebSocket(`ws://jiangpahnmj.cn/room?token=${encodeURIComponent(token)}`,
      {family:4,handshakeTimeout:5000,perMessageDeflate:false});
    ws.on('open',()=>{opened=true;sockets.push(ws);resolve(performance.now()-start);});
    ws.on('error',e=>{if(!opened) reject(new Error(e.code || e.message.replace(token,'[redacted]').replace(/token=[^\s]+/g,'token=[redacted]')));
      else if(!closing) {failure=e.code || 'websocket-error';}});
    ws.on('close',(code)=>{if(!closing && opened) {unexpected.push({code});failure='unexpected-close';}
      if(!opened) reject(new Error(`handshake-close-${code}`));});
    ws.on('message',body=>{try{if(JSON.parse(body).type==='kick') failure='account-kicked';}catch{}});
  });
}
function ping(ws) {
  return new Promise(resolve=>{
    const start=performance.now();const payload=Buffer.from(String(start));let done=false;
    const finish=value=>{if(!done){done=true;clearTimeout(timer);ws.off('pong',onPong);resolve(value);}};
    const onPong=data=>{if(data.equals(payload)) finish(performance.now()-start);};
    const timer=setTimeout(()=>finish(null),5000);ws.on('pong',onPong);
    if(ws.readyState!==WebSocket.OPEN) finish(null);
    else ws.ping(payload,true,e=>{if(e)finish(null);});
  });
}
process.on('SIGINT',()=>{failure='interrupted';});
try {
  const file=process.env.LOAD_TEST_CREDENTIAL_FILE;
  let credentials=JSON.parse(fs.readFileSync(file,'utf8'));fs.unlinkSync(file);
  token=await login(credentials);credentials=undefined;
  const targets=(process.env.LOAD_TEST_WS_TARGETS || '1,10,25,50,100').split(',').map(Number);
  const holdMs=Number(process.env.LOAD_TEST_WS_HOLD_SECONDS || 30)*1000;
  report.limits.maxConnections=Math.max(...targets);
  report.limits.holdSeconds=holdMs/1000;
  for(const count of targets) {
    report.currentTargetConnections=count;
    const handshakes=[]; const start=performance.now();
    console.log(JSON.stringify({starting:count,holdSeconds:holdMs/1000}));
    while(sockets.length<count && !failure) {handshakes.push(await open());await pause(200);}
    if(failure) break;
    const rtts=[];let failedPings=0;const hold=performance.now();
    for(let tick=0;tick<3 && !failure;tick++) {
      const values=await Promise.all(sockets.map(ping));
      for(const x of values) if(x===null) failedPings++; else rtts.push(x);
      if(failedPings || (rtts.length && pct(rtts,.95)>3000)) failure='ping-timeout-or-high-latency';
      const wait=hold+(tick+1)*holdMs/3-performance.now();if(wait>0)await pause(wait);
    }
    const s={targetConnections:count,openConnections:sockets.filter(x=>x.readyState===WebSocket.OPEN).length,
      newConnections:handshakes.length,handshakeP95Ms:pct(handshakes,.95),holdSeconds:(performance.now()-hold)/1000,
      successfulPings:rtts.length,failedPings,pingP50Ms:pct(rtts,.50),pingP95Ms:pct(rtts,.95),pingP99Ms:pct(rtts,.99),
      unexpectedCloses:unexpected.length,elapsedSeconds:(performance.now()-start)/1000,
      failure:failure || null};
    report.stages.push(s);save();console.log(JSON.stringify({result:s}));
    if(failure)break;
  }
} catch(e) {report.error=e.code || e.message;console.log(JSON.stringify({stopped:report.error}));}
finally {
  report.openConnectionsBeforeCleanup=sockets.filter(x=>x.readyState===WebSocket.OPEN).length;
  closing=true;
  await Promise.all(sockets.map(ws=>new Promise(resolve=>{
    if(ws.readyState===WebSocket.CLOSED)return resolve();
    const timer=setTimeout(()=>{ws.terminate();resolve();},3000);
    ws.once('close',()=>{clearTimeout(timer);resolve();});ws.close(1000,'load test completed');
  })));
  token=undefined;report.unexpectedCloses=unexpected;report.finishedAt=new Date().toISOString();
  report.remainingOpenConnections=sockets.filter(x=>x.readyState!==WebSocket.CLOSED).length;save();console.log(`REPORT ${out}`);
}
