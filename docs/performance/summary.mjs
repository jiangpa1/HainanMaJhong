export function summarize(report) {
  return (report.stages||[]).map(stage=> {
    const endpoints=Object.values(stage.http?.endpoints||{});
    const maximum=values=>values.length?Math.max(...values):null;
    return {tables:stage.tables,players:stage.players,seconds:stage.emissionSeconds,recordsVersion:report.recordsVersion||'legacy',
      encoding:report.acceptEncoding||'identity',acceptanceProfile:report.acceptanceProfile||'historical-baseline',
      pushMode:report.pushMode||'full',
      roundDrainSeconds:stage.roundDrainSeconds,
      unmatchedBroadcastRate:stage.unmatchedBroadcastAcceptance?.rate,
      unmatchedBroadcastPass:stage.unmatchedBroadcastAcceptance?.pass,
      sequenceGaps:(stage.rooms||[]).some(r=>r.sequence)?stage.rooms.reduce((n,r)=>n+(r.sequence?.gaps||0),0):null,
      missingSequences:(stage.rooms||[]).some(r=>r.sequence)?stage.rooms.reduce((n,r)=>n+(r.sequence?.missing||0),0):null,
      sent:stage.http?.sent,failed:stage.http?.failed,errorRate:stage.http?.errorRate,
      succeeded:stage.http?.succeeded,rateLimited:stage.http?.rateLimited,successfulRps:stage.http?.successfulRps,
      httpP95Ms:maximum(endpoints.map(e=>e.timing?.p95Ms).filter(Number.isFinite)),
      httpP99Ms:maximum(endpoints.map(e=>e.timing?.p99Ms).filter(Number.isFinite)),
      broadcastP95Ms:maximum((stage.rooms||[]).map(r=>r.broadcast?.p95Ms).filter(Number.isFinite)),
      pass:stage.pass===true,stopReason:stage.abort||report.stopReason||null};
  });
}
