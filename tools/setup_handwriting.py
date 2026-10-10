"""Download and verify the exact offline handwriting model and vocabulary."""
import argparse
import hashlib
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = [
    ('model.onnx', 'https://gitee.com/feelime/models/releases/download/handwriting-v2/model.onnx', '04bf65859482f2e9f4836fb872c5a881940a8f6f81bd0994542187617793e932'),
    ('vocab.json', 'https://raw.githubusercontent.com/feelime/feelime/main/app/src/main/assets/engine-data/handwriting/vocab.json', '83863afd12846c337c8880348f1a7e275450be87ba04cf17445cca3ffd0939bd'),
]


def setup(proxy=''):
    folder = ROOT / 'models/handwriting'
    folder.mkdir(parents=True, exist_ok=True)
    for name, url, expected in ASSETS:
        target = folder / name
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
            print('Verified ' + name)
            continue
        partial = target.with_suffix(target.suffix + '.part')
        command = ['curl.exe', '-fL', '--retry', '2', '--connect-timeout', '15', '--max-time', '120']
        if proxy:
            command += ['--proxy', proxy]
        subprocess.run(command + ['-o', str(partial), url], check=True)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != expected:
            raise ValueError('Handwriting checksum mismatch: ' + name)
        partial.replace(target)
        print('Installed ' + name)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--proxy', default='')
    args = parser.parse_args()
    assert len(ASSETS) == 2 and all(len(asset[2]) == 64 for asset in ASSETS)
    setup(args.proxy)
