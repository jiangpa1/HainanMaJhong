// Read-only, serial, bounded online probe. Token stays in process memory.
import http from 'node:http';
import https from 'node:https';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { decodeHttpBody } from './http-json-body.mjs';

const [base, ...routes] = process.argv.slice(2);
if (!base || !routes.length || routes.length > 8 || !process.env.RECORDS_GZIP_TOKEN) {
  throw new Error('Usage: RECORDS_GZIP_TOKEN=<token> node verify-records-gzip.mjs <base-url> <stable-records-route> [...max 8 routes]');
}
const origin = new URL(base);
if (!['http:', 'https:'].includes(origin.protocol) || origin.username || origin.password) throw new Error('Invalid base URL');
const urls = routes.map(route => {
  const url = new URL(route, origin);
  if (url.origin !== origin.origin || !/^\/api\/(?:v2\/)?records(?:\/|$)/.test(url.pathname) ||
      [...url.searchParams.keys()].some(k => /token|password|secret/i.test(k))) throw new Error('Only same-origin records URLs permitted');
  return url;
});
const token = process.env.RECORDS_GZIP_TOKEN;
delete process.env.RECORDS_GZIP_TOKEN;
const request = (url, encoding) => new Promise((resolve, reject) => {
  const start = performance.now();
  const req = (url.protocol === 'https:' ? https : http).request(url, {
    headers: { Authorization: `Bearer ${token}`, 'Accept-Encoding': encoding, Accept: 'application/json' }
  }, res => {
    let size = 0; const chunks = [];
    res.on('data', b => { size += b.length; if (size > 2_000_000) req.destroy(new Error('response-too-large')); else chunks.push(b); });
    res.on('error', reject);
    res.on('end', () => {
      try {
        const decoded = decodeHttpBody(Buffer.concat(chunks), res.headers['content-encoding']);
        const json = JSON.parse(decoded.text);
        if (res.statusCode !== 200 || json.code !== 200) throw new Error('Authenticated records request failed; HTTP ' + res.statusCode);
        resolve({ encoding: decoded.contentEncoding, bodyBytes: size, decodedBytes: decoded.decodedBytes,
          sha256: createHash('sha256').update(decoded.text).digest('hex'),
          vary: res.headers.vary || '', cacheControl: res.headers['cache-control'] || '',
          ms: +(performance.now() - start).toFixed(2) });
      } catch (e) { reject(e); }
    });
  });
  // Overall deadline also covers stalled response bodies, rather than idle timeout alone.
  const timer = setTimeout(() => req.destroy(new Error('request-timeout')), 10_000);
  req.on('close', () => clearTimeout(timer));
  req.on('error', reject); req.end();
});

const report = { at: new Date().toISOString(), base: origin.origin, pairs: [],
  notes: ['Serial read-only probes, at most 48 requests; no tokens or response contents saved',
    'Use immutable detail or rounds with readThroughRoundNum to avoid comparing changing data',
    'bodyBytes excludes HTTP/TCP/TLS overhead; correlate with Nginx log and NIC metrics'] };
for (const url of urls) {
  for (let i = 0; i < 3; i++) {
    const plain = await request(url, 'identity');
    const packed = await request(url, 'gzip');
    if (plain.encoding !== 'identity' || plain.sha256 !== packed.sha256) throw new Error('Decoded results differ; use a stable snapshot URL');
    if (packed.decodedBytes >= 1024 && packed.encoding !== 'gzip') throw new Error('Large JSON response was not gzipped');
    if (packed.encoding === 'gzip' && !packed.vary.toLowerCase().split(/\s*,\s*/).includes('accept-encoding')) throw new Error('Missing Vary Accept-Encoding');
    if (!/\bprivate\b/i.test(packed.cacheControl) || !/\bno-store\b/i.test(packed.cacheControl)) throw new Error('Missing private/no-store');
    report.pairs.push({ uri: url.pathname, identity: plain, gzip: packed,
      savedPercent: +(100 * (1 - packed.bodyBytes / plain.bodyBytes)).toFixed(2) });
  }
}
console.log(JSON.stringify(report, null, 2));
