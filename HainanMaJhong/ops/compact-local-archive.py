"""Compact the one known local archive; verify every byte before removing its source tree."""
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

root = Path(__file__).resolve().parents[2]
folder = (root / '.local-archive/history-2026-10-12').resolve()
parent = (root / '.local-archive').resolve()
if folder.parent != parent or not folder.is_relative_to(root.resolve()):
    raise RuntimeError('Archive path outside workspace')
target = parent / 'history-2026-10-12.zip'
if target.exists():
    raise RuntimeError('Refusing to overwrite local evidence archive')
manifest = []
with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for file in sorted(folder.rglob('*')):
        if file.is_symlink():
            raise RuntimeError('Unexpected symbolic link in archive')
        if file.is_file():
            data = file.read_bytes()
            name = file.relative_to(folder).as_posix()
            archive.writestr(name, data)
            manifest.append({'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
    archive.writestr('ARCHIVE-MANIFEST.json', json.dumps(manifest, ensure_ascii=False, indent=2))
with zipfile.ZipFile(target) as archive:
    assert archive.testzip() is None
    for row in manifest:
        assert hashlib.sha256(archive.read(row['path'])).hexdigest() == row['sha256']
        assert hashlib.sha256((folder / row['path']).read_bytes()).hexdigest() == row['sha256']
# The checked absolute folder is the single explicitly named workspace archive above.
shutil.rmtree(folder)
(parent / 'README.md').write_text('# 本地历史证据归档\n\n'
    'history-2026-10-12.zip 保存原始压测证据、旧包、设计留档和开发日志，含逐文件 SHA256 清单。'
    '可能含账号和凭据相关材料，仅供本机恢复，不随运维交付包发送。解压到独立目录按原相对路径查阅。\n', encoding='utf-8')
print(json.dumps({'files': len(manifest), 'archiveBytes': target.stat().st_size, 'verified': True}))
