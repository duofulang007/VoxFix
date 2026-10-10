"""Download the three official ASR archives, verify hashes, extract selected files."""
import concurrent.futures
import argparse
import hashlib
import shutil
import subprocess
import tarfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPECS = [
    ('asr-models', 'sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20-mobile',
     '45b8d04fe8faf5146397ff2e71d90ca8effdfcbe9adc30fdce769e92cabd6b03',
     {'encoder-epoch-99-avg-1.int8.onnx': 'streaming/encoder.onnx', 'decoder-epoch-99-avg-1.onnx': 'streaming/decoder.onnx', 'joiner-epoch-99-avg-1.int8.onnx': 'streaming/joiner.onnx', 'tokens.txt': 'streaming/tokens.txt', 'test_wavs/0.wav': 'test/0.wav'}),
    ('asr-models', 'sherpa-onnx-paraformer-zh-small-2024-03-09',
     'da92b3db5218c5be53aad53e57d1b6e63e7fc98a0e054fbdd6dbe18e9c6b1450',
     {'model.int8.onnx': 'final/model.onnx', 'tokens.txt': 'final/tokens.txt'}),
    ('punctuation-models', 'sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8',
     'c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1',
     {'model.int8.onnx': 'punctuation/model.onnx'}),
]


def setup(spec, proxy=''):
    release, name, expected, entries = spec
    cache = ROOT / '.model-downloads'
    cache.mkdir(exist_ok=True)
    archive = cache / (name + '.tar.bz2')
    def digest():
        with archive.open('rb') as stream:
            return hashlib.file_digest(stream, 'sha256').hexdigest()
    if not archive.exists() or digest() != expected:
        print('Downloading ' + name, flush=True)
        command = ['curl.exe', '-fL', '--retry', '3', '--connect-timeout', '30']
        if proxy:
            command += ['--proxy', proxy]
        subprocess.run(command + ['-o', str(archive), f'https://github.com/k2-fsa/sherpa-onnx/releases/download/{release}/{archive.name}'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if digest() != expected:
        raise ValueError('Model archive checksum mismatch: ' + name)
    with tarfile.open(archive, 'r:bz2') as package:
        members = {member.name: member for member in package.getmembers()}
        for source, destination in entries.items():
            member = members.get(name + '/' + source)
            if member is None and source.startswith('test_wavs/'):
                continue
            if member is None or not member.isfile():
                raise ValueError('Missing model file: ' + source)
            target = ROOT / 'models' / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            with package.extractfile(member) as stream, target.with_suffix(target.suffix + '.part').open('wb') as output:
                shutil.copyfileobj(stream, output)
            target.with_suffix(target.suffix + '.part').replace(target)
    print('Verified and installed ' + name, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--proxy', default='')
    args = parser.parse_args()
    assert len(SPECS) == 3 and all(len(spec[2]) == 64 for spec in SPECS)
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(lambda spec: setup(spec, args.proxy), SPECS))
    print('All offline voice models are ready.', flush=True)
