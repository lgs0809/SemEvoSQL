"""Bounded hashed-asset retention for users finishing a session across a web deployment."""
import hashlib
import json
from pathlib import PurePosixPath
import shutil

MANIFEST = '__acceptance_asset_history.json'


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(root, prefix):
    return {p.relative_to(root).as_posix(): file_hash(p)
            for p in sorted((root / prefix).rglob('*')) if p.is_file() and not p.is_symlink()}


def release(files):
    return {'id': hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest(), 'files': files}


def retain(current, previous, destination, prefix, keep=3):
    if keep < 1 or keep > 3:
        raise ValueError('Retain at most three verified web releases')
    latest = release(inventory(current, prefix))
    manifest = previous / MANIFEST
    old = json.loads(manifest.read_text())['releases'] if manifest.exists() else [release(inventory(previous, prefix))]
    if len(old) > 3:
        raise ValueError('Previous asset history exceeds the retention bound')
    history, seen = [latest], {latest['id']}
    for item in old:
        if len(history) >= keep:
            break
        if item['id'] not in seen:
            if item != release(item['files']):
                raise ValueError('Asset manifest hash differs from its file list')
            history.append(item)
            seen.add(item['id'])
    retained = {}
    for item in history[1:]:
        for name, digest in item['files'].items():
            path = PurePosixPath(name)
            if path.is_absolute() or '..' in path.parts or not path.parts or path.parts[0] != prefix:
                raise ValueError('Retained file must remain within the static asset directory')
            source = previous / name
            if source.is_symlink() or not source.is_file() or file_hash(source) != digest:
                raise ValueError('Previous asset is missing or differs from its verified hash')
            target = destination / name
            if target.exists():
                if file_hash(target) != digest:
                    raise ValueError('A static filename cannot identify different bytes')
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            if name not in latest['files']:
                retained[name] = digest
    (destination / MANIFEST).write_text(json.dumps({'format': 1, 'releases': history}, sort_keys=True) + '\n')
    return {'releaseCount': len(history), 'retainedFiles': retained,
            'retainedBytes': sum((destination / name).stat().st_size for name in retained)}
