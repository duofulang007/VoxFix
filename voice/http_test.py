"""Check the running loopback service with the official sample and hostile origin."""
import json
import urllib.error
import urllib.request
import wave

import numpy as np

from server import ROOT

BASE = 'http://127.0.0.1:18743'


def post(path, session='', data=b'', origin=BASE):
    headers = {'Origin': origin, 'Content-Type': 'application/octet-stream', 'X-Voice-Session': session}
    request = urllib.request.Request(BASE + '/api/voice/' + path, data=data, headers=headers)
    with urllib.request.urlopen(request, timeout=20) as response:
        return json.load(response)


if __name__ == '__main__':
    with urllib.request.urlopen(BASE + '/api/voice/status') as response:
        assert json.load(response)['ready']
    try:
        post('start', origin='https://example.com')
        raise AssertionError('Cross-origin recording was accepted')
    except urllib.error.HTTPError as error:
        assert error.code == 403
    cancelled = post('start')['session']
    assert post('cancel', cancelled)['cancelled']
    try:
        post('chunk', cancelled, np.zeros(3200, dtype='<f4').tobytes())
        raise AssertionError('Cancelled session accepted audio')
    except urllib.error.HTTPError as error:
        assert error.code == 400
    with wave.open(str(ROOT / 'models/test/0.wav'), 'rb') as recording:
        samples = np.frombuffer(recording.readframes(recording.getnframes()), dtype='<i2').astype(np.float32) / 32768
    key = post('start')['session']
    seen = []
    for offset in range(0, len(samples), 3200):
        text = post('chunk', key, samples[offset:offset + 3200].tobytes())['text']
        if text and (not seen or text != seen[-1]):
            seen.append(text)
    final = post('finish', key)
    assert len(seen) >= 2 and final['text'] and final['final']
    print(json.dumps({'http_streaming_updates': len(seen), 'final': final['text'], 'origin_guard': 'passed', 'cancelled_session': 'passed'}, ensure_ascii=False))
