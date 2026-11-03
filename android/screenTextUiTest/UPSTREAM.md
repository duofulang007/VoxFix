# Offline OCR comparison dependencies

Evaluation only; nothing here changes the production app's ML Kit or voice runtime.

- ML Kit Chinese text recognition: `com.google.mlkit:text-recognition-chinese:16.0.1`, bundled model.
- ONNX Runtime Android: `1.20.0`, MIT, from Maven Central.
  SHA256 `07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027`.
  Existing production `1.17.3` rejects these IR10 models (maximum IR9); it remains unchanged.
- PP-OCRv6 original models: PaddlePaddle/PaddleOCR, Apache-2.0.
  Retained upstream license from PaddleOCR commit `dab3fe35379033fdcb2d0e9572fac0b36c9a9ebf`.
- ONNX exports and embedded dictionaries: RapidAI/RapidOCR model release `v3.10.0`.
  Manifest and reference preprocessing/CTC implementation pinned to RapidOCR commit
  `80b427d69bfc5b91a12913394875910296c6f315`.
  Reference files: `python/rapidocr/ch_ppocr_rec/main.py`, `utils.py`, and `default_models.yaml`.
  See `licenses/` for retained licenses and copyright notices.

| Model | SHA256 |
| --- | --- |
| PP-OCRv6_rec_small.onnx | `6f327246b50388f3c176ae304bd95767ea6dc0c9ae92153ef8cbe210b3c14884` |
| PP-OCRv6_rec_medium.onnx | `eef444829dbbe18d7fea59a3f6eb75647518d2b3a9568d27c92e42940204894b` |

`tools/prepare_screen_ocr_eval.py` downloads, verifies and stages these files under ignored
`.android/screen-ocr-eval-20261103/`; models and native runtime binaries must not be committed.
The independent test APK explicitly removes INTERNET permission, and asserts that it is absent.
Only public model preparation needs network; on-device inference is local.

The experimental Java reader uses BGR/CHW, height48, width at least320, bilinear resize,
normalization to [-1,1], normalized zero padding, and greedy CTC blank/repeat removal.
It asserts input/output dimensions, dictionary length, and CTC boundary behavior.
Android Bitmap bilinear interpolation may differ slightly from OpenCV's implementation.
ML Kit supplies line rectangles with a 5% margin; candidate results retain its detection
misses and reading order. No expected text or dictionary correction guides decoding.

Run `offlineCompare=true` to compare the same 96 synthetic images twice plus four blank
images (588 rows across three engines). Cold model copy/session setup is reported separately;
candidate request time includes ML Kit localization and candidate line recognition.
This is a hybrid recognition evaluation, not a complete independent Paddle detection pipeline.
