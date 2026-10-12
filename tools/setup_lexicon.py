"""Fetch the pinned supplemental lexicon; retain attribution with generated data."""
import argparse
import hashlib
import urllib.request
from pathlib import Path

REVISION = 'da1fbe602e38f26db846fa10120ee64c2b0324c0'
SOURCE_HASH = '0418d5103d8e40c8fff4b686a873ad03e9836cd0419ecae0b7e1e2526312dba0'
URL = f'https://raw.githubusercontent.com/iDvel/rime-ice/{REVISION}/cn_dicts/base.dict.yaml'


def download(url, target, expected, algorithm='sha256'):
    """Fetch one pinned file, verify its digest, and reuse it when it already matches."""
    target = Path(target)
    target.parent.mkdir(parents=True, exist_ok=True)

    def digest(path):
        with path.open('rb') as stream:
            return hashlib.file_digest(stream, algorithm).hexdigest()

    if target.exists() and digest(target) == expected:
        return target
    part = target.with_suffix(target.suffix + '.part')
    with urllib.request.urlopen(url, timeout=60) as response, part.open('wb') as out:
        out.write(response.read())
    if digest(part) != expected:
        part.unlink()
        raise ValueError(f'上游摘要不一致，已丢弃下载: {target.name}')
    part.replace(target)
    return target


if __name__ == '__main__':
    argparse.ArgumentParser(description=__doc__).parse_args()
    target = download(URL, Path(__file__).resolve().parents[1] / '.model-downloads/rime-base.dict.yaml', SOURCE_HASH)
    print(f'READY {target}')
