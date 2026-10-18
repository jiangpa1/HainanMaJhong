// Generate private configs and candidate env fragments. Does not connect, deploy or load-test.
import fs from 'node:fs';
import path from 'node:path';
import {validateConfig} from './test-config.mjs';
const args=process.argv.slice(2),get=name=>args.includes(name)?args[args.indexOf(name)+1]:undefined;
if(!get('--config')||!get('--out'))throw Error('Usage: node generate-boundary-plan.mjs --config config.local.json --out private/boundary-plan-001');
const source=path.resolve(get('--config')),out=path.resolve(get('--out'));
if(fs.existsSync(out)&&fs.readdirSync(out).length)throw Error('Plan directory must be new or empty');
const raw=JSON.parse(fs.readFileSync(source,'utf8'));
raw.accountsFile=path.resolve(path.dirname(source),raw.accountsFile);
if(!raw.metricsUrl)throw Error('metricsUrl is required for success-histogram evidence');
const configs=[],budgets=[{name:'baseline-800',rps:800,burst:100},{name:'candidate-300',rps:300,burst:30},{name:'candidate-240',rps:240,burst:24},{name:'candidate-200',rps:200,burst:20}];
for(const budget of budgets)for(const retries of [0,4]){
  const name=`${budget.name}-retry-${retries}`;
  const config={...raw,accountsFile:raw.accountsFile,outputDirectory:path.join(out,'runs',name),tables:[80],seconds:300,rpsPerTable:5,
    acceptanceProfile:'overload',pushMode:'full',allowRegistration:false,formalReadRetries:retries,preparationReadRetries:4,rateSteps:[],roomBatchSize:4,roomBatchDelayMs:0};
  validateConfig(config,out);configs.push({name,config});
}
const recovery={...raw,outputDirectory:path.join(out,'runs','recovery-320'),tables:[80],seconds:300,rpsPerTable:2.5,
  acceptanceProfile:'p2',pushMode:'full',allowRegistration:false,formalReadRetries:0,preparationReadRetries:4,rateSteps:[],roomBatchSize:4,roomBatchDelayMs:0};
validateConfig(recovery,out);configs.push({name:'recovery-320',config:recovery});
const cold={...recovery,outputDirectory:path.join(out,'runs','cold-ramp-320'),roomBatchSize:1,roomBatchDelayMs:1500,
  rateSteps:[{seconds:60,rpsPerTable:.5},{seconds:60,rpsPerTable:1.25},{seconds:180,rpsPerTable:2.5}]};
validateConfig(cold,out);configs.push({name:'cold-ramp-320',config:cold});
fs.mkdirSync(out,{recursive:true});
for(const {name,config} of configs)fs.writeFileSync(path.join(out,name+'.json'),JSON.stringify(config,null,2)+'\n',{mode:0o600});
for(const b of budgets)fs.writeFileSync(path.join(out,b.name+'.env'),`# Experiment candidate only; keep concurrency=48 and all other settings unchanged.\nRECORDS_RPS=${b.rps}\nRECORDS_BURST=${b.burst}\n`,{mode:0o600});
fs.writeFileSync(path.join(out,'PLAN.md'),`# Boundary experiment plan\n\nNo run or deployment has been executed.\n\n- Fixed 320 players, 80 tables. @5 => 400 total GET/s, about 320 records GET/s.\n- Compare 800/100, 300/30, 240/24, 200/20; concurrency stays 48. Candidates are unverified.\n- For each budget: deploy only the two explicit env keys, restart and warm up consistently, run retry-0 and retry-4 in alternating order. Re-warm after every restart.\n- Run recovery-320 in a NEW directory after each overload run, on the same process without restart. Clone configs with unique run directories for repetitions.\n- Cold-ramp-320 is a separate fresh-process experiment: gradual room creation plus query rate ramp; preserve preparation CPU too.\n- Require accepted client attempt AND server success-histogram P95<=500ms/P99<=2s, real rejection observed, no unexpected failures, and clean settlements/cleanup.\n- Never promote a budget based on one round. Stop at resource guards; do not lower guards to obtain pass.\n- This fixed-room comparison isolates read rate; it does not reproduce the old 512-player game-engine load.\n\nUse node run-capacity.mjs --config <file> --check before an explicitly authorized run.\n`);
console.log(`Generated ${configs.length} configurations and ${budgets.length} candidate env fragments. No server contacted.`);
