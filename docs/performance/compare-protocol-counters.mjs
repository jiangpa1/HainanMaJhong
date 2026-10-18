import fs from 'node:fs';
const files = {
  '10/11 formal-v2-192 (history)': 'docs/performance/history/24aa618c55f9.json',
  '10/11 v2-gzip-192-retry (history)': 'docs/performance/history/8ac47200c155.json',
  '10/12 pre-P2 attempt2': 'docs/performance/results-2026-10-12-192-retest/formal-v2-192-attempt2/results.json',
  '10/12 P2read-192': 'docs/performance/results-2026-10-12-192-retest/formal-p2read-192/results.json',
};
for (const [name, file] of Object.entries(files)) {
  const report = JSON.parse(fs.readFileSync(file, 'utf8'));
  const rooms = (report.stages[0] && report.stages[0].rooms) || [];
  const sum = key => rooms.reduce((a, r) => a + (r[key] || 0), 0);
  console.log(`${name.padEnd(36)} rooms=${String(rooms.length).padStart(3)} unmatchedBroadcasts=${String(sum('unmatchedBroadcasts')).padStart(4)} duplicateRequests=${sum('duplicateRequests')} pendingDiscards=${sum('pendingDiscardDeliveries')}`);
}
