"""Fetch the pinned ARM64 Rime core, one dictionary, and their license closure."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'tools/pinyin-rime-dependencies.json'
TARGET = ROOT / '.android/pinyin-rime'


def verify(path, spec):
    if not path.is_file():
        return False
    data = path.read_bytes()
    blob = hashlib.sha1(f'blob {len(data)}\0'.encode() + data).hexdigest()
    return len(data) == spec['bytes'] and blob == spec['blob'] and (
        'sha256' not in spec or hashlib.sha256(data).hexdigest() == spec['sha256'])


def prepare(spec, repo, commit, verify_only=False, proxy=''):
    path = TARGET / spec['target']
    if not path.resolve().is_relative_to(TARGET.resolve()):
        raise ValueError('Dependency path escapes target')
    if not verify(path, spec):
        if verify_only:
            raise ValueError(f'Missing or damaged dependency: {spec["target"]}')
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + '.download')
        url = f'https://raw.githubusercontent.com/{repo}/{commit}/{spec["source"]}'
        command = ['curl.exe', '-fsSL', '--retry', '3', '--retry-all-errors', '--connect-timeout', '20',
                   '--max-time', '900']
        if proxy:
            command += ['--proxy', proxy]
        subprocess.run(command + [url, '-o', str(temporary)], check=True)
        if not verify(temporary, spec):
            raise ValueError(f'Dependency checksum mismatch: {spec["target"]}')
        temporary.replace(path)
    return {'path': spec['target'], 'bytes': path.stat().st_size,
            'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--verify-only', action='store_true')
    parser.add_argument('--proxy', default='')
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding='utf-8'))
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        receipts = list(pool.map(lambda spec: prepare(spec, manifest['repository'],
                        manifest['commit'], args.verify_only, args.proxy), manifest['files']))
    TARGET.mkdir(parents=True, exist_ok=True)
    (TARGET / 'verified.json').write_text(json.dumps(receipts, indent=2) + '\n', encoding='utf-8')
    print(f'RIME_ASSETS_VERIFIED {len(receipts)} files / {sum(r["bytes"] for r in receipts)} bytes')


if __name__ == '__main__':
    main()
