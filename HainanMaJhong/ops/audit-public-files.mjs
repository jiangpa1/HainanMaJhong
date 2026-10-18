// Read-only audit of tracked and non-ignored candidate text files. Never print matched values.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const files = [...new Set(execFileSync('git', ['ls-files', '-z', '--cached', '--others', '--exclude-standard'],
  { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 }).split('\0').filter(Boolean))];
const findings = [], skipped = [], extensions = new Set(['.md', '.json', '.jsonl', '.csv', '.yml', '.yaml', '.xml', '.properties', '.env', '.example', '.js', '.mjs', '.java', '.ps1', '.py', '.sql', '.conf', '.txt', '.bat']);
let scanned = 0;
for (const file of files) {
  const full = path.join(root, file);
  if (!fs.existsSync(full) || !fs.statSync(full).isFile()) continue;
  if (/(?:^|\/)(?:private|runs|\.local-archive)\/|(?:^|\/)\.env$|\.(?:dpapi|jfr|hprof|pem|key)$/.test(file))
    findings.push({ file, category: 'private-path-candidate' });
  if (!extensions.has(path.extname(file))) continue;
  if (fs.statSync(full).size > 4 * 1024 * 1024) { skipped.push(file); continue; }
  scanned++;
  const lines = fs.readFileSync(full, 'utf8').split(/\r?\n/);
  for (const [i, line] of lines.entries()) {
    if (/-----BEGIN (?:OPENSSH |RSA |EC )?PRIVATE KEY-----/.test(line))
      findings.push({ file, line: i + 1, category: 'private-key-marker' });
    if (/eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{12,}/.test(line))
      findings.push({ file, line: i + 1, category: 'jwt-literal' });
    // Configuration only: code/test passwords are ambiguous and need separate human review.
    if (/\.(?:yml|yaml|properties)$/.test(file) &&
        /^\s*(?:password|secret|client-secret)\s*[:=]\s*\S/i.test(line) &&
        !/[:=]\s*(?:#|['"]?(?:\$\{|REPLACE|CHANGE|example|<))/i.test(line))
      findings.push({ file, line: i + 1, category: 'literal-config-secret-review' });
  }
}
console.log(JSON.stringify({ scannedTextFiles: scanned, findings, skippedLargeFiles: skipped,
  scope: 'Candidate text only; not a complete secret audit or Git-history scan; no matched values printed' }, null, 2));
if (findings.length) process.exitCode = 1;
