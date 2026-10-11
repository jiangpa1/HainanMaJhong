import http from 'node:http';
import https from 'node:https';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { randomBytes } from 'node:crypto';
import { performance, monitorEventLoopDelay } from 'node:perf_hooks';
import {spawnSync} from 'node:child_process';
import {acceptEncoding,decodeHttpBody} from './http-json-body.mjs';
const require = createRequire(import.meta.url);
const WebSocket = require(process.env.WS_MODULE || 'F:/HainanMaJhong2/HainanMaJhong/frontend/node_modules/.pnpm/ws@8.21.3/node_modules/ws');
const base = new URL(process.env.TEST_BASE || 'http://jiangpahnmj.cn');
const out = path.resolve(process.env.TEST_OUT || 'outputs/capacity-2026-10-09');
fs.mkdirSync(out, { recursive: true });
const runId = Date.now().toString(36);
const seats = ['EAST', 'SOUTH', 'WEST', 'NORTH'];
const tiers=(process.env.TEST_TABLES||'8,16,32,48,52,56,64').split(',').map(Number);
const duration=Number(process.env.TEST_STAGE_SECONDS||300);
if(tiers.some(n=>!Number.isInteger(n)||n<1||n>64)||!Number.isFinite(duration)||duration<30||duration>600)throw Error('invalid-bounded-test-settings');
const responseEncoding=acceptEncoding(process.env.TEST_ACCEPT_ENCODING);
const v2=process.env.TEST_RECORDS_VERSION==='v2';
const handshakeTimeout=Number(process.env.TEST_HANDSHAKE_TIMEOUT_MS||10000);
const inflightCap=2048;
const agents = { 'http:': new http.Agent({ keepAlive: true, maxSockets: 2048 }), 'https:': new https.Agent({ keepAlive: true, maxSockets: 2048 }) };
const delay = ms => new Promise(r => setTimeout(r, ms));
const pct = (a, p) => a.length ? [...a].sort((a,b)=>a-b)[Math.ceil(a.length*p)-1] : null;
const summary = a => ({ n: a.length, p50Ms: pct(a,.5), p95Ms: pct(a,.95), p99Ms: pct(a,.99), maxMs: a.length ? Math.max(...a) : null });
const report = { runId, base: base.origin, startedAt: new Date().toISOString(), accounts: [], baseline: [], stages: [], cleanup: [], health: [], limits: { maxTables: Math.max(...tiers), maxPlayers: Math.max(...tiers)*4, httpTimeoutMs: 8000, handshakeTimeoutMs: 10000, httpInflightCap: inflightCap, tableIdleStopMs: 45000, actionThinkMs: '100-300', queryRpsPerTable:2.5, durationSeconds:duration,tiers }, notes: ['Dedicated accounts; one active game connection per distinct player.', 'Same aggressive game and records load as the previous test: 2.5 HTTP requests per table per second.', 'Server metrics are collected independently over SSH.', 'No registration, login or write operations are automatically retried.', 'Private recovery credentials are encrypted with Windows CurrentUser DPAPI in work/.'] };
report.limits.handshakeTimeoutMs=handshakeTimeout;
report.acceptEncoding=responseEncoding;
report.recordsVersion=v2?'v2':'legacy';
report.clientVersion=process.version;
report.connectIp=process.env.TEST_CONNECT_IP||null;
report.notes.push('Reuses 2026-10-09 dedicated accounts; fresh login, no credentials printed. Current histories may differ.');
let stopReason;
let currentStage;
const clients = [];
const rooms = [];
const sockets = new Set();
if(process.env.TEST_INITIAL_JSON){clients.push(...JSON.parse(fs.readFileSync(process.env.TEST_INITIAL_JSON,'utf8')));report.accounts=clients.map(c=>({username:c.username,userId:c.id,reused:true}));}
if(process.env.TEST_REUSE_VAULT){
 const command='Add-Type -AssemblyName System.Security; $bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd()); $plain=[System.Security.Cryptography.ProtectedData]::Unprotect($bytes,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([System.Text.Encoding]::UTF8.GetString($plain))';
 const p=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{input:fs.readFileSync(process.env.TEST_REUSE_VAULT).toString('base64'),encoding:'utf8',windowsHide:true,maxBuffer:10000000});
 if(p.status!==0)throw Error('private-recovery-failed');
 clients.push(...JSON.parse(p.stdout));
 report.accounts=clients.map(c=>({username:c.username,userId:c.id,reused:true}));
}
function checkpoint(){
  if(process.env.TEST_PRIVATE_JSON){fs.writeFileSync(process.env.TEST_PRIVATE_JSON,JSON.stringify(clients),{mode:0o600});return;}
  if(process.env.TEST_NO_VAULT==='1')return;
  const command='Add-Type -AssemblyName System.Security; $text=[Console]::In.ReadToEnd(); $bytes=[System.Security.Cryptography.ProtectedData]::Protect([System.Text.Encoding]::UTF8.GetBytes($text),$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([Convert]::ToBase64String($bytes))';
  const p=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',command],{input:JSON.stringify(clients),encoding:'utf8',windowsHide:true,maxBuffer:10000000});
  if(p.status!==0)throw Error('private-checkpoint-failed');
  fs.writeFileSync(path.resolve(process.env.TEST_VAULT||'work/capacity-accounts.dpapi'),Buffer.from(p.stdout.trim(),'base64'));
}
function stopped(){
  const stopFile=process.env.TEST_STOP_FILE||'work/capacity-stop.json';
  if(fs.existsSync(stopFile)){try{stopReason=JSON.parse(fs.readFileSync(stopFile,'utf8')).reason||'external-stop';}catch{stopReason='external-stop';}}
  return !!stopReason;
}
function save() { fs.writeFileSync(path.join(out,'results.json'), JSON.stringify(report,null,2)); }
function emit(event) { console.log(JSON.stringify(event)); save(); }
process.on('SIGINT',()=>{stopReason='interrupted';});
async function request(route, client, body, timeoutMs=8000) {
  const started=performance.now(); const url=new URL(route,base); const payload=body===undefined?null:JSON.stringify(body);
  return new Promise(resolve=>{
    let done=false; const phases={};
    const finish=r=>{if(done)return;done=true;clearTimeout(timer);resolve({...r,ms:performance.now()-started,phases});};
    const headers={'User-Agent':'HainanMahjong-authorized-real-player-test/2.0','Accept':'application/json,text/html'};
    headers['Accept-Encoding']=responseEncoding;
    if(process.env.TEST_HOST)headers.Host=process.env.TEST_HOST;
    if(client)headers.Authorization=`Bearer ${client.token}`;
    if(payload){headers['Content-Type']='application/json';headers['Content-Length']=Buffer.byteLength(payload);}
    const lookup=process.env.TEST_CONNECT_IP?((h,o,cb)=>cb(null,process.env.TEST_CONNECT_IP,4)):undefined;
    const req=(url.protocol==='https:'?https:http).request(url,{method:payload?'POST':'GET',headers,agent:agents[url.protocol],family:4,lookup},res=>{
      phases.headersMs=performance.now()-started;let bytes=0;const chunks=[];
      res.on('data',b=>{bytes+=b.length;if(bytes>3e6)req.destroy(new Error('response-too-large'));else chunks.push(b);});
      res.on('error',e=>finish({status:res.statusCode,error:e.code||'response-error'}));
      res.on('end',()=>{let decoded;try{decoded=decodeHttpBody(Buffer.concat(chunks),res.headers['content-encoding'],3_000_000);}catch(e){finish({status:res.statusCode,error:e.code||'decode-failed',bytes});return;}const {text}=decoded;let json;try{json=JSON.parse(text);}catch{}let headerBytes=Buffer.byteLength(`HTTP/${res.httpVersion} ${res.statusCode} ${res.statusMessage}\r\n\r\n`);for(let i=0;i<res.rawHeaders.length;i+=2)headerBytes+=Buffer.byteLength(`${res.rawHeaders[i]}: ${res.rawHeaders[i+1]}\r\n`);finish({status:res.statusCode,json,bytes,decodedBytes:decoded.decodedBytes,contentEncoding:decoded.contentEncoding,headerBytes,htmlApp:text.includes('<div id="app">')});});
    });
    const timer=setTimeout(()=>req.destroy(new Error('timeout')),timeoutMs);
    req.on('socket',socket=>{phases.socketMs=performance.now()-started;if(!socket.connecting)phases.connectedMs=performance.now()-started;else socket.once('connect',()=>{phases.connectedMs=performance.now()-started;});});
    req.on('error',e=>finish({status:0,error:e.code||e.message}));
    req.end(payload||undefined);
  });
}
function ok(r) { return !r.error && r.status===200 && r.json?.code===200; }
function brief(r) { return { status:r.status, code:r.json?.code, ms:r.ms, bytes:r.bytes, error:r.error, phases:r.phases }; }
async function setupAccounts(n) {
  if(clients.length<n&&process.env.TEST_REUSE_VAULT)throw Error('not-enough-existing-test-accounts');
  for(let i=0;i<Math.min(n,clients.length);i+=4){
    await Promise.all(clients.slice(i,Math.min(i+4,n)).map(async c=>{
      if(c.authenticatedForRun&&c.expiresAt>Date.now()+duration*1000+180000)return;
      const logged=await request('/api/login',null,{username:c.username,password:c.password});
      if(!ok(logged)||!logged.json?.data?.accessToken)throw Error('existing-test-account-login-failed');
      c.token=logged.json.data.accessToken;c.refreshToken=logged.json.data.refreshToken;c.expiresAt=Date.now()+logged.json.data.expiresIn;
      const me=await request('/api/user/me',c);
      if(!ok(me)||me.json?.data?.id!==c.id)throw Error('existing-test-account-identity-mismatch');
      c.authenticatedForRun=true;
    }));
  }
  while(clients.length<n && !stopped()){
    const start=clients.length;
    const batch=await Promise.allSettled(Array.from({length:Math.min(4,n-start)},async(_,j)=>{
      const i=start+j,username=`cp${runId}${String(i).padStart(4,'0')}`,password=randomBytes(18).toString('base64url');
      const entry={username};report.accounts.push(entry);
      const reg=await request('/api/register',null,{username,password,nickname:`容量测试${i+1}`});entry.registration=brief(reg);
      if(!ok(reg))throw Error(`registration-failed-${reg.error||reg.json?.code||reg.status}`);
      entry.userId=reg.json.data?.id;
      const login=await request('/api/login',null,{username,password});entry.login=brief(login);
      if(!ok(login)||!login.json?.data?.accessToken)throw Error('login-failed');
      const c={username,password,token:login.json.data.accessToken,refreshToken:login.json.data.refreshToken,expiresAt:Date.now()+login.json.data.expiresIn};
      const me=await request('/api/user/me',c);if(!ok(me)||!me.json?.data?.id)throw Error('identity-failed');c.id=me.json.data.id;entry.userId=c.id;return c;
    }));
    for(const b of batch)if(b.status==='fulfilled')clients.push(b.value);
    checkpoint();emit({accountsReady:clients.length,target:n});
    const failed=batch.find(b=>b.status==='rejected');if(failed)throw failed.reason;
  }
  if(new Set(clients.map(c=>c.id)).size!==clients.length)throw Error('duplicate-player-identities');
}
class Connection {
  constructor(client,route,room,kind){this.client=client;this.room=room;this.kind=kind;this.messages=[];this.waiters=[];this.expectedClose=false;this.ordinal=0;this.hand=0;this.seenRequests=new Set();
    const url=new URL(route,base);url.protocol=base.protocol==='https:'?'wss:':'ws:';url.searchParams.set('token',client.token);
    this.ws=new WebSocket(url,{family:4,handshakeTimeout,perMessageDeflate:false,maxPayload:3e6,headers:process.env.TEST_HOST?{Host:process.env.TEST_HOST}:undefined,lookup:process.env.TEST_CONNECT_IP?((h,o,cb)=>cb(null,process.env.TEST_CONNECT_IP,4)):undefined});sockets.add(this);
    const began=performance.now();
    this.opened=new Promise((resolve,reject)=>{
      this.ws.once('open',()=>{currentStage?.handshakes?.push(performance.now()-began);resolve();});
      this.ws.once('error',e=>reject(Error(`ws-open-${e.code||e.message||'error'}`)));
      this.ws.once('close',code=>reject(Error(`ws-open-close-${code}`)));
    });
    this.ws.on('message',raw=>{if(kind==='game')room.receivedWireBytes=(room.receivedWireBytes||0)+raw.length+(raw.length<126?2:raw.length<65536?4:10);let m;try{m=JSON.parse(raw);}catch{room.failures.push('invalid-ws-json');return;}this.handle(m);if(kind==='game'&&Number.isSafeInteger(m._monitorSeq)&&m._monitorSeq>0&&this.ws.readyState===WebSocket.OPEN)this.send({type:'metricsAck',seq:m._monitorSeq});});
    this.ws.on('error',e=>{if(!this.expectedClose)room.failures.push(`ws-error-${e.code||'error'}`);});
    this.ws.on('close',code=>{if(!this.expectedClose)room.failures.push(`unexpected-${kind}-close-${code}`);for(const w of this.waiters.splice(0)){clearTimeout(w.timer);w.reject(Error('closed-while-waiting'));}});
  }
  wait(predicate,timeout=12000){const found=this.messages.find(predicate);if(found)return Promise.resolve(found);return new Promise((resolve,reject)=>{const w={predicate,resolve,reject};w.timer=setTimeout(()=>{this.waiters=this.waiters.filter(x=>x!==w);reject(Error('ws-message-timeout'));},timeout);this.waiters.push(w);});}
  send(m){if(this.ws.readyState===WebSocket.OPEN){const raw=JSON.stringify(m);this.ws.send(raw);if(this.kind==='game')this.room.sentWireBytes=(this.room.sentWireBytes||0)+Buffer.byteLength(raw)+6;}else throw Error('send-on-closed-connection');}
  handle(m){const r=this.room;const now=performance.now();r.lastMessage=now;
    if(this.messages.length>50)this.messages.shift();this.messages.push(m);
    for(const w of [...this.waiters])if(w.predicate(m)){clearTimeout(w.timer);this.waiters=this.waiters.filter(x=>x!==w);w.resolve(m);}
    if(['denied','kick'].includes(m.type)){r.failures.push(`server-${m.type}`);return;}
    if(this.kind!=='game')return;
    r.messageCounts[m.type]=(r.messageCounts[m.type]||0)+1;
    if(m.type==='welcome'){
      if(m.seat!==this.client.seat || m.roomId!==r.code || new Set(m.players?.map(p=>p.userId)).size!==4 || m.players.some(p=>!r.players.some(c=>c.id===p.userId)))r.failures.push('welcome-seat-or-player-mismatch');
    }
    if(m.type==='hand_start'){this.hand=m.hand;this.ordinal=0;this.seenRequests.clear();if(!r.handStarts.has(m.hand))r.handStarts.set(m.hand,now);}
    if(m.type==='request' && !r.closing && !r.frozen){
      const key=`${this.hand}:${m.reqId}`;
      if(this.seenRequests.has(key)){r.duplicateRequests++;return;}this.seenRequests.add(key);
      r.requests[m.kind]=(r.requests[m.kind]||0)+1;
      const think=100+Math.random()*200;
      const timer=setTimeout(()=>{r.timers.delete(timer);if(r.closing || this.ws.readyState!==WebSocket.OPEN)return;
        if(r.frozen)return;let action;
        if(m.kind==='discard'){
          const allowed=m.hand?.filter(t=>!(m.ban||[]).includes(t));
          if(!allowed?.length){r.failures.push('no-legal-discard');return;}
          const counts=new Map();for(const t of allowed)counts.set(t,(counts.get(t)||0)+1);
          const weights=new Map(allowed.map(t=>[t,counts.get(t)*4+(t<27?((m.hand.includes(t-1)&&Math.floor(t/9)===Math.floor((t-1)/9)?2:0)+(m.hand.includes(t+1)&&Math.floor(t/9)===Math.floor((t+1)/9)?2:0)):0)+Math.random()]));
          allowed.sort((a,b)=>weights.get(a)-weights.get(b));const tile=allowed[0];
          action={type:'discard',reqId:m.reqId,tile};
          const ordinal=this.ordinal+1;const event={hand:this.hand,ordinal,seat:this.client.seat,tile,sent:performance.now(),received:{}};
          r.pendingDiscards.set(`${this.hand}:${ordinal}`,event);r.discardsSent++;
        }else{
          const options=m.options||[];let index=options.findIndex(o=>o.type.includes('HU'));
          if(index<0)index=options.findIndex(o=>o.type.includes('GANG'));
          if(index<0 && m.kind==='action' && Math.random()<.75)index=options.findIndex(o=>o.type.includes('PENG')||o.type.includes('CHI'));
          action=index>=0?{type:'act',reqId:m.reqId,index}:{type:'pass',reqId:m.reqId};
          if(index>=0)r.actions[options[index].type]=(r.actions[options[index].type]||0)+1;
        }
        try{this.send(action);r.thinkTimes.push(performance.now()-now);}catch{r.failures.push('action-send-failed');}
      },think);r.timers.add(timer);
    }
    if(m.type==='discard'){
      this.ordinal++;const key=`${this.hand}:${this.ordinal}`;const ev=r.pendingDiscards.get(key);
      if(ev){if(ev.seat!==m.seat||ev.tile!==m.tile){r.failures.push('discard-broadcast-mismatch');return;}
        if(ev.received[this.client.id]!==undefined){r.failures.push('duplicate-discard-delivery');return;}
        ev.received[this.client.id]=now-ev.sent;
        if(Object.keys(ev.received).length===4){r.broadcastTimes.push(Math.max(...Object.values(ev.received)));r.ownTimes.push(ev.received[r.players.find(c=>c.seat===ev.seat).id]);r.pendingDiscards.delete(key);}
      }else r.unmatchedBroadcasts++;
    }
    if(m.type==='coins'){
      if(Object.keys(m.coins||{}).length!==4 || Object.values(m.coins||{}).reduce((a,b)=>a+b,0)!==0)r.failures.push('coins-not-zero-sum');
      const existing=r.rounds.get(m.hand);if(existing && JSON.stringify(existing.coins)!==JSON.stringify(m.coins))r.failures.push('settlement-broadcast-mismatch');
      if(!existing){r.rounds.set(m.hand,{coins:m.coins,at:now});r.roundDurations.push(now-(r.handStarts.get(m.hand)||now));if(r.freezeAfterRound)r.frozen=true;}
    }
    if(m.type==='meld'&&m.meld?.type==='AN_GANG'&&m.seat!==this.client.seat&&!m.meld.hidden)r.failures.push('concealed-kong-exposed');
    if(m.type==='match'){
      r.frozen=false;r.finished=false;
      if(m.continued&&r.lastRematchHand!==this.hand){r.lastRematchHand=this.hand;r.rematches=(r.rematches||0)+1;}
    }
    if(m.type==='match_finish'){
      r.completedBlocks??=new Set();r.completedBlocks.add(this.hand);r.finished=true;
      if(m.disband||r.freezeAfterRound||r.closing||process.env.TEST_CONTINUE_MATCHES==='0')r.frozen=true;
      else if(m.isHost&&!r.rematchPending){
        r.rematchPending=true;
        const timer=setTimeout(()=>{r.timers.delete(timer);r.rematchPending=false;if(!r.closing&&!r.freezeAfterRound){try{this.send({type:'rematch'});}catch{r.failures.push('rematch-send-failed');}}},300);r.timers.add(timer);
      }
    }
    if(m.type==='room_closed'&&!r.closing)r.failures.push('unexpected-room-disband');
  }
  async close(){this.expectedClose=true;for(const w of this.waiters.splice(0)){clearTimeout(w.timer);w.reject(Error('test-cleanup'));}if(this.ws.readyState===WebSocket.CLOSED)return;sockets.delete(this);await new Promise(resolve=>{const t=setTimeout(()=>{this.ws.terminate();resolve();},2500);this.ws.once('close',()=>{clearTimeout(t);resolve();});this.ws.close(1000,'authorized test complete');});}
}
async function table(players){const r={players,connections:[],code:null,sessionId:null,lastMessage:performance.now(),failures:[],messageCounts:{},requests:{},actions:{},timers:new Set(),pendingDiscards:new Map(),rounds:new Map(),handStarts:new Map(),broadcastTimes:[],ownTimes:[],thinkTimes:[],roundDurations:[],discardsSent:0,duplicateRequests:0,unmatchedBroadcasts:0};rooms.push(r);
  const owner=new Connection(players[0],'/room',r,'room');r.connections.push(owner);await owner.opened;
  owner.send({type:'createRoom',config:{}});const info=await owner.wait(m=>m.type==='room_info');r.code=info.code;
  report.provisionedRooms??=[];report.provisionedRooms.push({roomId:r.code,players:players.map(c=>c.id)});for(const c of players)c.roomCode=r.code;checkpoint();save();
  if(!/^\d{6}$/.test(r.code))throw Error('invalid-created-room-code');
  const joiners=await Promise.all(players.slice(1).map(async c=>{const conn=new Connection(c,'/room',r,'room');r.connections.push(conn);await conn.opened;return conn;}));
  for(const conn of joiners)conn.send({type:'joinRoom',code:r.code});
  const starts=await Promise.all(r.connections.map(conn=>conn.wait(m=>m.type==='start')));
  starts.forEach((m,i)=>{players[i].seat=m.seat;});
  if(new Set(players.map(c=>c.seat)).size!==4)throw Error('duplicate-seats');
  const game=await Promise.all(players.map(async c=>{const conn=new Connection(c,`/game?code=${r.code}&seat=${c.seat}`,r,'game');r.connections.push(conn);await conn.opened;await conn.wait(m=>m.type==='welcome');return conn;}));
  await Promise.all(r.connections.filter(c=>c.kind==='room').map(c=>c.close()));
  await Promise.all(game.map(conn=>conn.wait(m=>m.type==='hand_start',45000)));
  const list=await request('/api/records?pageNum=1&pageSize=20',players[0]);
  if(!ok(list))throw Error('initial-record-list-failed');
  r.sessionId=list.json.data?.records?.find(x=>x.roomId===r.code)?.sessionId;
  checkpoint();emit({tableReady:r.code,players:players.map(c=>c.id),sessionId:r.sessionId});return r;
}
function validDetail(data,r,client,final=false){const errors=[];
  if(!data || data.notFound || data.roomId!==r.code || !Array.isArray(data.rounds))return ['invalid-record-detail'];
  const nums=data.rounds.map(x=>x.roundNum);if(new Set(nums).size!==nums.length)errors.push('duplicate-record-round');
  const totals=new Map(r.players.map(c=>[c.id,0]));
  for(const round of data.rounds){const ps=round.participants;if(ps?.length!==4 || new Set(ps.map(p=>p.userId)).size!==4 || ps.some(p=>!totals.has(p.userId))){errors.push('record-participants-mismatch');continue;}
    if(new Set(ps.map(p=>p.seat)).size!==4 || ps.some(p=>seats[p.seat]!==r.players.find(c=>c.id===p.userId).seat))errors.push('record-seats-mismatch');
    if(ps.reduce((s,p)=>s+p.scoreChange,0)!==0)errors.push('record-score-not-zero-sum');
    if(ps.filter(p=>p.isMe).length!==1 || !ps.find(p=>p.userId===client.id)?.isMe)errors.push('record-me-mismatch');
    for(const p of ps)totals.set(p.userId,totals.get(p.userId)+p.scoreChange);
  }
  if(final){
    if(data.rounds.length!==r.rounds.size || nums.some(n=>!r.rounds.has(n)))errors.push('settled-round-count-mismatch');
    const ordered=[...data.rounds].sort((a,b)=>a.roundNum-b.roundNum);
    for(let i=0;i<ordered.length;i++){
      const row=ordered[i],cur=r.rounds.get(row.roundNum),prev=i?r.rounds.get(ordered[i-1].roundNum):null;
      if(row.roundNum!==i+1)errors.push('record-round-sequence-gap');
      if(cur)for(const p of row.participants||[]){const seat=seats[p.seat];if(p.scoreChange!==cur.coins[seat]-(prev?.coins[seat]||0))errors.push('round-score-broadcast-mismatch');}
    }
    const overview=data.overview?.players||[];if(overview.length!==4 && r.rounds.size)errors.push('overview-player-count-mismatch');
    for(const p of overview)if(p.totalScore!==totals.get(p.userId))errors.push('overview-total-mismatch');
    const latest=[...r.rounds.keys()].sort((a,b)=>a-b).at(-1);
    if(latest)for(const c of r.players)if(totals.get(c.id)!==r.rounds.get(latest).coins[c.seat])errors.push('websocket-record-total-mismatch');
  }
  return [...new Set(errors)];
}
function validV2(data,r,overview){
  if(!data||data.sessionId!==r.sessionId||!Number.isSafeInteger(data.readThroughRoundNum)||data.readThroughRoundNum<0)return ['invalid-v2-version'];
  if(overview){
    if(data.roomId!==r.code||data.players?.length!==4||new Set(data.players.map(p=>p.userId)).size!==4||data.players.some(p=>!r.players.some(c=>c.id===p.userId)))return ['invalid-v2-overview-players'];
    if(data.players.reduce((sum,p)=>sum+p.totalScore,0)!==0)return ['v2-overview-not-zero-sum'];
  }else{
    if(!Array.isArray(data.rounds)||data.rounds.length>10||new Set(data.rounds.map(x=>x.roundNum)).size!==data.rounds.length)return ['invalid-v2-page'];
    for(const row of data.rounds){
      if(row.roundNum>data.readThroughRoundNum||row.participants?.length!==4||new Set(row.participants.map(p=>p.userId)).size!==4||row.participants.some(p=>!r.players.some(c=>c.id===p.userId)))return ['invalid-v2-round-players'];
      if(row.participants.reduce((sum,p)=>sum+p.scoreChange,0)!==0)return ['v2-round-not-zero-sum'];
    }
  }
  return [];
}
async function cleanup(r){r.closing=true;for(const t of r.timers)clearTimeout(t);r.timers.clear();
  await Promise.all(r.connections.map(c=>c.close()));await delay(1500);
  if(!r.code){report.cleanup.push({roomId:null,remainingConnections:r.connections.filter(c=>c.ws.readyState!==WebSocket.CLOSED).length});return;}
  const results=await Promise.all(r.players.map(async c=>{const p=await request('/api/room/pending',c);return {userId:c.id,...brief(p),data:p.json?.data};}));
  const checks=[];
  for(const c of r.players){const res=await request(`/api/records/byRoom?roomId=${r.code}`,c);const errors=ok(res)?validDetail(res.json.data,r,c,true):['final-record-request-failed'];checks.push({userId:c.id,...brief(res),rounds:res.json?.data?.rounds?.length,errors});}
  const list=await request('/api/records?pageNum=1&pageSize=20',r.players[0]);const row=list.json?.data?.records?.find(x=>x.roomId===r.code);
  if(row)r.sessionId=row.sessionId;
  const listChecks=[];const latest=[...r.rounds.keys()].sort((a,b)=>a-b).at(-1);const coins=latest?r.rounds.get(latest).coins:{};
  for(const c of r.players){const l=await request('/api/records?pageNum=1&pageSize=20',c);const entry=l.json?.data?.records?.find(x=>x.roomId===r.code);const expected=coins[c.seat]||0;const rank=1+Object.values(coins).filter(v=>v>expected).length;const errors=[];
    if(!ok(l)||!entry)errors.push('final-list-session-missing');else {if(!entry.endTime)errors.push('session-not-ended');if(entry.totalScore!==expected||entry.rank!==rank)errors.push('list-score-or-rank-mismatch');if(entry.sessionId!==r.sessionId||entry.players?.length!==4)errors.push('list-session-or-players-mismatch');}
    listChecks.push({userId:c.id,...brief(l),errors});
  }
  const closed=await request(`/api/room/check?code=${r.code}`,r.players[0]);
  const disbanded=closed.json?.code===400&&/不存在|已解散/.test(closed.json?.message||'');
  const item={roomId:r.code,sessionId:r.sessionId,pending:results,recordChecks:checks,listChecks,sessionEnded:!!row?.endTime,status:row?.status,disbanded,roomCheck:brief(closed),remainingConnections:r.connections.filter(c=>c.ws.readyState!==WebSocket.CLOSED).length};report.cleanup.push(item);emit({cleanup:{roomId:item.roomId,sessionEnded:item.sessionEnded,disbanded:item.disbanded,remainingConnections:item.remainingConnections,recordErrors:checks.reduce((n,c)=>n+c.errors.length,0),listErrors:listChecks.reduce((n,c)=>n+c.errors.length,0)}});return item;
}
async function reconnect(r){
  if(r.finished)return {skipped:'four-winds-already-finished'};
  const c=r.players[0];const old=r.connections.find(x=>x.kind==='game'&&x.client.id===c.id&&x.ws.readyState===WebSocket.OPEN);
  const began=performance.now();await old.close();await delay(500);
  const pending=await request('/api/room/pending',c);
  const conn=new Connection(c,`/game?code=${r.code}&seat=${c.seat}`,r,'game');r.connections.push(conn);await conn.opened;
  await conn.wait(m=>m.type==='welcome');await conn.wait(m=>m.type==='hand_start');await conn.wait(m=>m.type==='board');await conn.wait(m=>m.type==='hand');
  const check={roomId:r.code,userId:c.id,ms:performance.now()-began,pending:brief(pending),pendingRoom:pending.json?.data,restoredSeat:c.seat,snapshotReceived:true};
  if(!ok(pending)||pending.json.data?.exists!==true)r.failures.push('reconnect-pending-room-missing');
  return check;
}
async function stage(count,rps,seconds){
  const s={tables:count,players:count*4,targetHttpRps:rps,plannedSeconds:seconds,startedAt:new Date().toISOString(),handshakes:[],http:{sent:0,completed:0,failed:0,skipped:0,peakInflight:0,endpoints:{},failureSamples:[]}};report.stages.push(s);currentStage=s;await setupAccounts(count*4);
  const active=[];for(let i=0;i<count;i+=4){if(stopped())throw Error(stopReason);const batch=await Promise.allSettled(Array.from({length:Math.min(4,count-i)},(_,j)=>table(clients.slice((i+j)*4,(i+j)*4+4))));for(const b of batch)if(b.status==='fulfilled')active.push(b.value);const failed=batch.find(b=>b.status==='rejected');if(failed)throw failed.reason;}
  const jobs=new Set();const began=performance.now();const lag=monitorEventLoopDelay({resolution:20});lag.enable();let progress=began;let slot=0;
  const interval=1000/rps;let abort;const recentSamples=[];let lastHealthCheck=began,exceedingWindows=0;
  s.windowStartedAt=new Date().toISOString();
  const receivedAtStart=active.reduce((n,r)=>n+(r.receivedWireBytes||0),0),sentAtStart=active.reduce((n,r)=>n+(r.sentWireBytes||0),0);
  while(performance.now()-began<seconds*1000&&!stopped()&&!abort){
    const wait=began+slot*interval-performance.now();if(wait>0)await delay(wait);
    if(performance.now()-began>=seconds*1000)break;
    const index=slot++;const r=active[index%active.length];const c=r.players[Math.floor(index/active.length)%4];const pick=(Math.floor(index/(active.length*4))+index%(active.length*4))%5;
    const name=(v2?['list-v2','overview-v2','rounds-v2','list-page2-v2','pending']:['list','byRoom','detail','list-page2','pending'])[pick];
    const route=v2?(pick===0?'/api/v2/records?pageNum=1&pageSize=20':pick===1?`/api/v2/records/${r.sessionId}/overview`:pick===2?`/api/v2/records/${r.sessionId}/rounds?afterRoundNum=0&pageSize=10`:pick===3?'/api/v2/records?pageNum=2&pageSize=10':'/api/room/pending'):(pick===0?'/api/records?pageNum=1&pageSize=20':pick===1?`/api/records/byRoom?roomId=${r.code}`:pick===2?(r.sessionId?`/api/records/${r.sessionId}`:`/api/records/byRoom?roomId=${r.code}`):pick===3?'/api/records?pageNum=2&pageSize=10':'/api/room/pending');
    if(jobs.size>=inflightCap){s.http.skipped++;continue;}
    s.http.sent++;s.http.peakInflight=Math.max(s.http.peakInflight,jobs.size+1);
    const ep=s.http.endpoints[name]||(s.http.endpoints[name]={sent:0,failed:0,latencies:[],statusCounts:{},codeCounts:{}});ep.sent++;
    const job=request(route,c).then(res=>{s.http.completed++;s.http.responseBytes=(s.http.responseBytes||0)+(res.bytes||0)+(res.headerBytes||0);ep.bodyBytes=(ep.bodyBytes||0)+(res.bytes||0);ep.decodedBytes=(ep.decodedBytes||0)+(res.decodedBytes||0);ep.encodings??={};ep.encodings[res.contentEncoding||'unknown']=(ep.encodings[res.contentEncoding||'unknown']||0)+1;ep.latencies.push(res.ms);ep.statusCounts[res.status]=(ep.statusCounts[res.status]||0)+1;ep.codeCounts[res.json?.code??'none']=(ep.codeCounts[res.json?.code??'none']||0)+1;
      let errors=[];if(!ok(res))errors.push(res.error||`http-${res.status}-business-${res.json?.code}`);
      else if(pick===1||pick===2)errors=v2?validV2(res.json.data,r,pick===1):validDetail(res.json.data,r,c);
      else if(pick===0||pick===3){const d=res.json.data;if(!Array.isArray(d?.records)||!Number.isFinite(d?.total)||d.pageNum!==(pick===3?2:1))errors.push('invalid-record-page');if(pick===0){const row=d?.records?.find(x=>x.roomId===r.code);if(!row)errors.push('own-session-missing-from-list');else r.sessionId=row.sessionId;}}
      if(errors.length){s.http.failed++;ep.failed++;if(s.http.failureSamples.length<12)s.http.failureSamples.push({endpoint:name,errors,...brief(res)});}
      const at=performance.now();recentSamples.push({at,ms:res.ms,failed:errors.length>0});while(recentSamples.length&&recentSamples[0].at<at-20000)recentSamples.shift();
    }).finally(()=>jobs.delete(job));jobs.add(job);
    const failures=active.flatMap(r=>r.failures);if(failures.length)abort=failures[0];
    if(active.some(r=>performance.now()-r.lastMessage>45000))abort='table-no-message-for-45-seconds';
    if(s.http.completed>=30 && s.http.failed/s.http.completed>.05)abort='http-error-rate-over-5-percent';
    if(performance.now()-lastHealthCheck>=5000&&performance.now()-began>=20000){
      lastHealthCheck=performance.now();const recent=recentSamples.filter(x=>x.at>=lastHealthCheck-20000);
      const bad=recent.filter(x=>x.failed).length,p95=pct(recent.map(x=>x.ms),.95);s.http.lastRecentWindow={seconds:20,n:recent.length,failed:bad,errorRate:recent.length?bad/recent.length:0,p95Ms:p95};
      if(recent.length>=100&&(p95>5000||bad/recent.length>.05))exceedingWindows++;else exceedingWindows=0;
      if(exceedingWindows>=2)abort='sustained-20second-http-window-unhealthy';
    }
    if(performance.now()-progress>20000){progress=performance.now();emit({progress:{tables:count,elapsed:Math.round((progress-began)/1000),httpCompleted:s.http.completed,httpFailed:s.http.failed,rounds:active.map(r=>r.rounds.size),discards:active.map(r=>r.discardsSent),wsFailures:failures.length}});}
  }
  s.emissionSeconds=(performance.now()-began)/1000;await Promise.allSettled([...jobs]);s.windowEndedAt=new Date().toISOString();
  s.traffic={seconds:(performance.now()-began)/1000,httpBodyAndHeadersBytes:s.http.responseBytes||0,gameReceivedFrameBytes:active.reduce((n,r)=>n+(r.receivedWireBytes||0),0)-receivedAtStart,gameSentFrameBytes:active.reduce((n,r)=>n+(r.sentWireBytes||0),0)-sentAtStart};
  s.traffic.serverApplicationBitsPerSecond=(s.traffic.httpBodyAndHeadersBytes+s.traffic.gameReceivedFrameBytes)*8/s.traffic.seconds;s.traffic.perPlayerBitsPerSecond=s.traffic.serverApplicationBitsPerSecond/(count*4);
  if(!abort&&!stopReason){
    for(const r of active)r.freezeAfterRound=true;
    const drain=performance.now();
    while(active.some(r=>!r.frozen)&&performance.now()-drain<120000&&!stopReason){
      await delay(1000);if(stopped())break;
      if(active.some(r=>r.failures.length)){abort='game-failed-during-round-drain';break;}
      if(performance.now()-progress>20000){progress=performance.now();emit({finishingCurrentHands:{tables:count,rounds:active.map(r=>r.rounds.size),frozen:active.map(r=>!!r.frozen)}});}
    }
    if(active.some(r=>!r.frozen)&&!abort)abort='round-drain-timeout';
    s.roundDrainSeconds=(performance.now()-drain)/1000;
  }
  lag.disable();s.elapsedSeconds=(performance.now()-began)/1000;s.clientEventLoopP99Ms=lag.percentile(99)/1e6;
  s.http.actualRps=s.http.completed/s.emissionSeconds;s.http.errorRate=s.http.completed?s.http.failed/s.http.completed:1;
  for(const ep of Object.values(s.http.endpoints)){ep.timing=summary(ep.latencies);delete ep.latencies;}
  s.handshakeTiming=summary(s.handshakes);delete s.handshakes;
  if(!abort&&!stopReason && count===1){s.reconnect=await reconnect(active[0]);emit({reconnect:s.reconnect});}
  for(let i=0;i<active.length;i+=4)await Promise.all(active.slice(i,i+4).map(cleanup));
  s.rooms=active.map(r=>({roomId:r.code,sessionId:r.sessionId,settledHands:r.rounds.size,handsStarted:r.handStarts.size,finishedFourWinds:!!r.finished||!!r.completedBlocks?.size,completedBlocks:r.completedBlocks?.size||0,rematches:r.rematches||0,requests:r.requests,actions:r.actions,messages:r.messageCounts,discardsSent:r.discardsSent,broadcast:summary(r.broadcastTimes),ownDiscard:summary(r.ownTimes),think:summary(r.thinkTimes),roundDuration:summary(r.roundDurations),duplicateRequests:r.duplicateRequests,unmatchedBroadcasts:r.unmatchedBroadcasts,pendingDiscardDeliveries:r.pendingDiscards.size,failures:r.failures}));
  s.abort=abort||stopReason||null;
  const finalErrors=report.cleanup.filter(x=>active.some(r=>r.code===x.roomId)).some(x=>x.remainingConnections||!x.sessionEnded||!x.disbanded||x.recordChecks.some(c=>c.errors.length)||x.listChecks.some(c=>c.errors.length)||x.pending.some(c=>c.code!==200||c.data?.exists!==false));
  s.pass=!s.abort&&s.http.skipped===0&&s.http.errorRate<.01&&Object.values(s.http.endpoints).every(ep=>ep.timing.p95Ms<3000)&&active.every(r=>r.rounds.size>0&&r.failures.length===0&&r.pendingDiscards.size===0&&pct(r.broadcastTimes,.95)<3000)&&!finalErrors;
  s.finishedAt=new Date().toISOString();emit({stageResult:{tables:s.tables,pass:s.pass,http:s.http,settled:s.rooms.map(r=>r.settledHands),broadcastP95:s.rooms.map(r=>r.broadcast.p95Ms),abort:s.abort}});if(!s.pass)stopReason=s.abort||'stage-acceptance-failed';return s;
}
try{
  for(let i=0;i<3;i++){const r=await request('/',null,undefined,12000);report.baseline.push({...brief(r),htmlApp:r.htmlApp});emit({baseline:report.baseline.at(-1)});if(r.error||r.status!==200||!r.htmlApp)throw Error('homepage-baseline-failed');await delay(500);}
  for(const count of tiers){await stage(count,count*Number(process.env.TEST_RPS_PER_TABLE||2.5),duration);if(stopReason)break;await delay(15000);}
}catch(e){stopReason=e.message;emit({stopped:stopReason});}
finally{
  for(const r of rooms.filter(r=>!r.closing)){try{await cleanup(r);}catch{report.cleanup.push({roomId:r.code,error:'cleanup-failed'});}}
  await Promise.allSettled([...sockets].map(c=>c.close()));
  if(clients.length && process.env.TEST_AUDIT_ROOMS){report.previousRoomAudits=[];for(const code of process.env.TEST_AUDIT_ROOMS.split(',')){if(!/^\d{6}$/.test(code))continue;const res=await request(`/api/room/check?code=${code}`,clients[0]);report.previousRoomAudits.push({roomId:code,...brief(res),message:res.json?.message,disbanded:res.json?.code===400&&res.json?.message?.includes('不存在或已解散')});}}
  for(let i=0;i<3;i++){const r=await request('/',null,undefined,12000);report.health.push({...brief(r),htmlApp:r.htmlApp});await delay(1000);}
  for(const c of clients){c.token=undefined;}
  for(const agent of Object.values(agents))agent.destroy();report.stopReason=stopReason||null;report.finishedAt=new Date().toISOString();save();emit({finished:report.finishedAt,stopReason:report.stopReason,report:path.join(out,'results.json')});
}
