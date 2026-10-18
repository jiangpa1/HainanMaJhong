import fs from 'node:fs';
const dirs = process.argv.slice(2);
for (const d of dirs) {
  const r = JSON.parse(fs.readFileSync(d + '/results.json', 'utf8'));
  const s = r.stages[0];
  console.log('### ' + d);
  console.log('planned=' + s.plannedSeconds + ' emission=' + s.emissionSeconds.toFixed(1) + ' elapsed=' + s.elapsedSeconds.toFixed(1) + ' abort=' + s.abort);
  console.log('http sent=' + s.http.sent + ' failed=' + s.http.failed + ' errRate=' + s.http.errorRate + ' rps=' + s.http.actualRps.toFixed(2) + ' peakInflight=' + s.http.peakInflight);
  for (const [k, v] of Object.entries(s.http.endpoints)) {
    console.log('  ' + k + ' n=' + v.sent + ' fail=' + v.failed + ' p50=' + v.timing.p50Ms.toFixed(1) + ' p95=' + v.timing.p95Ms.toFixed(1) + ' p99=' + v.timing.p99Ms.toFixed(1) + ' max=' + v.timing.maxMs.toFixed(1) + ' bodyMiB=' + (v.bodyBytes / 1048576).toFixed(2) + ' decodedMiB=' + (v.decodedBytes / 1048576).toFixed(2));
  }
  console.log('traffic appMbps=' + (s.traffic.serverApplicationBitsPerSecond / 1e6).toFixed(3) + ' perPlayerMbps=' + (s.traffic.perPlayerBitsPerSecond / 1e6).toFixed(5) + ' rxMiB=' + (s.traffic.gameReceivedFrameBytes / 1048576).toFixed(2));
  console.log('handshake=' + JSON.stringify(s.handshakeTiming));
  const st = s.rooms.map(x => x.settledHands), b = s.rooms.map(x => x.broadcast.p95Ms);
  console.log('rooms=' + s.rooms.length + ' settledTotal=' + st.reduce((a, b) => a + b, 0) + ' settledMin/Max=' + Math.min(...st) + '/' + Math.max(...st) + ' broadcastP95max=' + Math.max(...b).toFixed(1) + ' dupReq=' + s.rooms.reduce((a, x) => a + x.duplicateRequests, 0) + ' unmatchedBc=' + s.rooms.reduce((a, x) => a + x.unmatchedBroadcasts, 0) + ' pendingDiscard=' + s.rooms.reduce((a, x) => a + x.pendingDiscardDeliveries, 0));
  console.log('rolling=' + JSON.stringify(s.rollingAcceptance));
  console.log('cleanup rooms=' + r.cleanup.length + ' disbanded=' + r.cleanup.filter(c => c.disbanded).length + ' sessionEnded=' + r.cleanup.filter(c => c.sessionEnded).length + ' recordErrors=' + r.cleanup.reduce((a, c) => a + (c.recordErrors || 0), 0) + ' listErrors=' + r.cleanup.reduce((a, c) => a + (c.listErrors || 0), 0) + ' pendingExists=' + r.cleanup.filter(c => (c.pending || []).some(p => p.data && p.data.exists)).length);
  console.log('pass=' + s.pass + ' baselinePass=' + s.baselinePass + ' p2Pass=' + s.p2Pass);
}
