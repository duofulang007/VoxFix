"""Import the pinned local R5 once, then verify only the four assets shipped on Android."""
import argparse
import hashlib
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = {
    'desktop-sensevoice/model.int8.onnx': 'a002044fef99b50be903b059c39b012072bc3f131df0ad58e59d548cfde7535d',
    'desktop-sensevoice/tokens.txt': 'f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc',
    'vad/silero_vad.onnx': '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6',
    'punctuation/model.int8.onnx': '65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1',
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def verify(path, expected):
    if not path.is_file() or digest(path) != expected:
        raise ValueError(f'Model identity mismatch or missing: {path}')


def prepare(source=None):
    if source is not None:
        records = [(name, expected) for name, expected in ASSETS.items() if name.startswith('desktop-sensevoice/')]
        for name, expected in records:
            verify(source / Path(name).name, expected)
        for name, expected in records:
            target = ROOT / 'models' / name
            if target.is_file() and digest(target) == expected:
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            scratch = target.with_suffix(target.suffix + '.part')
            shutil.copyfile(source / Path(name).name, scratch)
            verify(scratch, expected)
            scratch.replace(target)
    for name, expected in ASSETS.items():
        verify(ROOT / 'models' / name, expected)
    print('DESKTOP_R5_ASSETS_VERIFIED files=4')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, help='Local desktop sensevoice folder; never downloads or copies personal words')
    prepare(parser.parse_args().source)
