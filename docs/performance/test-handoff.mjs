import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
import {validateConfig} from './test-config.mjs';
import {summarize} from './summary.mjs';
test('explicit target, independent accounts, fresh output and bounded load',()=> {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mahjong-config-'));
  try {
    const c={baseUrl:'http://127.0.0.1:8080',accountsFile:'accounts.json',outputDirectory:'results',tables:[1],seconds:30};
    assert.throws(()=>validateConfig(c,root),/Not enough/);
    fs.writeFileSync(path.join(root,'accounts.json'),JSON.stringify(Array.from({length:4},(_,i)=>({id:i+1,username:'test'+i,password:'test-only'}))));
    assert.equal(validateConfig(c,root).accountsAvailable,4);
    assert.throws(()=>validateConfig({...c,baseUrl:'http://user:secret@localhost'},root));
    assert.throws(()=>validateConfig({...c,tables:[65]},root));
    assert.throws(()=>validateConfig({...c,seconds:1801},root));
    assert.throws(()=>validateConfig({...c,password:'must-not-export'},root),/Unknown config/);
    assert.throws(()=>validateConfig({...c,accountsFile:'results/accounts.json'},root),/outside/);
    fs.mkdirSync(path.join(root,'results'));fs.writeFileSync(path.join(root,'results','results.json'),'{}');
    assert.throws(()=>validateConfig(c,root),/never overwrite/);
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});
test('historical failures remain failures and worst endpoint is reported',()=> {
  const rows=summarize({stopReason:'cleanup-failed',stages:[{pass:false,tables:48,players:192,http:{endpoints:{a:{timing:{p95Ms:3,p99Ms:5}},b:{timing:{p95Ms:40,p99Ms:60}}}},rooms:[]}]});
  assert.equal(rows[0].pass,false);assert.equal(rows[0].httpP95Ms,40);assert.equal(rows[0].stopReason,'cleanup-failed');
});
test('local failed run exits nonzero, preserves report and never exports credentials',()=> {
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'mahjong-run-'));
  try {
    const secret='unique-private-fixture-password';
    fs.writeFileSync(path.join(root,'accounts.json'),JSON.stringify(Array.from({length:4},(_,i)=>({id:i+1,username:'fixture'+i,password:secret}))));
    fs.writeFileSync(path.join(root,'config.json'),JSON.stringify({baseUrl:'http://127.0.0.1:1',accountsFile:'accounts.json',outputDirectory:'results',tables:[1],seconds:30}));
    const run=spawnSync(process.execPath,[fileURLToPath(new URL('./run-capacity.mjs',import.meta.url)),'--config',path.join(root,'config.json')],
      {encoding:'utf8',timeout:15000,windowsHide:true});
    assert.equal(run.status,1); assert.ok(!run.stdout.includes(secret)); assert.ok(!run.stderr.includes(secret));
    const report=fs.readFileSync(path.join(root,'results/results.json'),'utf8');
    assert.ok(!report.includes(secret)); assert.equal(JSON.parse(report).stopReason,'homepage-baseline-failed');
    assert.ok(fs.existsSync(path.join(root,'results/monitor-done.json')));
  } finally {fs.rmSync(root,{recursive:true,force:true});}
});
