import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '../../..');
const reports = [
  '线上压测对比_2026-10-11.md', '线上压测复测_2026-10-12.md',
  '热点归属_2026-10-15.md', 'Phase1测试报告_2026-10-15.md', '准入保护验证_2026-10-15.md'
].map(name => 'HainanMaJhong/docs/' + name);
const files = ['docs/performance/public/key-results.csv', ...reports];
const manifest = files.map(file => {
  const bytes = fs.readFileSync(path.join(root, file));
  return { path: file, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') };
});
fs.writeFileSync(path.join(here, 'manifest.json'), JSON.stringify({
  kind: 'manually-reviewed-report-excerpts', originalAcceptancePreserved: true, files: manifest
}, null, 2) + '\n');
console.log(`Public evidence manifest: ${manifest.length} files`);
