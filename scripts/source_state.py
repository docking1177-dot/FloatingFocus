"""Content identity for checks; ignored build outputs and untracked asset/ are not source."""
import argparse
import hashlib
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def git(*args, root=ROOT):
    return subprocess.check_output(['git', '-C', str(root), *args])


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def source_state(root=ROOT):
    root = Path(root)
    tracked = set(git('ls-files', '-z', root=root).decode('utf-8').split('\0')) - {''}
    extra = set(git('ls-files', '--others', '--exclude-standard', '-z', root=root).decode('utf-8').split('\0')) - {''}
    excluded = sorted(p for p in extra if p.startswith('asset/'))
    extra.difference_update(excluded)
    digest = hashlib.sha256()
    for name in sorted(tracked | extra):
        path = root / name
        value = sha256(path) if path.is_file() else 'MISSING'
        digest.update(name.encode('utf-8') + b'\0' + value.encode() + b'\0')
    dirty = bool(git('diff', 'HEAD', '--name-only', root=root).strip() or extra)
    return {
        'commit': git('rev-parse', 'HEAD', root=root).decode().strip(),
        'fingerprint': digest.hexdigest(), 'dirty': dirty,
        'untrackedSource': sorted(extra), 'excludedUntrackedAssetCount': len(excluded),
    }


def build_inputs(root=ROOT):
    return {name: sha256(root / name) if (root / name).is_file() else None
            for name in ('local.properties', '.tools/maven-bridge.init.gradle')}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args()
    print(json.dumps({'source': source_state(args.root), 'localBuildInputs': build_inputs(args.root)}, ensure_ascii=False))
