"""Check first; --apply installs the checked snippets and gracefully reloads.
Default is check-only. Requires root for --apply. No Java/JAR changes.
"""
import argparse, glob, json, os, pathlib, re, shutil, subprocess, tempfile, time

MAIN = pathlib.Path('/etc/nginx/nginx.conf')
SERVER = pathlib.Path('/etc/nginx/conf.d/mahjong.conf')
LOG = pathlib.Path('/etc/nginx/conf.d/00-records-gzip-log.conf')
LOCATION = pathlib.Path('/etc/nginx/snippets/mahjong-records-gzip.conf')
TARGETS = (SERVER, LOG, LOCATION)
DIRECTIVE = '    include /etc/nginx/snippets/mahjong-records-gzip.conf;'


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode='w', dir=path.parent, delete=False) as f:
        f.write(content); name = f.name
    os.chmod(name, 0o644)
    os.replace(name, path)


def restore(backup):
    backup = backup.resolve()
    allowed = pathlib.Path('/etc/nginx/mahjong-gzip-backups').resolve()
    if backup.parent != allowed:
        raise ValueError('Backup must be directly inside /etc/nginx/mahjong-gzip-backups')
    manifest = json.loads((backup / 'manifest.json').read_text())
    for target in TARGETS:
        if manifest[str(target)]:
            shutil.copy2(backup / target.name, target)
        else:
            target.unlink(missing_ok=True)


def main(args):
    if args.rollback:
        restore(pathlib.Path(args.rollback))
        subprocess.run(['/usr/sbin/nginx', '-t'], check=True)
        subprocess.run(['/usr/sbin/nginx', '-s', 'reload'], check=True)
        print('Rolled back and gracefully reloaded')
        return
    source = pathlib.Path(__file__).resolve().parent
    log = (source / 'records-gzip-log.conf').read_text()
    location = (source / 'records-gzip-location.conf').read_text()
    server = SERVER.read_text()
    if len(re.findall(r'^\s*server\s*\{', server, re.M)) != 1 or not server.rstrip().endswith('}'):
        raise ValueError('Expected one Mahjong server block; review configuration manually')
    if re.search(r'location\s+[^\n{]*records', server):
        raise ValueError('Existing records location detected; review its precedence manually')
    for target, marker in ((LOG, 'log_format records_gzip'), (LOCATION, 'gzip_comp_level')):
        if target.exists() and marker not in target.read_text():
            raise ValueError(f'Refusing to overwrite unrelated file: {target}')
    if DIRECTIVE.strip() not in server:
        end = server.rfind('}')
        server = server[:end] + '\n' + DIRECTIVE + '\n' + server[end:]
    with tempfile.TemporaryDirectory(prefix='mahjong-gzip-config-') as temp:
        root = pathlib.Path(temp)
        (root / 'log.conf').write_text(log)
        (root / 'location.conf').write_text(location.replace('/var/log/nginx/records-gzip.log', str(root / 'access.log')))
        (root / 'server.conf').write_text(server.replace(str(LOCATION), str(root / 'location.conf')))
        includes = [str(root / 'log.conf')]
        for name in sorted(glob.glob('/etc/nginx/conf.d/*.conf')):
            if name == str(LOG): continue
            includes.append(str(root / 'server.conf') if name == str(SERVER) else name)
        main_config, count = re.subn(r'include\s+/etc/nginx/conf\.d/\*\.conf\s*;',
            '\n'.join('include ' + name + ';' for name in includes), MAIN.read_text())
        if count != 1:
            raise ValueError('Expected exactly one conf.d include in http context')
        (root / 'nginx.conf').write_text(main_config)
        subprocess.run(['/usr/sbin/nginx', '-t', '-c', str(root / 'nginx.conf')], check=True)
    print('PASS: proposed configuration validated against existing Nginx configuration')
    if not args.apply:
        print('Check only; production configuration and running workers unchanged')
        return
    if os.geteuid() != 0:
        raise PermissionError('--apply requires root')
    backup = pathlib.Path('/etc/nginx/mahjong-gzip-backups') / str(time.time_ns())
    backup.mkdir(parents=True, mode=0o700)
    manifest = {str(target): target.exists() for target in TARGETS}
    for target in TARGETS:
        if target.exists(): shutil.copy2(target, backup / target.name)
    (backup / 'manifest.json').write_text(json.dumps(manifest))
    try:
        write(LOG, log); write(LOCATION, location); write(SERVER, server)
        subprocess.run(['/usr/sbin/nginx', '-t'], check=True)
        subprocess.run(['/usr/sbin/nginx', '-s', 'reload'], check=True)
    except Exception:
        restore(backup)
        subprocess.run(['/usr/sbin/nginx', '-t'], check=True)
        subprocess.run(['/usr/sbin/nginx', '-s', 'reload'], check=True)
        raise
    print('Applied with graceful reload. Backup: ' + str(backup))
    print('Rollback: python3 ' + str(source / 'install-records-gzip.py') + ' --rollback ' + str(backup))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group()
    group.add_argument('--apply', action='store_true')
    group.add_argument('--rollback')
    main(parser.parse_args())
