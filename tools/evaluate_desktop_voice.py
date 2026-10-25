"""Prepare a desktop-reference replay for Android; never reads the personal dictionary.

Run with VoxFix Desktop's Python (its pinned sherpa runtime is Python 3.13).
Only existing fixed test fixtures are copied from the device. No microphone is used.
"""
import argparse
from collections import deque
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.android/desktop-voice-20261025'
MODEL_HASH = 'a002044fef99b50be903b059c39b012072bc3f131df0ad58e59d548cfde7535d'
IDS = {
    'new-short-0', 'new-short-1', 'new-short-2', 'new-short-3',
    'plain-zh-0', 'plain-zh-7', 'plain-zh-8', 'long-0-0', 'long-2-600',
    'mix-huihui-1', 'mix-switch-1', 'mix-huihui-5',
    'word-0', 'word-1', 'word-2', 'sentence-0',
    'public-yue-0', 'public-yue-11', 'public-para-yue', 'public-yue-long',
    'new-negation-0', 'new-negation-1', 'new-negation-2',
    'new-number-0', 'new-number-3', 'new-short-0-quiet',
    'new-negation-0-quiet', 'new-short-1-noise15', 'new-everyday-0',
    'new-short-2-fast', 'plain-zh-12-quiet', 'silence-0', 'silence-1',
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def adb(*args):
    return subprocess.check_output([str(ROOT / '.android/sdk/platform-tools/adb.exe'), *args])


def validate_rows(records):
    if len({row['id'] for row in records}) != len(records):
        raise ValueError('Duplicate fixture identity')
    for row in records:
        if not row['frames'] or row['frames'][-1]['at'] != row['samples']:
            raise ValueError('Missing release event')
        previous = 0
        for frame in row['frames']:
            if not previous <= frame['at'] <= row['samples']:
                raise ValueError('Non-monotonic replay clock')
            previous = frame['at']
            for start, end in frame['finals'] + ([frame['draft']] if frame['draft'] else []):
                if not 0 <= start < end <= frame['at']:
                    raise ValueError('Invalid audio boundary')


def prepare(desktop):
    import numpy as np
    sys.path.insert(0, str(desktop / 'app'))
    import recognize as source

    OUT.mkdir(parents=True, exist_ok=True)
    model = desktop / 'models/sensevoice/model.int8.onnx'
    if digest(model) != MODEL_HASH:
        raise ValueError('R5 model identity changed; evaluate a new pinned candidate explicitly')
    tokens = desktop / 'models/sensevoice/tokens.txt'
    if digest(tokens) != digest(ROOT / 'models/sensevoice/tokens.txt'):
        raise ValueError('Android/desktop token vocabularies differ')
    session, _ = source.load_model()
    records, found = [], set()
    for folder, sub in [('voice-quality-20261018', 'fixtures/'), ('mandarin-stress-20261018', '')]:
        cases = json.loads(adb('exec-out', 'run-as', 'local.voxfix.debug', 'cat', f'cache/{folder}/cases.json'))
        for case in cases:
            identity = case['id']
            if identity not in IDS:
                continue
            found.add(identity)
            if not re.fullmatch('[a-z0-9-]+', identity):
                raise ValueError('Unsafe fixture id')
            raw = adb('exec-out', 'run-as', 'local.voxfix.debug', 'cat', f'cache/{folder}/{sub}{identity}.f32')
            audio = np.frombuffer(raw, dtype='<f4')
            if not 0 < audio.size <= 16000 * 90 or not np.isfinite(audio).all() or np.abs(audio).max() > 1.001:
                raise ValueError('Invalid fixed fixture')
            identity = ('vq-' if sub else 'ms-') + identity
            (OUT / f'{identity}.f32').write_bytes(raw)
            # Use the actual desktop segmentation, gain and scheduling rules, without a worker.
            # Export arrivals; the Android worker decides which obsolete drafts to skip at phone speed.
            recognition = source.Recognition(session)
            recognition.rescue = None
            recognition.cancelled = False
            frames, finals = [], []
            for end in range(1600, audio.size + 1600, 1600):
                start = end - 1600
                recognition.accept_frame(audio[start:min(end, audio.size)])
                pending = list(recognition.pending)
                recognition.pending = deque()
                segments = [[row[1], row[2]] for row in pending]
                finals.extend(segments)
                draft = recognition.draft
                frames.append({'at': recognition.samples, 'finals': segments,
                               'key': recognition.active_key[0] if recognition.active_key else -1,
                               'draft': [draft[0][0], recognition.samples] if draft else None})
                recognition.draft = None
            recognition.vad.flush()
            recognition.collect_segments(flushing=True)
            segments = [[row[1], row[2]] for row in recognition.pending]
            finals.extend(segments)
            frames.append({'at': int(audio.size), 'finals': segments, 'key': -1, 'draft': None})
            reference, rejected, decode_ms = '', False, 0.0
            for start, end in finals:
                at = time.perf_counter()
                text = re.sub(r'<\|[^|]*\|>', '', session.run(source.QuietBoost().process(audio[start:end])).text).strip()
                text = session.punctuate(text)
                decode_ms += (time.perf_counter() - at) * 1000
                if source.outside_product_scripts(text):
                    rejected = True
                    break
                reference = source.join_text(reference, text)
            records.append({'id': identity, 'category': case.get('category', 'unknown'),
                            'expected': case.get('expected', ''), 'samples': int(audio.size),
                            'audioSha256': hashlib.sha256(raw).hexdigest(), 'frames': frames,
                            'referenceText': reference, 'referenceRejected': rejected,
                            'desktopFinalDecodeMs': round(decode_ms, 2)})
            print(f'PREPARED {identity} {audio.size / 16000:.2f}s', flush=True)
    missing = IDS - found
    if missing:
        raise ValueError(f'Missing required fixtures: {sorted(missing)}')
    validate_rows(records)
    receipt = {'modelSha256': MODEL_HASH, 'tokensSha256': digest(tokens),
               'desktopSources': {name: digest(desktop / 'app' / name) for name in
                                  ('recognize.py', 'dictionary_rules.py', 'hotwords.py')},
               'scope': 'desktop VAD plan + empty dictionary; Android native decode/scheduler; no microphone',
               'rows': records}
    (OUT / 'plan.json').write_text(json.dumps(receipt, ensure_ascii=False), encoding='utf-8')
    print(f'PLAN_READY cases={len(records)} output={OUT}', flush=True)


def score(paths):
    def normal(text):
        # Character score only; not an English word-error rate or a number-format equivalence.
        return ''.join(c for c in text.lower() if c.isalnum())

    def distance(left, right):
        previous = list(range(len(right) + 1))
        for i, char in enumerate(left, 1):
            row = [i]
            for j, other in enumerate(right, 1):
                row.append(min(row[-1] + 1, previous[j] + 1, previous[j - 1] + (char != other)))
            previous = row
        return previous[-1]

    def quantile(values, q):
        values = sorted(values)
        return values[min(len(values) - 1, int((len(values) - 1) * q))] if values else None

    reports = []
    for path in paths:
        report = json.loads(path.read_text(encoding='utf-8'))
        rows = report['rows']
        total = sum(len(normal(row['expected'])) for row in rows)
        errors = sum(distance(normal(row['expected']), normal(row['finalText'])) for row in rows)
        summary = {'mode': report['mode'], 'cases': len(rows), 'loadMs': report['loadMs'],
                   'exact': sum(normal(row['expected']) == normal(row['finalText']) for row in rows),
                   'characterErrorRate': errors / total if total else None,
                   'firstP50': quantile([r['firstTextMs'] for r in rows if r['firstTextMs'] >= 0], .5),
                   'firstP95': quantile([r['firstTextMs'] for r in rows if r['firstTextMs'] >= 0], .95),
                   'noText': sum(r['firstTextMs'] < 0 for r in rows),
                   'releaseP50': quantile([r['releaseMs'] for r in rows], .5),
                   'releaseP95': quantile([r['releaseMs'] for r in rows], .95),
                   'stableP50': quantile([r.get('stableMs', r['releaseMs']) for r in rows], .5),
                   'stableP95': quantile([r.get('stableMs', r['releaseMs']) for r in rows], .95),
                   'peakPssKiB': report['peakPssKiB'],
                   'cpuMs': sum(r['cpuMs'] for r in rows)}
        if report['mode'] in ('t2', 't4'):
            summary['desktopParity'] = sum(r['finalText'] == r['referenceText'] and
                                           r['rejected'] == r['referenceRejected'] for r in rows)
        reports.append(summary)
    print(json.dumps(reports, ensure_ascii=False, indent=2))


def export_rules(desktop):
    sys.path.insert(0, str(desktop / 'app'))
    import recognize as source
    import dictionary_rules as dictionary

    rows = []
    rules = [{'to': 'APP', 'from': ['app', 'app p', 'a p p']},
             {'to': 'Target', 'from': ['wrong name'], 'context': 'zh'},
             {'to': '$1', 'from': ['cost']},
             {'to': 'final', 'from': ['Target']}]
    compiled = dictionary.compile_dictionary({'rules': rules})
    for text, force in [('app p and a p p', False), ('wrong name', False), ('wrong name', True),
                        ('这个wrong name', False), ('myapp app', False), ('COST costlier', False)]:
        rows.append({'op': 'dictionary', 'rules': rules, 'text': text, 'force': force,
                     'expected': dictionary.apply_dictionary(text, compiled, force=force)})
    for original, result in [('不要取消', '要取消。'), ('128.5元', '1285元。'), ('.NET', 'NET。'),
                             ('你好。', '你好。。'), ('你好，', '你好，。'), ('API 成功', 'API 成功。'),
                             ('API\u3000成功', 'API\u3000成功。'), ('API\u00a0成功', 'API\u00a0成功。'),
                             ('价格128.5元', '价格128.5元。'), ('hello world', 'hello, world.')]:
        value = original
        if source.punctuation_only(original, result):
            cleaned = source.drop_inserted_punctuation(original, result)
            if source.punctuation_only(original, cleaned):
                value = cleaned
        rows.append({'op': 'punctuation', 'text': original, 'result': result, 'expected': value})
    for text in ['中文API 128.5元', 'こんにちは', '한국어', 'emoji😀', '中文\u0085API', '中文\u3000API']:
        rows.append({'op': 'allowed', 'text': text, 'expected': not source.outside_product_scripts(text)})
    for left, right in [('API', 'test'), ('中文', 'API'), ('128', '元'), ('hello.', 'World')]:
        rows.append({'op': 'join', 'left': left, 'right': right, 'expected': source.join_text(left, right)})
    out = ROOT / '.android/desktop-live-20261025/rules.json'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({'sources': {name: digest(desktop / 'app' / name) for name in
                                         ('recognize.py', 'dictionary_rules.py')}, 'rows': rows}, ensure_ascii=False), encoding='utf-8')
    print(f'RULE_GOLDENS_READY cases={len(rows)} output={out}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--desktop', type=Path, default=Path('D:/Projects/VoxFix Desktop'))
    parser.add_argument('--score', nargs='+', type=Path)
    parser.add_argument('--rules', action='store_true', help='Export synthetic rule cases from desktop source; no personal words')
    args = parser.parse_args()
    if args.score:
        score(args.score)
    elif args.rules:
        export_rules(args.desktop)
    else:
        prepare(args.desktop)
