# sherpa-onnx Android runtime

Official static-link ONNX Runtime AAR, tag v1.13.8. Apache-2.0 license is preserved in LICENSE.

- Source: https://github.com/k2-fsa/sherpa-onnx/tree/v1.13.8
- Artifact: https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-static-link-onnxruntime-1.13.8.aar
- SHA-256: `b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471`
- Release API reports size 38,691,998 bytes and the same SHA-256. ARM64 ships only `libsherpa-onnx-jni.so`; it does not need the former handwritten Java JNI or a second shared ONNX Runtime.

The former local file bearing the same version name had SHA-256 `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96` and bundled separate runtime libraries. It is not the verified official artifact; prior measurements against it must keep that distinction.
