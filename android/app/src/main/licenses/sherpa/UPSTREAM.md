# sherpa-onnx Android runtime

Official static-link ONNX Runtime AAR, tag v1.13.8. Apache-2.0 license is preserved in LICENSE.

- Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- Artifact: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar
- SHA-256: `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`
- Release API reports size 38,691,998 bytes and the same SHA-256. ARM64 ships only `libsherpa-onnx-jni.so`; it does not need the former handwritten Java JNI or a second shared ONNX Runtime.

The former local file bearing the same version name had SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` and bundled separate runtime libraries. It is not the verified official artifact; prior measurements against it must keep that distinction.

Personal hotwords are applied at decode time on the R5 token table shipped as
`sensevoice/tokens.txt` (CTC rescue, `boost=3.0`): `DesktopHotwords`/`DesktopCtcDecoder`
only re-decode a finished segment against the word list, so no extra vocabulary asset is
packaged and no personal words ship in the APK. Latin entries must be written in upper
case because the R5 token table has no lowercase Latin pieces.

The R5 pipeline uses this runtime's neural VAD. Its small
`DesktopVadPosition` adapter mirrors buffer indices from the v1.13.8 implementation:
https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/csrc/voice-activity-detector.cc
It reconstructs the missing Kotlin current-segment start from the native speech flag,
512-sample tail, minimum speech duration and completed segments, without running a
second detector. This is restricted to the pinned v4 VAD graph, SHA-256
`9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6`.
Runtime/model changes require frame-by-frame parity tests before use. The existing
Apache-2.0 license above is retained.
