// Aggregate only server successes. This measures filter processing, not upstream queue time.
export async function captureSuccessHistogram(url) {
  if (!url) return { available: false, reason: 'metricsUrl-not-configured' };
  try {
    const r = await fetch(url, { signal: AbortSignal.timeout(5000) });
    if (!r.ok) throw Error('http-' + r.status);
    const text = await r.text();
    if (text.length > 4e6) throw Error('metrics-too-large');
    const routes = {};
    for (const line of text.split('\n')) {
      const m = line.match(/^mahjong_http_response_seconds_(bucket|sum|count)\{(.*)\}\s+([\d.eE+-]+)/);
      if (!m || !/(?:^|,)outcome="success"(?:,|$)/.test(m[2])) continue;
      const label = name => {
        const found = m[2].match(new RegExp('(?:^|,)' + name + '="((?:\\\\.|[^"\\\\])*)"'));
        return found ? JSON.parse('"' + found[1] + '"') : null;
      };
      const route = label('route');
      if (!route?.startsWith('/api/')) continue;
      const row = routes[route] ??= { sum: 0, count: 0, buckets: {} };
      const n = Number(m[3]);
      if (!Number.isFinite(n)) throw Error('nonfinite-counter');
      if (m[1] === 'bucket') { const le = label('le'); row.buckets[le] = (row.buckets[le] || 0) + n; }
      else row[m[1]] += n;
    }
    if (!Object.keys(routes).length) throw Error('success-histograms-missing');
    // 应用通常带公共标签导出（如 process_start_time_seconds{application="...",}），必须允许标签段，否则差分永远不可用。
    const processStarted=Number(text.match(/^process_start_time_seconds(?:\{[^}]*\})?\s+([\d.eE+-]+)/m)?.[1]);
    if(!Number.isFinite(processStarted))throw Error('process-start-metric-missing');
    return { available: true, at: new Date().toISOString(), processStarted, routes };
  } catch (e) { return { available: false, reason: e.message }; }
}
function quantile(buckets, count, q) {
  if (!count) return null;
  let previousCount = 0, previousBound = 0;
  for (const [bound, cumulative] of Object.entries(buckets).map(([le, n]) => [le==='+Inf'?Infinity:Number(le), n]).sort((a, b) => a[0] - b[0])) {
    if (cumulative >= count * q) {
      if (!Number.isFinite(bound)) return null; // No finite estimate for overflow samples.
      return (previousBound + (bound - previousBound) * (count * q - previousCount) / Math.max(1, cumulative - previousCount)) * 1000;
    }
    previousBound = bound; previousCount = cumulative;
  }
  return null;
}
export function successHistogramDelta(before, after) {
  const result = { available: false, quantiles:'approximate, interpolated from cumulative histogram bucket deltas', scope: 'All server outcome=success API processing during snapshot interval; includes other clients; excludes pre-filter queueing', routes: {} };
  if (!before.available || !after.available) return { ...result, reason: before.reason || after.reason };
  if(before.processStarted!==after.processStarted)return {...result,reason:'server-restarted-during-window'};
  for (const [route, end] of Object.entries(after.routes)) {
    const start = before.routes[route] || { count: 0, sum: 0, buckets: {} };
    if (Object.keys(start.buckets).some(le => !(le in end.buckets))) return { ...result, reason: 'bucket-layout-changed' };
    const count = end.count - start.count, sum = end.sum - start.sum;
    const buckets = Object.fromEntries(Object.entries(end.buckets).map(([le, n]) => [le, n - (start.buckets[le] || 0)]));
    if (count < 0 || sum < 0 || Object.values(buckets).some(n => n < 0)) return { ...result, reason: 'counter-reset-or-layout-change' };
    if (!count) continue;
    result.routes[route] = { count, meanMs: sum * 1000 / count, p95Ms: quantile(buckets, count, .95), p99Ms: quantile(buckets, count, .99) };
  }
  result.available = Object.keys(result.routes).length > 0;
  result.startedAt = before.at; result.finishedAt = after.at;
  return result;
}
