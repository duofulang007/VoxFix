# General SenseVoiceSmall

Source model: https://modelscope.cn/models/iic/SenseVoiceSmall

ONNX conversion: https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/2365baeacb507f821a0c8120fcee3d484dba7a07

- Upstream INT8 SHA-256: `c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51`.
- Tokens SHA-256: `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc`.
- Apache-2.0 license retained in LICENSE. Build-time CPU graph optimization preserves metadata and interfaces; its receipt remains local.

This replaces the 2025-09-09 ASLP-lab Cantonese fine-tune. General-model language detection is required by the Cantonese second-pass adoption policy; do not substitute a constant-yue LID model.
