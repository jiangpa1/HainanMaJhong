import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
export function validateConfig(raw,root) {
  const allowed=new Set(['baseUrl','accountsFile','outputDirectory','tables','seconds','encoding','recordsVersion','rpsPerTable','acceptanceProfile','allowRegistration','connectIp','hostHeader','metricsUrl','pushMode','maxUnmatchedBroadcastRate','maxSequenceGaps','loginIntervalMs','formalReadRetries','preparationReadRetries','rateSteps','roomBatchSize','roomBatchDelayMs']);
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||Object.keys(raw).some(key=>!allowed.has(key)))throw Error('Unknown config field; credentials belong only in the separate accounts file');
  const c={tables:[1],seconds:30,encoding:'gzip',recordsVersion:'v2',rpsPerTable:2.5,acceptanceProfile:'p2',allowRegistration:false,pushMode:'full',maxUnmatchedBroadcastRate:0.001,maxSequenceGaps:0,loginIntervalMs:1100,formalReadRetries:0,preparationReadRetries:4,rateSteps:[],roomBatchSize:4,roomBatchDelayMs:0,...raw};
  for(const key of ['formalReadRetries','preparationReadRetries']) if(!Number.isInteger(c[key])||c[key]<0||c[key]>4)throw Error(key+' must be 0..4');
  if(!Number.isInteger(c.roomBatchSize)||c.roomBatchSize<1||c.roomBatchSize>4||!Number.isInteger(c.roomBatchDelayMs)||c.roomBatchDelayMs<0||c.roomBatchDelayMs>60000)throw Error('Invalid room creation pacing');
  if(!Number.isInteger(c.loginIntervalMs)||c.loginIntervalMs<0||c.loginIntervalMs>60000)throw Error('loginIntervalMs must be 0..60000');
  // 帧完整性判据：协议级序号缺口（服务端每帧盖章）。0 = 不允许任何缺口/重复/缺帧。
  if(!Number.isInteger(c.maxSequenceGaps)||c.maxSequenceGaps<0)throw Error('maxSequenceGaps must be a non-negative integer');
  // 未匹配广播率已降级为【信息项】，不再作为通过/失败判据（其计数受工具跨连接记账影响，见 docs/性能测试工具已知偏差.md）。
  if(!Number.isFinite(c.maxUnmatchedBroadcastRate)||c.maxUnmatchedBroadcastRate<0||c.maxUnmatchedBroadcastRate>1)throw Error('maxUnmatchedBroadcastRate must be 0..1');
  if(!['full','delta'].includes(c.pushMode))throw Error('pushMode must be full or delta');
  const base=new URL(c.baseUrl);
  if(!['http:','https:'].includes(base.protocol)||base.username||base.password||base.search||base.hash||base.pathname!=='/')throw Error('baseUrl must be an explicit HTTP(S) origin');
  if(!Array.isArray(c.tables)||!c.tables.length||c.tables.some(n=>!Number.isInteger(n)||n<1||n>512))throw Error('tables must contain integers 1..512');
  if(!Number.isInteger(c.seconds)||c.seconds<30||c.seconds>1800)throw Error('seconds must be 30..1800');
  if(!['identity','gzip'].includes(c.encoding)||!['legacy','v2'].includes(c.recordsVersion)||!['baseline','p2','overload'].includes(c.acceptanceProfile))throw Error('Invalid encoding/version/profile');
  if(!Number.isFinite(c.rpsPerTable)||c.rpsPerTable<=0||c.rpsPerTable>5)throw Error('rpsPerTable must be >0 and <=5');
  if(!Array.isArray(c.rateSteps)||c.rateSteps.some(s=>!s||Object.keys(s).some(k=>!['seconds','rpsPerTable'].includes(k))||!Number.isInteger(s.seconds)||s.seconds<1||!Number.isFinite(s.rpsPerTable)||s.rpsPerTable<=0||s.rpsPerTable>5)
    ||(c.rateSteps.length&&c.rateSteps.reduce((n,s)=>n+s.seconds,0)!==c.seconds))throw Error('rateSteps must be bounded and sum exactly to seconds');
  if(c.acceptanceProfile==='overload'&&(!c.metricsUrl||c.rateSteps.length))throw Error('overload requires metricsUrl and a fixed-rate window; run recovery separately');
  if(typeof c.allowRegistration!=='boolean')throw Error('allowRegistration must be boolean');
  if(typeof c.accountsFile!=='string'||typeof c.outputDirectory!=='string')throw Error('accountsFile and outputDirectory are required');
  c.accountsFile=path.resolve(root,c.accountsFile);c.outputDirectory=path.resolve(root,c.outputDirectory);
  const relative=path.relative(c.outputDirectory,c.accountsFile);
  if(relative===''||(!relative.startsWith('..'+path.sep)&&relative!=='..'&&!path.isAbsolute(relative)))throw Error('Private accounts file must be outside results directory');
  if(fs.existsSync(c.outputDirectory)&&fs.readdirSync(c.outputDirectory).length)throw Error('Output directory must be new or empty; never overwrite an old run');
  if(c.connectIp&&net.isIP(c.connectIp)!==4)throw Error('connectIp must be IPv4');
  if(c.hostHeader&&(!/^[a-zA-Z0-9.-]+(?::\d+)?$/.test(c.hostHeader)))throw Error('Invalid hostHeader');
  if(c.metricsUrl) {
    const metrics=new URL(c.metricsUrl);
    if(!['http:','https:'].includes(metrics.protocol)||metrics.username||metrics.password||metrics.search||metrics.hash)throw Error('Invalid metricsUrl');
  }
  const accounts=fs.existsSync(c.accountsFile)?JSON.parse(fs.readFileSync(c.accountsFile,'utf8')):[];
  if(!Array.isArray(accounts)||accounts.some(a=>typeof a.username!=='string'||!a.username||typeof a.password!=='string'||!a.password||!Number.isSafeInteger(a.id)||a.id<=0)
      ||new Set(accounts.map(a=>a.id)).size!==accounts.length||new Set(accounts.map(a=>a.username)).size!==accounts.length)throw Error('Accounts must have unique username, password and positive numeric id');
  if(!c.allowRegistration&&accounts.length<Math.max(...c.tables)*4)throw Error('Not enough dedicated accounts; provide a private accounts file or explicitly allowRegistration');
  c.accountsAvailable=accounts.length;c.baseUrl=base.origin;return c;
}
