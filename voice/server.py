"""Loopback-only offline speech service; audio stays in RAM and is never logged."""
import json
import difflib
import re
import secrets
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import sherpa_onnx as sherpa
from polish import Polisher

ROOT = Path(__file__).resolve().parents[1]
RATE = 16000
PORT = 18743


def audio_samples(data):
    if not data or len(data) % 4 or len(data) > RATE * 4:
        raise ValueError('音频分片格式或大小无效')
    samples = np.frombuffer(data, dtype='<f4').copy()
    if not np.isfinite(samples).all() or np.abs(samples).max() > 1.5:
        raise ValueError('音频采样值无效')
    return np.clip(samples, -1, 1)


def clean_text(text):
    return re.sub(r'(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])', '', text.strip())


def join_text(left, right):
    separator = ' ' if left and right and left[-1].isascii() and left[-1].isalnum() and right[0].isascii() and right[0].isalnum() else ''
    return left + separator + right


def refine_text(raw, candidate):
    # shortcut: transcript similarity guards light correction; use confidence-aware rescoring if accuracy requires it.
    english = lambda value: re.findall(r'[a-z][a-z0-9]*', value.lower())
    numbers = lambda value: re.findall(r'\d+(?:[.:/]\d+)*|[零〇一二两三四五六七八九十百千万亿]+', value)
    negatives = lambda value: re.findall(r'不|没|未|别|无|勿', value)
    if not candidate or english(raw) != english(candidate) or numbers(raw) != numbers(candidate) or negatives(raw) != negatives(candidate):
        return raw
    left = re.sub(r'\s+', '', raw.lower())
    right = re.sub(r'\s+', '', candidate.lower())
    return candidate if difflib.SequenceMatcher(None, left, right, autojunk=False).ratio() >= 0.85 else raw


def validate_hotwords(words):
    if not isinstance(words, list) or len(words) > 100 or any(not isinstance(word, str) or not re.fullmatch(r'[\u3400-\u9fff]{1,30}', word) for word in words):
        raise ValueError('热词最多 100 条，每条 1–30 个汉字；英文热词尚未接入')
    return list(dict.fromkeys(words))


class Engine:
    def __init__(self):
        self.lock = threading.Lock()
        self.sessions = {}
        self.ready = False
        self.error = ''
        self.decoding = 'greedy_search'

    def load(self):
        try:
            model = ROOT / 'models'
            self.online = self.make_online(self.decoding)
            self.final = sherpa.OfflineRecognizer.from_paraformer(
                paraformer=str(model / 'final/model.onnx'), tokens=str(model / 'final/tokens.txt'), num_threads=2,
            )
            self.punct = sherpa.OfflinePunctuation(sherpa.OfflinePunctuationConfig(
                model=sherpa.OfflinePunctuationModelConfig(ct_transformer=str(model / 'punctuation/model.onnx'), num_threads=2),
            ))
            self.ready = True
            print('OFFLINE_VOICE_READY', flush=True)
        except Exception as error:
            self.error = '离线模型未能加载，请检查模型文件与安装环境。'
            print('VOICE_LOAD_FAILED: ' + type(error).__name__, flush=True)

    def make_online(self, decoding):
        model = ROOT / 'models'
        return sherpa.OnlineRecognizer.from_transducer(
            tokens=str(model / 'streaming/tokens.txt'), encoder=str(model / 'streaming/encoder.onnx'),
            decoder=str(model / 'streaming/decoder.onnx'), joiner=str(model / 'streaming/joiner.onnx'),
            num_threads=2, provider='cpu', enable_endpoint_detection=True,
            rule1_min_trailing_silence=1.6, rule2_min_trailing_silence=0.8, rule3_min_utterance_length=20,
            decoding_method=decoding, max_active_paths=4, hotwords_score=1.5,
        )

    def start(self, hotwords=None):
        if not self.ready:
            raise ValueError(self.error or '离线模型正在加载，请稍后再试')
        now = time.monotonic()
        self.sessions = {key: value for key, value in self.sessions.items() if now - value['updated'] < 60}
        hotwords = validate_hotwords([] if hotwords is None else hotwords)
        if hotwords:
            tokens = {line.rsplit(' ', 1)[0] for line in (ROOT/'models/streaming/tokens.txt').read_text(encoding='utf-8').splitlines()}
            if any(char not in tokens for word in hotwords for char in word):
                raise ValueError('热词含当前模型不支持的汉字')
        decoding = 'modified_beam_search' if hotwords else 'greedy_search'
        if decoding != self.decoding:
            if self.sessions:
                raise ValueError('请先结束其他页面的录音，再切换热词模式')
            self.online = self.make_online(decoding)
            self.decoding = decoding
        if len(self.sessions) >= 3:
            raise ValueError('已有录音在进行，请先结束其他页面的录音')
        key = secrets.token_urlsafe(24)
        stream = self.online.create_stream(' '.join(hotwords)) if hotwords else self.online.create_stream()
        self.sessions[key] = {'stream': stream, 'chunks': [], 'text': '', 'samples': 0, 'updated': now, 'hotwords': hotwords}
        return {'session': key, 'sampleRate': RATE}

    def decode_segment(self, session):
        raw = clean_text(self.online.get_result(session['stream']))
        if raw and session['chunks']:
            samples = np.concatenate(session['chunks'])
            # Silence-only segments must not be sent through the second-pass recognizer.
            if np.sqrt(np.mean(samples ** 2)) > 0.002:
                stream = self.final.create_stream()
                stream.accept_waveform(RATE, samples)
                self.final.decode_stream(stream)
                candidate = clean_text(stream.result.text)
                if all(word not in raw or word in candidate for word in session.get('hotwords', [])):
                    raw = refine_text(raw, candidate)
            punctuated = self.punct.add_punctuation(raw)
            session['text'] = join_text(session['text'], punctuated)
        session['chunks'] = []

    def chunk(self, key, data):
        session = self.sessions.get(key)
        if session is None or time.monotonic() - session['updated'] > 60:
            self.sessions.pop(key, None)
            raise ValueError('录音已结束或过期，请重新开始')
        samples = audio_samples(data)
        session['samples'] += len(samples)
        if session['samples'] > RATE * 360:
            self.sessions.pop(key, None)
            raise ValueError('单次录音最长六分钟，请分段输入')
        session['updated'] = time.monotonic()
        session['chunks'].append(samples)
        session['stream'].accept_waveform(RATE, samples)
        while self.online.is_ready(session['stream']):
            self.online.decode_stream(session['stream'])
        if self.online.is_endpoint(session['stream']):
            self.decode_segment(session)
            self.online.reset(session['stream'])
        partial = clean_text(self.online.get_result(session['stream']))
        return {'text': join_text(session['text'], partial), 'final': False}

    def finish(self, key):
        session = self.sessions.pop(key, None)
        if session is None:
            raise ValueError('录音已结束或取消')
        session['stream'].accept_waveform(RATE, np.zeros(int(RATE * 0.5), dtype=np.float32))
        session['stream'].input_finished()
        while self.online.is_ready(session['stream']):
            self.online.decode_stream(session['stream'])
        self.decode_segment(session)
        return {'text': session['text'], 'final': True, 'seconds': round(session['samples'] / RATE, 2)}


