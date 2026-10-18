// Explicit invocation sends one synthetic firing/resolved pair to the configured email receiver.
import fs from 'node:fs';
import path from 'node:path';
const args = process.argv.slice(2), get = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined;
if (!get('--out')) throw Error('Usage: node alert-delivery-drill.mjs --out <new-evidence-dir> [--send]');
const out = path.resolve(get('--out'));
if (fs.existsSync(out) && fs.readdirSync(out).length) throw Error('Evidence directory must be new or empty');
fs.mkdirSync(out, { recursive: true });
const prom = 'http://127.0.0.1:9090', am = 'http://127.0.0.1:9093';
async function call(url, options = {}) {
  const r = await fetch(url, { ...options, signal: AbortSignal.timeout(5000) });
  if (!r.ok) throw Error('Monitoring endpoint failed: ' + r.status);
  return r;
}
const discovery = await (await call(prom + '/api/v1/alertmanagers')).json();
if (!discovery.data?.activeAlertmanagers?.length) throw Error('Prometheus has no active Alertmanager');
await call(am + '/-/ready');
const evidence = { startedAt: new Date().toISOString(), alertmanagerDiscovered: true, sent: false, inboxConfirmed: false,
  scope: 'Direct Alertmanager receiver drill; does not prove a Prometheus rule evaluation or human receipt' };
const save = () => fs.writeFileSync(path.join(out, 'delivery-drill.json'), JSON.stringify(evidence, null, 2));
save();
if (args.includes('--send')) {
  const start = new Date(), endsAt = new Date(start.getTime() + 120000);
  const alert = { labels: { alertname: 'MahjongDeliveryDrill', severity: 'warning', drill_id: String(start.getTime()) },
    annotations: { summary: '麻将告警送达演练：请确认收到触发与恢复两封邮件' }, startsAt: start.toISOString(), endsAt: endsAt.toISOString() };
  const send = item => call(am + '/api/v2/alerts', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify([item]) });
  const before = await (await call(am + '/metrics')).text();
  fs.writeFileSync(path.join(out, 'alertmanager-before.prom'), before);
  await send(alert); evidence.sent = true; evidence.firingSubmittedAt = new Date().toISOString(); save();
  // Wait beyond group_wait so the resolved update cannot erase a yet-unsent firing notification.
  await new Promise(resolve => setTimeout(resolve, 30000));
  await send({ ...alert, endsAt: new Date().toISOString() });
  evidence.resolvedSubmittedAt = new Date().toISOString(); save();
  console.log('Firing/resolved pair submitted. Verify both inbox messages and notification failure counters; submission is not delivery confirmation.');
} else console.log('Discovery/readiness checked. Use --send explicitly after filling mail settings to send the drill pair.');
