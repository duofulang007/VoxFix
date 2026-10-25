# SenseVoiceSmall R5 — current Android dictation model

Current local Android weights are the same R5 INT8 used by VoxFix Desktop, imported
with `tools/prepare_desktop_voice.py --source <local-desktop-sensevoice-folder>`:

- Model: 239,234,182 bytes; SHA-256 `a002044fef99b50be903b059c39b012072bc3f131df0ad58e59d548cfde7535d`.
- Tokens: SHA-256 `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`.
- CPU INT8, automatic language selection, ITN disabled. The independent CT-Transformer
  runs on final segments with a guard against word/number/negation changes.
- The build verifies the four production asset hashes and packages only R5, tokens,
  VAD and punctuation. Zipformer and the general-model second-pass graph are excluded.
- R5 provenance and local-use conditions: the desktop project's
  `docs/R5模型启用-2026-10-23.md` and `models/sensevoice/UPSTREAM.md`.
  Upstream Apache-2.0 is retained in LICENSE; it does not replace fine-tuning-data
  conditions. Models/data are not committed or redistributed by this repository.

## Previous public baseline (not shipped in the R5 package)

Source model: https://modelscope.cn/models/iic/SenseVoiceSmall

ONNX conversion: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07

- Upstream INT8 SHA-256: `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`.
- Tokens SHA-256: `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`.
- Apache-2.0 license retained in LICENSE.

This replaces the 2025-09-09 ASLP-lab Cantonese fine-tune. General-model language detection is required by the Cantonese second-pass adoption policy; do not substitute a constant-yue LID model.
