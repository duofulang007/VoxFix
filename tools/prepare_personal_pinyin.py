"""Build a local seed dictionary without inventing personal frequency history."""
import argparse
import json
from pathlib import Path
import re
from pypinyin import pinyin, Style

ROOT = Path(__file__).resolve().parents[1]


def read_words(text):
    body = text.split('###以下为正文内容###', 1)[1]
    records = {}
    for line in body.splitlines():
        if not line.strip():
            continue
        match = re.fullmatch(r'(\S+)\s+([01])', line.strip())
        if not match:
            raise ValueError('Unexpected user dictionary row')
        word, kind = match.groups()
        records[word] = max(int(kind), records.get(word, 0))
    return records


def seed(records, existing):
    rows, english, review = [], [], []
    for word, kind in records.items():
        if kind == 1:
            review.append({'word': word, 'reason': 'contact_reading'}); continue
        if re.fullmatch('[A-Za-z]{2,32}', word):
            english.append(word); continue
        if not re.fullmatch(r'[\u3400-\u9fff]{1,16}', word):
            review.append({'word': word, 'reason': 'character_format'}); continue
        if word in existing:
            continue
        sounds = pinyin(word, style=Style.NORMAL, heteronym=True)
        if any(len(part) != 1 or not re.fullmatch('[a-z]+', part[0]) for part in sounds):
            review.append({'word': word, 'reason': 'ambiguous_reading'}); continue
        rows.append((word, ' '.join(part[0] for part in sounds)))
    return rows, english, review


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('source', type=Path)
    args = parser.parse_args()
    records = read_words(args.source.read_text(encoding='utf-8-sig'))
    existing = {row[0] for row in json.loads((ROOT/'preview/pinyin-data.json').read_text(encoding='utf-8'))['entries']}
    rows, english, review = seed(records, existing)
    destination = ROOT/'.android/pinyin-rime/personal'
    destination.mkdir(parents=True, exist_ok=True)
    header = '# Local personal vocabulary; frequencies are not imported.\n---\nname: voxfix_personal\nversion: "1"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n'
    # Rime's phonetic compiler needs character entries beneath compound words.
    characters = {(char, sound) for word, code in rows for char, sound in zip(word, code.split())}
    (destination/'voxfix_personal.dict.yaml').write_text(header + ''.join(
        f'{word}\t{code}\t1\n' for word, code in sorted(set(rows) | characters)), encoding='utf-8')
    (destination/'personal-english.txt').write_text('\n'.join(english)+'\n', encoding='utf-8')
    (destination/'review.json').write_text(json.dumps(review, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    summary = {'inputUnique':len(records), 'seedChinese':len(rows), 'english':len(english),
               'review':len(review), 'contacts':sum(row['reason']=='contact_reading' for row in review),
               'characterEntries':len(characters)}
    (destination/'summary.json').write_text(json.dumps(summary, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
