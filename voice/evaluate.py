"""Evaluate an explicitly supplied WAV/transcript manifest; never records a microphone."""
import argparse
import json
import re
import time
import wave
from pathlib import Path
import numpy as np
from server import Engine, RATE, validate_hotwords


def cer(expected, actual):
    normalize = lambda text: re.sub(r'[\s，。！？、；：,.!?;:]', '', text).lower()
    left, right = normalize(expected), normalize(actual)
    if not left:
        raise ValueError('参考转写不能为空')
    row = list(range(len(right)+1))
    for i, char in enumerate(left, 1):
        next_row = [i]
        for j, other in enumerate(right, 1):
            next_row.append(min(next_row[-1]+1, row[j]+1, row[j-1]+(char!=other)))
        row = next_row
    return row[-1]/len(left)


def evaluate(manifest):
    cases = json.loads(manifest.read_text(encoding='utf-8'))
    if not isinstance(cases, list) or not 1<=len(cases)<=100:
        raise ValueError('评测清单必须包含 1–100 条样本')
    engine = Engine();engine.load()
    if not engine.ready:
        raise RuntimeError(engine.error)
    results = []
    for case in cases:
        if not isinstance(case, dict) or not isinstance(case.get('expected'), str) or not case['expected'].strip() or len(case['expected'])>12000:
            raise ValueError('每条样本必须有正确参考转写 expected，最长 12000 字')
        source = (manifest.parent/case['audio']).resolve()
        with wave.open(str(source), 'rb') as recording:
            if recording.getnchannels()!=1 or recording.getsampwidth()!=2 or recording.getframerate()!=RATE or recording.getnframes()>RATE*360:
                raise ValueError('请使用六分钟以内的 16kHz 单声道 PCM16 WAV')
            samples = np.frombuffer(recording.readframes(recording.getnframes()), dtype='<i2').astype(np.float32)/32768
        key = engine.start(validate_hotwords(case.get('hotwords', [])))['session']
        started = time.perf_counter();first = None;updates = [];latencies = []
        for offset in range(0, len(samples), 3200):
            chunk_started = time.perf_counter()
            result = engine.chunk(key, samples[offset:offset+3200].tobytes())
            latencies.append(time.perf_counter()-chunk_started)
            if result['text'] and (not updates or updates[-1]!=result['text']):
                updates.append(result['text'])
                if first is None:
                    first = (offset+3200)/RATE
        final = engine.finish(key)['text'];elapsed = time.perf_counter()-started
        results.append({'audio': case['audio'], 'expected': case['expected'], 'actual': final, 'cer': round(cer(case['expected'],final),4), 'audioSeconds': len(samples)/RATE, 'processingSeconds': round(elapsed,3), 'firstTextAudioSeconds': first, 'streamingUpdates': len(updates), 'maxChunkSeconds': round(max(latencies,default=0),3), 'hotwords': case.get('hotwords', [])})
    return {'scope': 'Offline file evaluation, not real-time microphone or phone latency', 'cases': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser();parser.add_argument('manifest', type=Path);parser.add_argument('output', type=Path);args = parser.parse_args()
    args.output.write_text(json.dumps(evaluate(args.manifest),ensure_ascii=False,indent=2),encoding='utf-8')
    print('EVALUATION_SAVED',args.output)
