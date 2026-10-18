"""Build a credential-free operator bundle from explicit file allowlists. No server access."""
from pathlib import Path
import csv
import hashlib
import json
import re
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / 'docs/performance'
OUT = ROOT / 'deliverables'
HISTORY = PERF / 'history'
NAME = 'hainan-mahjong-ops-loadtest-2026-10-16'
ACTIVE = ['package.json', 'pnpm-lock.yaml', 'README.md', 'config.example.json',
          'accounts.example.json', 'run-capacity.mjs', 'test-config.mjs',
          'online-capacity-retest.mjs', 'monitor.mjs', 'cpu-guard.mjs', 'success-histogram.mjs', 'generate-boundary-plan.mjs', 'summary.mjs',
          'summarize-results.mjs', 'http-json-body.mjs', 'verify-records-gzip.mjs',
          'check-http-json-body.mjs', 'check-records-gzip-probe.mjs', 'test-handoff.mjs']
PRIVATE_KEYS = {'username', 'password', 'token', 'accessToken', 'refreshToken', 'authorization',
                'userId', 'winnerId', 'loserId', 'roomId', 'sessionId', 'roomCode',
                'pendingRoom', 'data', 'account', 'accounts', 'provisionedRooms',
                'winHand', 'win_hand', 'hand', 'detail'}

def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()

def clean(value):
    if isinstance(value, dict):
        return {k: clean(v) for k, v in value.items()
                if k not in PRIVATE_KEYS and not (k == 'players' and isinstance(v, list))}
    if isinstance(value, list):
        return [clean(v) for v in value]
    if isinstance(value, str) and (re.search(r'eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', value)
                                  or re.search(r'(?:Bearer\s+|password\s*=|token=)', value, re.I)):
        return '[credential-like value redacted]'
    return value

