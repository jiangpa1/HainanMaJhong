// Route-histogram integration check.
//
// Proves that CapacityHttpFilter records a `success` sample for every route the capacity tool relies on,
// including the two session-scoped templates (`/api/v2/records/{sessionId}/overview|rounds`).
//
// Usage:
//   node route-histogram-check.mjs [--base http://jiangpahnmj.cn] [--metrics http://127.0.0.1:9091/actuator/prometheus]
//                                  [--accounts ..\private\jmeter-accounts.csv]
//
// Requires the 9091 tunnel to be open (the metrics endpoint is bound to 127.0.0.1 on the server).
// Exit code 0 = every expected route recorded at least one success sample.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
};

const base = arg('base', 'http://jiangpahnmj.cn');
const metricsUrl = arg('metrics', 'http://127.0.0.1:9091/actuator/prometheus');
const accountsFile = arg('accounts', join(here, 'private', 'jmeter-accounts.csv'));

function firstAccount() {
  const line = readFileSync(accountsFile, 'utf8').split(/\r?\n/).find((l, i) => i > 0 && l.trim());
  if (!line) throw new Error(`no account rows in ${accountsFile}`);
  const [username, password] = line.split(',');
  return { username, password };
}

async function call(route, token) {
  const res = await fetch(new URL(route, base), {
    headers: token ? { Authorization: `Bearer ${token}` } : {},
  });
  const text = await res.text();
  let code = null;
  try { code = JSON.parse(text).code ?? null; } catch { /* non-JSON */ }
  return { status: res.status, code, text };
}

function parseRoutes(text) {
  const out = new Map();
  for (const line of text.split('\n')) {
    if (!line.startsWith('mahjong_http_response_seconds_count')) continue;
    const route = /route="([^"]*)"/.exec(line)?.[1];
    const outcome = /outcome="([^"]*)"/.exec(line)?.[1];
    const value = Number(line.trim().split(/\s+/).pop());
    if (!route || !outcome || !Number.isFinite(value)) continue;
    out.set(`${route}|${outcome}`, (out.get(`${route}|${outcome}`) || 0) + value);
  }
  return out;
}

const { username, password } = firstAccount();
const loginBody = await (await fetch(new URL('/api/login', base), {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=UTF-8' },
  body: JSON.stringify({ username, password }),
})).text();
const token = JSON.parse(loginBody).data?.accessToken;
if (!token) throw new Error(`login failed: ${loginBody.slice(0, 160)}`);

const list = await call('/api/v2/records?pageNum=1&pageSize=20', token);
const sessionId = JSON.parse(list.text).data?.records?.[0]?.sessionId ?? JSON.parse(list.text).data?.readThroughSessionId;
if (!sessionId) throw new Error(`cannot resolve a sessionId from the list response: ${list.text.slice(0, 160)}`);

const before = parseRoutes(await (await fetch(metricsUrl)).text());

const probes = [
  ['/api/v2/records?pageNum=1&pageSize=20', '/api/v2/records'],
  [`/api/v2/records/${sessionId}/overview`, '/api/v2/records/{sessionId}/overview'],
  [`/api/v2/records/${sessionId}/rounds?afterRoundNum=0&pageSize=10`, '/api/v2/records/{sessionId}/rounds'],
  ['/api/room/pending', '/api/room/pending'],
];
const results = [];
for (const [route, label] of probes) {
  const r = await call(route, token);
  results.push({ route, label, status: r.status, code: r.code });
}

await new Promise(r => setTimeout(r, 1200)); // let the next scrape interval land
const after = parseRoutes(await (await fetch(metricsUrl)).text());

let failures = 0;
console.log('route                                    http  body  success-before  success-after');
for (const r of results) {
  const key = `${r.label}|success`;
  const b = before.get(key) || 0;
  const a = after.get(key) || 0;
  const ok = r.status === 200 && r.code === 200 && a > b;
  if (!ok) failures++;
  console.log(
    `${r.label.padEnd(40)} ${String(r.status).padStart(4)} ${String(r.code).padStart(5)} ${String(b).padStart(15)} ${String(a).padStart(14)}  ${ok ? 'OK' : 'FAIL'}`,
  );
}

if (failures) {
  console.error(`\n${failures} route(s) did not record a success sample. Is the 9091 tunnel open and the metrics endpoint reachable?`);
  process.exit(1);
}
console.log('\nall expected routes recorded a success sample');