engine = Engine()
polisher = Polisher()


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT / 'preview'), **kwargs)

    def log_message(self, *args):
        pass

    def reply(self, status, value):
        data = json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        self.wfile.write(data)

    def valid_host(self):
        return self.headers.get('Host') in {f'127.0.0.1:{PORT}', f'localhost:{PORT}'}

    def do_GET(self):
        if not self.valid_host():
            self.reply(403, {'error': '仅允许本机访问'})
        elif self.path == '/api/voice/status':
            self.reply(200, {'ready': engine.ready, 'error': engine.error, 'offline': True, 'engine': 'Zipformer + Paraformer + CT-Transformer'})
        elif self.path == '/api/polish/status':
            self.reply(200, polisher.status())
        else:
            super().do_GET()

    def do_POST(self):
        if not self.valid_host() or self.headers.get('Origin') not in {f'http://127.0.0.1:{PORT}', f'http://localhost:{PORT}'}:
            self.close_connection = True
            self.reply(403, {'error': '录音请求必须来自本机试用页'})
            return
        try:
            length = int(self.headers.get('Content-Length', '-1'))
            if length < 0 or length > RATE * 4:
                raise ValueError('请求过大或缺少长度')
            body = self.rfile.read(length)
            if self.path == '/api/polish':
                payload = json.loads(body)
                if not isinstance(payload, dict):
                    raise ValueError('润色请求格式无效')
                self.reply(200, polisher.polish(payload.get('text')))
                return
            key = self.headers.get('X-Voice-Session', '')
            with engine.lock:
                if self.path == '/api/voice/start':
                    payload = json.loads(body) if body else {}
                    if not isinstance(payload, dict):
                        raise ValueError('录音请求格式无效')
                    result = engine.start(payload.get('hotwords', []))
                elif self.path == '/api/voice/chunk':
                    result = engine.chunk(key, body)
                elif self.path == '/api/voice/finish':
                    result = engine.finish(key)
                elif self.path == '/api/voice/cancel':
                    engine.sessions.pop(key, None)
                    result = {'cancelled': True}
                elif self.path == '/api/voice/keepalive':
                    session = engine.sessions.get(key)
                    if session is None or time.monotonic() - session['updated'] > 60:
                        raise ValueError('录音已结束或过期')
                    session['updated'] = time.monotonic()
                    result = {'paused': True}
                else:
                    self.reply(404, {'error': '未知接口'})
                    return
            self.reply(200, result)
        except ValueError as error:
            self.close_connection = True
            self.reply(400, {'error': str(error)})
        except Exception:
            with engine.lock:
                engine.sessions.pop(self.headers.get('X-Voice-Session', ''), None)
            self.reply(500, {'error': '离线润色失败，原文未修改，请重试' if self.path == '/api/polish' else '本地识别失败，录音已停止，请重新开始'})


def expire_sessions():
    while True:
        time.sleep(15)
        with engine.lock:
            now = time.monotonic()
            engine.sessions = {key: value for key, value in engine.sessions.items() if now - value['updated'] < 60}


if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', PORT), Handler)
    threading.Thread(target=engine.load, daemon=True).start()
    threading.Thread(target=expire_sessions, daemon=True).start()
    print(f'VOICE_PAGE_READY http://127.0.0.1:{PORT}', flush=True)
    server.serve_forever()