def collect_history():
    HISTORY.mkdir(parents=True, exist_ok=True)
    sources = list(PERF.glob('results-*/**/results.json'))
    if (ROOT / 'output/work').exists():
        sources += list((ROOT / 'output/work').rglob('results.json'))
    if not sources:
        raise SystemExit('Raw history is archived; existing sanitized history was preserved. Restore original evidence before recollecting.')
    rows, evidence = [], []
    for source in sorted(set(sources)):
        if any('mock' in p.lower() for p in source.parts):
            continue
        raw = json.loads(source.read_text(encoding='utf-8-sig'))
        if not isinstance(raw, dict) or not isinstance(raw.get('stages'), list):
            continue
        label = hashlib.sha256(source.relative_to(ROOT).as_posix().encode()).hexdigest()[:12]
        target = HISTORY / (label + '.json')
        fields = ['runId', 'base', 'startedAt', 'finishedAt', 'stopReason', 'acceptEncoding',
                  'recordsVersion', 'clientVersion', 'limits', 'stages', 'baseline', 'health']
        public = clean({key: raw[key] for key in fields if key in raw})
        public['derivedFrom'] = {'source': source.relative_to(ROOT).as_posix(), 'sourceSha256': digest(source),
                                 'sanitized': True, 'originalAcceptancePreserved': True}
        target.write_text(json.dumps(public, ensure_ascii=False, indent=2), encoding='utf-8')
        evidence.append(public['derivedFrom'])
        for stage in raw['stages'] or [{}]:
            endpoints = list(stage.get('http', {}).get('endpoints', {}).values())
            p95 = [e.get('timing', {}).get('p95Ms') for e in endpoints]
            p95 = [n for n in p95 if isinstance(n, (int, float))]
            rows.append({'file': target.name, 'source': source.relative_to(ROOT).as_posix(),
                         'startedAt': stage.get('startedAt', raw.get('startedAt')),
                         'tables': stage.get('tables'), 'players': stage.get('players'),
                         'seconds': stage.get('emissionSeconds'), 'recordsVersion': raw.get('recordsVersion', 'legacy'),
                         'encoding': raw.get('acceptEncoding', 'identity'), 'httpSent': stage.get('http', {}).get('sent'),
                         'httpFailed': stage.get('http', {}).get('failed'), 'worstHttpP95Ms': max(p95) if p95 else None,
                         'pass': stage.get('pass', False), 'stopReason': stage.get('abort') or raw.get('stopReason')})
    if rows:
        with (HISTORY / 'index.csv').open('w', encoding='utf-8-sig', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    (HISTORY / 'sources.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding='utf-8')
    return len(evidence), len(rows)

def build_bundle():
    OUT.mkdir(exist_ok=True)
    bundle = OUT / (NAME + '.zip')
    if bundle.exists():
        raise RuntimeError('Refusing to overwrite an existing delivery zip')
    files = {}
    def add(source, destination):
        if not source.is_file():
            raise FileNotFoundError(source)
        files[destination] = source
    for name in ACTIVE:
        add(PERF / name, 'load-test/' + name)
    for name in ['index.csv', 'README.md']:
        add(HISTORY / name, 'load-test/history/' + name)
    for name in ['README.md', 'key-results.csv', 'manifest.json', 'write-manifest.mjs']:
        add(PERF / 'public' / name, 'load-test/public/' + name)
    for folder, names in {
        'results-2026-10-11-retest': ['online-comparison.csv', 'online-comparison-data.json'],
        'results-2026-10-11-summary': ['comparison-data.json', 'gzip-level-comparison.csv', 'record-size-comparison.csv']
    }.items():
        for name in names:
            add(PERF / folder / name, 'load-test/' + folder + '/' + name)
        for source in (PERF / folder / 'charts').glob('*.svg'):
            add(source, 'load-test/' + folder + '/charts/' + source.name)
    for source in (ROOT / 'HainanMaJhong/docs').glob('*.md'):
        add(source, 'docs/' + source.name)
    for source in (ROOT / 'output/pdf').glob('*.pdf'):
        add(source, 'reports/' + source.name)
    target = ROOT / 'HainanMaJhong/target'
    jar = target / 'HainanMaJhong2-1.0-SNAPSHOT.jar'
    report = json.loads((target / 'build-report.json').read_text(encoding='utf-8-sig'))
    if digest(jar) != report['sha256']:
        raise RuntimeError('Jar differs from the complete build report; rebuild from source')
    source_manifest = target / 'source-manifest.json'
    if digest(source_manifest) != report['sourceManifestSha256']:
        raise RuntimeError('Source manifest differs from the build report')
    for row in json.loads(source_manifest.read_text(encoding='utf-8-sig')):
        if digest(ROOT / row['path']) != row['sha256']:
            raise RuntimeError('Source changed since build: ' + row['path'])
    add(jar, 'app/HainanMaJhong2-1.0-SNAPSHOT.jar')
    add(target / 'build-report.json', 'app/build-report.json')
    add(source_manifest, 'app/source-manifest.json')
    add(ROOT / 'HainanMaJhong/.env.example', 'app/.env.example')
    add(ROOT / 'HainanMaJhong/README.md', 'app/README.md')
    add(ROOT / 'HainanMaJhong/ops/records-p2.ps1', 'app/ops/records-p2.ps1')
    add(ROOT / 'HainanMaJhong/ops/inspect-lifecycle.py', 'app/ops/inspect-lifecycle.py')
    for source in (ROOT / 'HainanMaJhong/ops/monitoring').glob('*'):
        if source.is_file(): add(source, 'app/ops/monitoring/' + source.name)
    for source in (ROOT / 'HainanMaJhong/ops/nginx').glob('*'):
        if source.is_file(): add(source, 'app/ops/nginx/' + source.name)
    # Historical SQL backups remain local. Current schema comes from application migrations.
    # Ship the exact installed ws package too, so the default runner works offline after extraction.
    ws = PERF / 'node_modules/.pnpm/ws@8.21.0/node_modules/ws'
    if ws.is_dir():
        for source in ws.rglob('*'):
            if source.is_file(): add(source, 'load-test/node_modules/ws/' + source.relative_to(ws).as_posix())
    add(OUT / '运维交付说明.md', 'README.md')
    def payload(name, source):
        data = source.read_bytes()
        if source.suffix == '.md' and name.startswith('docs/'):
            text = data.decode('utf-8-sig').replace('../../docs/performance/', '../load-test/').replace('../../output/pdf/', '../reports/')
            return text.encode('utf-8')
        return data
    manifest = [{'path': name, 'bytes': len(payload(name, source)), 'sha256': hashlib.sha256(payload(name, source)).hexdigest()}
                for name, source in sorted(files.items())]
    for name, source in files.items():
        if source.suffix in {'.json', '.jsonl', '.md', '.csv', '.mjs', '.ps1', '.yml', '.yaml', '.sql', '.py', '.conf'}:
            text = source.read_text(encoding='utf-8-sig')
            if re.search(r'-----BEGIN (?:OPENSSH |RSA |EC )?PRIVATE KEY-----|eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+', text):
                raise RuntimeError('Potential secret in delivery: ' + name)
    with zipfile.ZipFile(bundle, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zip:
        for name, source in sorted(files.items()): zip.writestr(NAME + '/' + name, payload(name, source))
        zip.writestr(NAME + '/MANIFEST.json', json.dumps(manifest, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(bundle) as zip:
        assert zip.testzip() is None
        for row in manifest:
            assert hashlib.sha256(zip.read(NAME + '/' + row['path'])).hexdigest() == row['sha256']
    (OUT / 'SHA256SUMS.txt').write_text(digest(bundle) + '  ' + bundle.name + '\n', encoding='utf-8')
    print(json.dumps({'bundle': str(bundle), 'files': len(manifest), 'bytes': bundle.stat().st_size}, ensure_ascii=False))

def enrich_archived_history():
    """Export cleanup diagnostics and numeric resource series from verified local originals."""
    archive_path = ROOT / '.local-archive/history-2026-10-12.zip'
    metadata = []
    with zipfile.ZipFile(archive_path) as archive:
        for file in HISTORY.glob('*.json'):
            if file.name == 'sources.json':
                continue
            public = json.loads(file.read_text(encoding='utf-8'))
            origin = public.get('derivedFrom')
            if not origin:
                continue
            data = archive.read(origin['source'])
            assert hashlib.sha256(data).hexdigest() == origin['sourceSha256']
            raw = json.loads(data.decode('utf-8-sig'))
            cleanup = []
            for item in raw.get('cleanup', []):
                row = clean(item)
                for pending, original in zip(row.get('pending', []), item.get('pending', [])):
                    pending['pendingExists'] = original.get('data', {}).get('exists') if isinstance(original.get('data'), dict) else None
                cleanup.append(row)
            public['cleanup'] = cleanup
            file.write_text(json.dumps(public, ensure_ascii=False, indent=2), encoding='utf-8')
        for name in archive.namelist():
            if not name.endswith('.jsonl') or 'metrics' not in Path(name).name or 'mock' in name.lower():
                continue
            if not name.startswith(('docs/performance/', 'output/work/')):
                continue
            data = archive.read(name)
            rows = []
            for line in data.decode('utf-8-sig').splitlines():
                if not line.strip():
                    continue
                raw = json.loads(line)
                rows.append(clean({k: raw[k] for k in ['at', 'ok', 'source', 'metrics', 'actuatorError', 'error'] if k in raw}))
            label = hashlib.sha256(name.encode()).hexdigest()[:12] + '-metrics.jsonl'
            (HISTORY / label).write_text('\n'.join(json.dumps(row, ensure_ascii=False) for row in rows) + '\n', encoding='utf-8')
            metadata.append({'file': label, 'source': name, 'sourceSha256': hashlib.sha256(data).hexdigest(), 'samples': len(rows)})
    (HISTORY / 'metrics-index.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'resourceSeries': len(metadata), 'samples': sum(x['samples'] for x in metadata)}))

if __name__ == '__main__':
    import sys
    if sys.argv[1:] == ['history']:
        print(json.dumps(dict(zip(['runs', 'stages'], collect_history()))))
    elif sys.argv[1:] == ['bundle']:
        build_bundle()
    elif sys.argv[1:] == ['history-from-archive']:
        enrich_archived_history()
    else:
        raise SystemExit('Usage: prepare-ops-handoff.py history | history-from-archive | bundle')
