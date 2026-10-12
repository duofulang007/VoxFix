"""Build shared word records: full spelling, initials and T9 reuse the same IDs."""
import hashlib
import importlib.resources
import json
import re
from pathlib import Path
from pypinyin import lazy_pinyin, pinyin, Style
from setup_lexicon import SOURCE_HASH

ROOT = Path(__file__).resolve().parents[1]
words = {}


def add(word, frequency, syllables=None):
    syllables = syllables or lazy_pinyin(word, style=Style.NORMAL)
    if not syllables or not all(re.fullmatch('[a-z]+', value) for value in syllables):
        return
    key = (word, tuple(syllables))
    words[key] = max(frequency, words.get(key, 0))


with importlib.resources.files('jieba').joinpath('dict.txt').open(encoding='utf-8') as source:
    for line in source:
        word, frequency, *_ = line.split()
        if not int(frequency) or not re.fullmatch(r'[\u3400-\u9fff]+', word):
            continue
        add(word, int(frequency))
        if len(word) == 1:
            for pronunciation in pinyin(word, style=Style.NORMAL, heteronym=True)[0]:
                add(word, max(1, int(frequency)//100), [pronunciation])

rime = ROOT / '.model-downloads/rime-base.dict.yaml'
if not rime.exists():
    raise FileNotFoundError('请先运行 tools/setup_lexicon.py 准备固定版本的雾凇基础词库')
assert hashlib.sha256(rime.read_bytes()).hexdigest() == SOURCE_HASH, '词库校验失败'
for line in rime.read_text(encoding='utf-8').splitlines():
    if line.startswith('#'):
        continue
    columns = line.split('\t')
    if len(columns) == 3 and columns[2].isdigit() and int(columns[2]) >= 3000:
        add(columns[0], min(100000, int(columns[2])//10), columns[1].split())

packs = json.loads((ROOT/'tools/phrase-packs.json').read_text(encoding='utf-8'))
for word in packs['daily'].split('|'):
    add(word, 200000)
syllables = sorted({s for _, codes in words for s in codes})
ids = {s: i for i, s in enumerate(syllables)}
entries = [[word, freq, [ids[s] for s in codes]] for (word, codes), freq in sorted(words.items(), key=lambda row: -row[1])]
characters = {}
for word, _, code in entries:
    if len(word) == 1 and word not in characters:
        characters[word] = code[0]
data = {'schema': 2, 'syllables': syllables, 'entries': entries, 'characters': characters}
target = ROOT/'preview/pinyin-data.json'
target.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
lite = dict(data, entries=[row for row in entries if len(row[0]) == 1 or row[1] >= 10000])
(ROOT/'preview/pinyin-lite.json').write_text(json.dumps(lite, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
optional = {}
for name in ['quotes', 'technology', 'running']:
    optional[name] = []
    for text in packs[name].split('|'):
        chinese = ''.join(re.findall(r'[\u3400-\u9fff]', text))
        optional[name].append([text, ' '.join(lazy_pinyin(chinese, style=Style.NORMAL))])
(ROOT/'preview/phrase-packs.json').write_text(json.dumps(optional, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
(ROOT/'preview/pinyin-initials.json').unlink(missing_ok=True)
assert any(word == '我知道了' for word, _, _ in entries)
print(f'Built {len(entries):,} shared records / {target.stat().st_size:,} bytes; no initials truncation')
