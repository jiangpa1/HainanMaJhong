import assert from 'node:assert/strict';
import http from 'node:http';
import { gzipSync } from 'node:zlib';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const body = Buffer.from(JSON.stringify({ code: 200, data: Array.from({ length: 10 }, (_, n) => ({ round: n, nickname: '测试玩家', scores: [6, -2, -2, -2] })) }));
// Make the response eligible for the configured minimum length.
const large = Buffer.from(JSON.stringify({ code: 200, data: body.toString().repeat(4) }));
let mode = 'good'; let calls = 0;
const server = http.createServer((req, res) => {
  calls++;
  assert.equal(req.headers.authorization, 'Bearer synthetic-probe-test');
  const useGzip = req.headers['accept-encoding'] === 'gzip' && mode !== 'missing-gzip';
  const raw = mode === 'changed' && useGzip ? Buffer.from('{"code":200,"data":"changed"}') : large;
  const data = useGzip ? gzipSync(raw) : raw;
  res.writeHead(200, { 'Content-Type': 'application/json', 'Content-Length': data.length,
    'Cache-Control': 'private, no-store', Vary: 'Accept-Encoding',
    ...(useGzip ? { 'Content-Encoding': 'gzip' } : {}) });
  res.end(data);
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`;
const probe = () => new Promise((resolve, reject) => {
  const child = spawn(process.execPath, [fileURLToPath(new URL('./verify-records-gzip.mjs', import.meta.url)), base,
    '/api/v2/records/42/rounds?afterRoundNum=0&pageSize=10&readThroughRoundNum=10'], {
    env: { ...process.env, RECORDS_GZIP_TOKEN: 'synthetic-probe-test' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let out = '', err = '';
  child.stdout.on('data', b => { out += b }); child.stderr.on('data', b => { err += b });
  child.on('error', reject); child.on('close', code => resolve({ code, out, err }));
});
try {
  let result = await probe(); assert.equal(result.code, 0, result.err);
  const report = JSON.parse(result.out);
  assert.equal(report.pairs.length, 3); assert.equal(calls, 6);
  assert.ok(report.pairs.every(p => p.savedPercent > 50 && p.identity.sha256 === p.gzip.sha256));
  assert.ok(!result.out.includes('synthetic-probe-test') && !result.out.includes('测试玩家'));
  mode = 'missing-gzip'; result = await probe(); assert.notEqual(result.code, 0); assert.match(result.err, /not gzipped/);
  mode = 'changed'; result = await probe(); assert.notEqual(result.code, 0); assert.match(result.err, /Decoded results differ/);
  console.log('在线 gzip 探针通过：真实 HTTP 解码比对、压缩缺失/数据不一致检测、凭据不写入报告。');
} finally { await new Promise(resolve => server.close(resolve)); }
