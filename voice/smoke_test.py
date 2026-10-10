"""Run the complete offline pipeline on the upstream model's own test recording."""
import json
import time
import wave

import numpy as np

from server import Engine, RATE, ROOT


def run():
    engine = Engine()
    started = time.perf_counter()
    engine.load()
    assert engine.ready, engine.error
    load_seconds = time.perf_counter() - started
    source = ROOT / 'models/test/0.wav'
    with wave.open(str(source), 'rb') as recording:
        assert recording.getnchannels() == 1 and recording.getsampwidth() == 2
        assert recording.getframerate() == RATE
        samples = np.frombuffer(recording.readframes(recording.getnframes()), dtype='<i2').astype(np.float32) / 32768
    key = engine.start()['session']
    updates = []
    started = time.perf_counter()
    for offset in range(0, len(samples), 3200):
        result = engine.chunk(key, samples[offset:offset + 3200].tobytes())
        if result['text'] and (not updates or updates[-1] != result['text']):
            updates.append(result['text'])
    final = engine.finish(key)
    elapsed = time.perf_counter() - started
    assert len(updates) >= 2, 'Streaming recognition did not produce multiple updates'
    assert final['text'] and any(mark in final['text'] for mark in '，。！？,.!?'), 'Final result has no punctuation'
    silence = engine.start()['session']
    engine.chunk(silence, np.zeros(8000, dtype='<f4').tobytes())
    assert engine.finish(silence)['text'] == '', 'Silence produced text'
    report = {
        'source': 'Official model test_wavs/0.wav, not a microphone recording',
        'load_seconds': round(load_seconds, 3), 'audio_seconds': round(len(samples) / RATE, 3),
        'processing_seconds': round(elapsed, 3), 'streaming_update_count': len(updates),
        'first_updates': updates[:4], 'final': final['text'], 'silence': 'passed',
        'model_bytes': sum(file.stat().st_size for folder in ['streaming', 'final', 'punctuation'] for file in (ROOT / 'models' / folder).iterdir()),
    }
    (ROOT / 'docs/offline-voice-smoke.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    run()
