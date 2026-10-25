"""Download and verify the public VAD and punctuation models packaged by the app.

The ASR weights are not handled here: the active Android R5 package is imported and
pinned by prepare_desktop_voice.py into models/desktop-sensevoice/. This script only
prepares the two remaining public assets that stageModels packs:

- silero-vad, which decides when a pause closes an utterance;
- a CT-Transformer int8 punctuation model, used when a settled segment still needs
  sentence punctuation.

Every download is checked against a fixed SHA-256. Digests are recorded per extracted
file rather than per archive, so the check still holds when a mirror is used instead of
GitHub releases; a mirror cannot silently substitute different weights.
"""
import concurrent.futures
import argparse
import hashlib
import shutil
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GITHUB = 'https://github.com/k2-fsa/sherpa-onnx/releases/download'
MIRROR = 'https://hf-mirror.com/csukuangfj'
ARCHIVES = [
    ('punctuation-models', 'sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8'),
]

# source name inside the archive -> destination under models/, with its digest
FILES = {
    # 词表与标点符号集内嵌在 onnx 元数据里，只需要这一个文件。
    'sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8': {
        'model.int8.onnx': ('punctuation/model.int8.onnx',
                            '65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1'),
    },
}

# Fetched individually because GitHub release objects are large and often unavailable.
SINGLES = [
    ('vad/silero_vad.onnx', f'{GITHUB}/asr-models/silero_vad.onnx',
     '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6'),
]

def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(target: Path, expected: str | None) -> None:
    if expected is None or not target.is_file():
        return
    actual = sha256(target)
    if actual != expected:
        raise ValueError(f'Checksum mismatch for {target}: {actual} != {expected}')


def fetch(url: str, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    scratch = target.with_suffix(target.suffix + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': 'VoxFix-setup'})
    with urllib.request.urlopen(request, timeout=900) as response, scratch.open('wb') as output:
        shutil.copyfileobj(response, output)
    scratch.replace(target)


def setup_archive(release: str, name: str) -> None:
    cache = ROOT / '.model-downloads'
    cache.mkdir(exist_ok=True)
    archive = cache / (name + '.tar.bz2')
    entries = FILES[name]
    if not archive.is_file() or archive.stat().st_size == 0:
        print('Downloading ' + name, flush=True)
        try:
            fetch(f'{GITHUB}/{release}/{archive.name}', archive)
        except Exception as error:  # noqa: BLE001 - fall back to the mirror
            print(f'GitHub unavailable ({error}); trying mirror', flush=True)
            for member in entries:
                fetch(f'{MIRROR}/{name}/resolve/main/{member}', ROOT / 'models' / entries[member][0])
            for member, (destination, expected) in entries.items():
                verify(ROOT / 'models' / destination, expected)
            print('Verified via mirror: ' + name, flush=True)
            return
    with tarfile.open(archive, 'r:bz2') as package:
        members = {member.name: member for member in package.getmembers()}
        for source, (destination, expected) in entries.items():
            member = members.get(f'{name}/{source}')
            if member is None or not member.isfile():
                raise ValueError('Missing model file: ' + source)
            target = ROOT / 'models' / destination
            target.parent.mkdir(parents=True, exist_ok=True)
            scratch = target.with_suffix(target.suffix + '.part')
            with package.extractfile(member) as stream, scratch.open('wb') as output:
                shutil.copyfileobj(stream, output)
            scratch.replace(target)
            verify(target, expected)
    print('Verified and installed ' + name, flush=True)


def setup_single(destination: str, url: str, expected: str) -> None:
    target = ROOT / 'models' / destination
    if not target.is_file() or sha256(target) != expected:
        print('Downloading ' + destination, flush=True)
        fetch(url, target)
    verify(target, expected)
    print('Verified and installed ' + destination, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--proxy', default='')
    parser.parse_args()
    for release, name in ARCHIVES:
        setup_archive(release, name)
    for destination, url, expected in SINGLES:
        setup_single(destination, url, expected)
    print('VAD and punctuation models are ready.', flush=True)
