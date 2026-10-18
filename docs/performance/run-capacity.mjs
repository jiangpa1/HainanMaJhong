import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { validateConfig } from './test-config.mjs';
const args=process.argv.slice(2),index=args.indexOf('--config');
if(index<0||!args[index+1])throw Error('Usage: node run-capacity.mjs --config config.local.json [--check]');
const configPath=path.resolve(args[index+1]);
const config=validateConfig(JSON.parse(fs.readFileSync(configPath,'utf8')),path.dirname(configPath));
if(process.env.TEST_PUSH_MODE) {
  if(!['full','delta'].includes(process.env.TEST_PUSH_MODE))throw Error('TEST_PUSH_MODE must be full or delta');
  config.pushMode=process.env.TEST_PUSH_MODE;
}
if(args.includes('--check')) {
  console.log(JSON.stringify({baseUrl:config.baseUrl,tables:config.tables,players:Math.max(...config.tables)*4,seconds:config.seconds,
    encoding:config.encoding,recordsVersion:config.recordsVersion,acceptanceProfile:config.acceptanceProfile,pushMode:config.pushMode,
    maxUnmatchedBroadcastRate:config.maxUnmatchedBroadcastRate,
    loginIntervalMs:config.loginIntervalMs,
    formalReadRetries:config.formalReadRetries,preparationReadRetries:config.preparationReadRetries,rateSteps:config.rateSteps,
    roomBatchSize:config.roomBatchSize,roomBatchDelayMs:config.roomBatchDelayMs,
    accountsAvailable:config.accountsAvailable,allowRegistration:config.allowRegistration},null,2));
} else {
  fs.mkdirSync(config.outputDirectory,{recursive:true});
  const inherited={...process.env};
  for(const key of Object.keys(inherited))if(key.startsWith('TEST_')||key==='WS_MODULE')delete inherited[key];
  const env={...inherited,TEST_BASE:config.baseUrl,TEST_TABLES:config.tables.join(','),TEST_STAGE_SECONDS:String(config.seconds),
    TEST_ACCEPT_ENCODING:config.encoding,TEST_RECORDS_VERSION:config.recordsVersion,TEST_ACCEPTANCE_PROFILE:config.acceptanceProfile,
    TEST_PUSH_MODE:config.pushMode,TEST_MAX_UNMATCHED_BROADCAST_RATE:String(config.maxUnmatchedBroadcastRate),TEST_MAX_SEQUENCE_GAPS:String(config.maxSequenceGaps),
    TEST_RPS_PER_TABLE:String(config.rpsPerTable),TEST_OUT:config.outputDirectory,TEST_STOP_FILE:path.join(config.outputDirectory,'resource-stop.json'),
    TEST_ALLOW_REGISTRATION:config.allowRegistration?'1':'0',TEST_PRIVATE_JSON:config.accountsFile,
    TEST_LOGIN_INTERVAL_MS:String(config.loginIntervalMs),
    TEST_FORMAL_READ_RETRIES:String(config.formalReadRetries),TEST_429_RETRIES:String(config.preparationReadRetries),
    TEST_RATE_STEPS:JSON.stringify(config.rateSteps),TEST_ROOM_BATCH_SIZE:String(config.roomBatchSize),TEST_ROOM_BATCH_DELAY_MS:String(config.roomBatchDelayMs),
    TEST_METRICS_URL:config.metricsUrl||'',
    TEST_NO_VAULT:'1',TEST_CONNECT_IP:config.connectIp||'',TEST_HOST:config.hostHeader||''};
  delete env.TEST_REUSE_VAULT; delete env.TEST_INITIAL_JSON;
  if(fs.existsSync(config.accountsFile))env.TEST_INITIAL_JSON=config.accountsFile;
  fs.mkdirSync(path.dirname(config.accountsFile),{recursive:true});
  fs.writeFileSync(path.join(config.outputDirectory,'run-config.json'),JSON.stringify({...config,accountsFile:'[private file omitted]'},null,2));
  if(config.metricsUrl) {
    const monitor=spawn(process.execPath,[fileURLToPath(new URL('./monitor.mjs',import.meta.url)),'--url',config.metricsUrl,'--out',config.outputDirectory],{stdio:'inherit',windowsHide:true});
    const failed=()=>fs.writeFileSync(path.join(config.outputDirectory,'resource-stop.json'),JSON.stringify({reason:'monitor-process-failed'}));
    monitor.on('error',failed);
    monitor.on('exit',()=> { if(!fs.existsSync(path.join(config.outputDirectory,'monitor-done.json')))failed(); });
  }
  const child=spawn(process.execPath,[fileURLToPath(new URL('./online-capacity-retest.mjs',import.meta.url))],{env,stdio:'inherit',windowsHide:true});
  process.on('SIGINT',()=>child.kill('SIGINT'));
  child.on('error',()=>{console.error('Unable to start load-test process');process.exitCode=1;});
  child.on('exit',code=>{
    fs.writeFileSync(path.join(config.outputDirectory,'monitor-done.json'),JSON.stringify({finishedAt:new Date().toISOString()}));
    process.exitCode=code??1;
  });
}
