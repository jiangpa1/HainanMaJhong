// 准入保护行为验证：① 并发登录爆发 ② 并发战绩查询爆发
// 用法: node burst-admission.mjs [loginBurst] [recordsBurst]
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const here = path.dirname(fileURLToPath(import.meta.url));
const accounts = JSON.parse(fs.readFileSync(path.join(here, 'private', 'accounts.json'), 'utf8'));
const loginBurst = Number(process.argv[2] || 60);
const recordsBurst = Number(process.argv[3] || 200);
const base = 'http://jiangpahnmj.cn';

async function post(url, body, token) {
  const t0 = performance.now();
  const res = await fetch(base + url, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token ? { authorization: 'Bearer ' + token } : {}) },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch {}
  return { status: res.status, retryAfter: res.headers.get('retry-after'), code: json?.code, ms: performance.now() - t0, json };
}
async function get(url, token) {
  const t0 = performance.now();
  const res = await fetch(base + url, { headers: { ...(token ? { authorization: 'Bearer ' + token } : {}) } });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch {}
  return { status: res.status, retryAfter: res.headers.get('retry-after'), code: json?.code, ms: performance.now() - t0, json };
}
const tally = list => list.reduce((a, r) => { const k = `${r.status}${r.code !== undefined && r.code !== r.status ? '/code' + r.code : ''}`; a[k] = (a[k] || 0) + 1; return a; }, {});

// ① 并发登录爆发
console.log(`=== ① ${loginBurst} 个并发登录 ===`);
const t0 = performance.now();
const results = await Promise.all(Array.from({ length: loginBurst }, (_, i) => {
  const c = accounts[i % accounts.length];
  return post('/api/login', { username: c.username, password: c.password }).catch(e => ({ status: 'ERR', code: e.message, ms: 0 }));
}));
const elapsed = ((performance.now() - t0) / 1000).toFixed(2);
console.log('状态分布:', JSON.stringify(tally(results)));
console.log('Retry-After 取值:', JSON.stringify([...new Set(results.map(r => r.retryAfter))]));
console.log('耗时:', elapsed, 's   最慢单个:', Math.round(Math.max(...results.map(r => r.ms))) + 'ms');
const win = results.filter(r => r.status === 200);
console.log('成功登录数:', win.length);
const token = win[0]?.json?.data?.accessToken;

// ② 并发战绩查询爆发
if (token) {
  console.log(`\n=== ② ${recordsBurst} 个并发战绩查询（同一用户 token）===`);
  const t1 = performance.now();
  const rres = await Promise.all(Array.from({ length: recordsBurst }, (_, i) =>
    get(`/api/v2/records?pageNum=1&pageSize=20&_=${i}`, token).catch(e => ({ status: 'ERR', code: e.message, ms: 0 }))));
  console.log('状态分布:', JSON.stringify(tally(rres)));
  console.log('Retry-After 取值:', JSON.stringify([...new Set(rres.map(r => r.retryAfter))]));
  console.log('耗时:', ((performance.now() - t1) / 1000).toFixed(2), 's   成功:', rres.filter(r => r.status === 200).length);
} else {
  console.log('（未取得 token，跳过战绩爆发）');
}
