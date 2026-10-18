// Deep diff of two captured records-API snapshots; prints the first N differing paths.
import fs from 'node:fs';
const [, , aPath, bPath, limitArg] = process.argv;
const limit = Number(limitArg || 25);
const a = JSON.parse(fs.readFileSync(aPath, 'utf8'));
const b = JSON.parse(fs.readFileSync(bPath, 'utf8'));
const diffs = [];
function walk(x, y, path) {
  if (diffs.length >= limit) return;
  if (Array.isArray(x) || Array.isArray(y)) {
    if (!Array.isArray(x) || !Array.isArray(y)) return diffs.push(`${path}: type ${typeof x} vs ${typeof y}`);
    if (x.length !== y.length) diffs.push(`${path}: length ${x.length} vs ${y.length}`);
    for (let i = 0; i < Math.min(x.length, y.length); i++) walk(x[i], y[i], `${path}[${i}]`);
    return;
  }
  if (x && y && typeof x === 'object' && typeof y === 'object') {
    for (const key of new Set([...Object.keys(x), ...Object.keys(y)])) walk(x[key], y[key], `${path}.${key}`);
    return;
  }
  if (x !== y) diffs.push(`${path}: ${JSON.stringify(x)} vs ${JSON.stringify(y)}`);
}
walk(a.accounts, b.accounts, 'accounts');
console.log(`before=${aPath} at=${a.at}`);
console.log(`after =${bPath} at=${b.at}`);
console.log(`differences=${diffs.length}${diffs.length >= limit ? ` (showing first ${limit})` : ''}`);
for (const d of diffs) console.log('  ' + d);
