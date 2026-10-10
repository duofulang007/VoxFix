"""Fetch the pinned supplemental lexicon; retain attribution with generated data."""
from pathlib import Path
from setup_polish import download

REVISION = 'da1fbe602e38f26db846fa10120ee64c2b0324c0'
SOURCE_HASH = '0418d5103d8e40c8fff4b686a873ad03e9836cd0419ecae0b7e1e2526312dba0'
if __name__ == '__main__':
    download(f'https://raw.githubusercontent.com/iDvel/rime-ice/{REVISION}/cn_dicts/base.dict.yaml', Path(__file__).resolve().parents[1]/'.model-downloads/rime-base.dict.yaml', SOURCE_HASH)
