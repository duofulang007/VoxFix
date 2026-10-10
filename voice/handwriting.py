"""Offline single-character handwriting using the verified Melnyk-Net model."""
import hashlib
import json
import math
import threading
from pathlib import Path

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MODEL_SHA = '04bf65859482f2e9f4836fb872c5a881940a8f6f81bd0994542187617793e932'
VOCAB_SHA = '83863afd12846c337c8880348f1a7e275450be87ba04cf17445cca3ffd0939bd'


def validate_ink(payload):
    width, height = payload.get('w'), payload.get('h')
    if any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or v < 1 or v > 2000 for v in [width, height]):
        raise ValueError('书写区域尺寸无效')
    strokes = payload.get('strokes')
    if not isinstance(strokes, list) or len(strokes) > 100:
        raise ValueError('笔画数据无效')
    count = 0
    for stroke in strokes:
        if not isinstance(stroke, list) or not stroke:
            raise ValueError('笔画数据无效')
        count += len(stroke)
        for point in stroke:
            if not isinstance(point, list) or len(point) != 2 or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in point):
                raise ValueError('笔画坐标无效')
            if not 0 <= point[0] <= width or not 0 <= point[1] <= height:
                raise ValueError('笔画超出书写区域')
    if count > 2000:
        raise ValueError('笔画过多，请先选字或清空后重写')
    return strokes


def preprocess(strokes):
    if not strokes:
        return None
    points = np.asarray([point for stroke in strokes for point in stroke], dtype=np.float64)
    lower, upper = points.min(axis=0), points.max(axis=0)
    span = np.maximum(upper - lower, 1)
    scale = 256 / span.max()
    offset = (256 - span * scale) / 2 - lower * scale
    canvas = Image.new('L', (768, 768), 255)
    drawing = ImageDraw.Draw(canvas)
    pen = 33
    for stroke in strokes:
        coords = [tuple((np.asarray(point) * scale + offset) * 3) for point in stroke]
        if len(coords) > 1:
            drawing.line(coords, fill=0, width=pen, joint='curve')
        for x, y in coords:
            radius = pen / 2
            drawing.ellipse((x - radius, y - radius, x + radius, y + radius), fill=0)
    canvas = canvas.resize((256, 256), Image.Resampling.LANCZOS)
    pixels = np.asarray(canvas)
    ys, xs = np.where(pixels < 200)
    if not len(xs):
        return None
    padx, pady = max(4, int((xs.max() - xs.min() + 1) * .1)), max(4, int((ys.max() - ys.min() + 1) * .1))
    crop = canvas.crop((max(0, xs.min() - padx), max(0, ys.min() - pady), min(256, xs.max() + padx + 1), min(256, ys.max() + pady + 1)))
    values = np.asarray(crop)
    histogram = np.bincount(values.ravel(), minlength=256).astype(np.float64)
    counts = np.cumsum(histogram)
    sums = np.cumsum(histogram * np.arange(256))
    denominators = counts * (counts[-1] - counts)
    variance = np.divide((sums[-1] * counts - sums * counts[-1]) ** 2, denominators, out=np.zeros(256), where=denominators > 0)
    threshold = int(variance.argmax())
    crop = Image.fromarray(np.where(values <= threshold, 0, 255).astype(np.uint8))
    side = max(crop.size)
    square = Image.new('L', (side, side), 255)
    square.paste(crop, ((side - crop.width) // 2, (side - crop.height) // 2))
    output = Image.new('L', (96, 96), 255)
    output.paste(square.resize((88, 88), Image.Resampling.BILINEAR), (4, 4))
    return (255 - np.asarray(output)).astype(np.uint8).reshape(1, 96, 96, 1)


class Handwriting:
    def __init__(self):
        self.ready = False
        self.error = ''
        self.lock = threading.Lock()

    def load(self):
        try:
            model = ROOT / 'models/handwriting/model.onnx'
            if hashlib.sha256(model.read_bytes()).hexdigest() != MODEL_SHA:
                raise ValueError('手写模型校验失败')
            vocabulary = (model.parent / 'vocab.json').read_bytes()
            if hashlib.sha256(vocabulary).hexdigest() != VOCAB_SHA:
                raise ValueError('手写字符表校验失败')
            self.vocabulary = json.loads(vocabulary)
            if len(self.vocabulary) != 3755 or any(not isinstance(char, str) or len(char) != 1 for char in self.vocabulary):
                raise ValueError('手写字符表无效')
            options = ort.SessionOptions()
            options.intra_op_num_threads = 2
            options.inter_op_num_threads = 1
            self.session = ort.InferenceSession(str(model), options, providers=['CPUExecutionProvider'])
            self.input_name = self.session.get_inputs()[0].name
            self.ready = True
        except Exception as error:
            self.error = '手写模型未就绪：' + type(error).__name__

    def recognize(self, payload):
        strokes = validate_ink(payload)
        if not self.ready:
            raise ValueError(self.error or '手写模型正在加载')
        image = preprocess(strokes)
        if image is None:
            return []
        with self.lock:
            scores = self.session.run(None, {self.input_name: image})[0].reshape(-1)
        if len(scores) != len(self.vocabulary) or not np.isfinite(scores).all():
            raise ValueError('手写识别结果无效')
        return [self.vocabulary[int(index)] for index in np.argsort(-scores)[:9]]
