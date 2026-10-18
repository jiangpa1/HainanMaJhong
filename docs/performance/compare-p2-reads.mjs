// Read-only comparison of the records API across a configuration change (e.g. enabling the P2 read path).
// Usage: node compare-p2-reads.mjs private/accounts.json out-before.json [userId,userId,...]
import fs from 'node:fs';

const [, , accountsPath, outPath, idList] = process.argv;
if (!accountsPath || !outPath) throw Error('Usage: node compare-p2-reads.mjs <accounts.json> <out.json> [userId,...]');
const base = process.env.TEST_BASE || 'http://jiangpahnmj.cn';
const accounts = JSON.parse(fs.readFileSync(accountsPath, 'utf8'));
const wanted = idList ? idList.split(',').map(Number) : accounts.slice(0, 8).map(a => a.id);
const selected = wanted.map(id => accounts.find(a => a.id === id)).filter(Boolean);

const stable = value => {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]));
  return value;
};

async function call(route, token, method = 'GET', body) {
  const headers = { Accept: 'application/json', 'User-Agent': 'p2-read-compare/1.0', 'Accept-Encoding': 'identity' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (body) headers['Content-Type'] = 'application/json';
  const response = await fetch(new URL(route, base), { method, headers, body: body ? JSON.stringify(body) : undefined });
  const text = await response.text();
  let json; try { json = JSON.parse(text); } catch {}
  return { status: response.status, code: json?.code, message: json?.message, data: json?.data };
}

const out = { base, at: new Date().toISOString(), accounts: {} };
for (const account of selected) {
  const login = await call('/api/login', null, 'POST', { username: account.username, password: account.password });
  if (login.code !== 200 || !login.data?.accessToken) { out.accounts[account.id] = { loginError: login }; continue; }
  const token = login.data.accessToken;
  const entry = {};
  for (const [key, route] of [
    ['me', '/api/user/me'],
    ['v2List1', '/api/v2/records?pageNum=1&pageSize=20'],
    ['v2List2', '/api/v2/records?pageNum=2&pageSize=10'],
    ['legacyList1', '/api/records?pageNum=1&pageSize=20'],
    ['legacyList2', '/api/records?pageNum=2&pageSize=10'],
    ['pending', '/api/room/pending'],
  ]) entry[key] = await call(route, token);

  const sessions = (entry.v2List1.data?.records || entry.v2List1.data?.list || []).slice(0, 3);
  for (const session of sessions) {
    const sid = session.sessionId ?? session.id;
    entry[`overview-${sid}`] = await call(`/api/v2/records/${sid}/overview`, token);
    entry[`rounds-${sid}`] = await call(`/api/v2/records/${sid}/rounds?afterRoundNum=0&pageSize=10`, token);
    entry[`legacyDetail-${sid}`] = await call(`/api/records/${sid}`, token);
  }
  const first = sessions[0];
  if (first) {
    const roomId = first.roomId ?? first.code;
    entry[`legacyByRoom-${roomId}`] = await call(`/api/records/byRoom?roomId=${roomId}`, token);
  }
  out.accounts[account.id] = stable(entry);
}
fs.writeFileSync(outPath, JSON.stringify(out, null, 2));
const summary = Object.entries(out.accounts).map(([id, entry]) => `${id}:${entry.loginError ? 'LOGIN-FAIL' : Object.keys(entry).length + ' calls'}`);
console.log(`base=${base} accounts=${selected.length} -> ${outPath}`);
console.log(summary.join(' '));
