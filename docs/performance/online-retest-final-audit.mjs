import fs from 'node:fs';
import http from 'node:http';
import {spawnSync} from 'node:child_process';
const root='docs/performance/results-2026-10-11-retest';
const r=JSON.parse(fs.readFileSync(root+'/v2-gzip-192-retry/results.json','utf8'));
const failed=r.cleanup.flatMap(x=>x.pending.filter(p=>p.code!==200||p.data?.exists!==false).map(p=>({roomId:x.roomId,userId:p.userId,originalError:p.error})));
const decrypt='Add-Type -AssemblyName System.Security; $bytes=[Convert]::FromBase64String([Console]::In.ReadToEnd()); $plain=[System.Security.Cryptography.ProtectedData]::Unprotect($bytes,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); [Console]::Out.Write([System.Text.Encoding]::UTF8.GetString($plain))';
const child=spawnSync('powershell.exe',['-NoProfile','-NonInteractive','-Command',decrypt],{input:fs.readFileSync(process.env.TEST_REUSE_VAULT).toString('base64'),encoding:'utf8',windowsHide:true,maxBuffer:1e7});
if(child.status!==0)throw Error('private-recovery-failed');
const accounts=JSON.parse(child.stdout);
async function request(route,token,body){return new Promise(resolve=>{
 const payload=body?JSON.stringify(body):undefined;
 const headers={Host:'jiangpahnmj.cn',Accept:'application/json','Accept-Encoding':'identity',Connection:'close'};
 if(token)headers.Authorization='Bearer '+token;
 if(payload){headers['Content-Type']='application/json';headers['Content-Length']=Buffer.byteLength(payload);}
 const req=http.request({hostname:'39.107.247.119',port:80,path:route,method:payload?'POST':'GET',headers,agent:false},res=>{
  let text='';res.on('data',b=>{text+=b;if(text.length>1e6)req.destroy(Error('response-too-large'));});res.on('error',()=>resolve({error:'response-error'}));res.on('end',()=>{try{resolve({status:res.statusCode,...JSON.parse(text)});}catch{resolve({error:'invalid-json'});}});
 });const timer=setTimeout(()=>req.destroy(Error('timeout')),12000);req.on('close',()=>clearTimeout(timer));req.on('error',e=>resolve({error:e.code||e.message}));req.end(payload);
});}
const results=[];
for(const x of failed){
 const c=accounts.find(a=>a.id===x.userId);if(!c)throw Error('account-missing');
 const login=await request('/api/login',null,{username:c.username,password:c.password});
 if(login.status!==200||login.code!==200||!login.data?.accessToken)throw Error('audit-login-failed');
 const me=await request('/api/user/me',login.data.accessToken);
 if(me.data?.id!==c.id)throw Error('audit-identity-mismatch');
 const pending=await request('/api/room/pending',login.data.accessToken);
 results.push({...x,status:pending.status,code:pending.code,error:pending.error,pendingExists:pending.data?.exists,verified:pending.status===200&&pending.code===200&&pending.data?.exists===false});
}
const report={at:new Date().toISOString(),kind:'independent-post-test-read',rawStagePass:r.stages[0].pass,notes:'Original failed cleanup reads and strict acceptance result remain unchanged; no load or room creation.',checks:results,allVerified:results.every(x=>x.verified)};
fs.writeFileSync(root+'/supplemental-pending-audit.json',JSON.stringify(report,null,2));console.log(JSON.stringify(report));
