"""Stage the desktop ASCEND corpus for the Android real-device evaluation.

Same manifest the desktop used (work/baseline-20261022/manifest-original.json,
1314 clips), same 16-bit mono 16 kHz samples, byte-identical PCM so the phone can
re-check the desktop checksum before decoding. Written as raw int16 `.pcm` to keep
the pushed payload at half the size of float32.

    python tools/prepare_ascend_eval.py --manifest "D:/Projects/VoxFix Desktop/work/baseline-20261022/manifest-original.json"
"""
import argparse
import hashlib
import json
from pathlib import Path
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.android/ascend-eval-20261025'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--out', type=Path, default=OUT)
    args = parser.parse_args()

    manifest = json.loads(args.manifest.read_text(encoding='utf-8'))
    audio = args.out / 'audio'
    audio.mkdir(parents=True, exist_ok=True)
    rows = []
    for row in manifest:
        identity = row['id']
        with wave.open(row['wav'], 'rb') as source:
            if (source.getnchannels(), source.getsampwidth(), source.getframerate()) != (1, 2, 16000):
                raise ValueError('Unexpected format: ' + identity)
            pcm = source.readframes(source.getnframes())
        digest = hashlib.sha256(pcm).hexdigest()
        if row.get('pcmSha256') and row['pcmSha256'] != digest:
            raise ValueError('Audio checksum mismatch: ' + identity)
        (audio / f'{identity}.pcm').write_bytes(pcm)
        rows.append({'id': identity, 'category': row['category'], 'expected': row['expected'],
                     'samples': len(pcm) // 2, 'pcmSha256': digest, 'seconds': round(len(pcm) / 32000, 3)})
        if len(rows) % 200 == 0:
            print(f'STAGED {len(rows)}', flush=True)

    plan = {
        'source': 'desktop baseline-20261022 manifest-original',
        'manifestSha256': hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
        'delivery': 'unpaced 100 ms (1600 sample) packets, same as the desktop evaluator',
        'count': len(rows),
        'totalSeconds': round(sum(r['seconds'] for r in rows), 1),
        'rows': rows,
    }
    (args.out / 'plan.json').write_text(json.dumps(plan, ensure_ascii=False), encoding='utf-8')
    print(f'PLAN_READY cases={len(rows)} seconds={plan["totalSeconds"]} bytes={sum(r["samples"] for r in rows) * 2}')


if __name__ == '__main__':
    main()
