# Handwriting upstream

Pure ink preprocessing is adapted from Feelime v1.3.7, commit `011b2786402c75f3b7c58699482a91d4aab820ef`, `app/src/main/java/com/feelime/ime/HandwritingEngine.kt`. GPL-3.0 license is preserved in LICENSE. Bounds and total point validation are tightened; upstream debug ink persistence and diagnostics are not copied. Wrapper uses the existing pinned Melnyk model and vocabulary; no user ink is stored.

- Model SHA-256: `04bf65859482f2e9f4836fb872c5a881940a8f6f81bd0994542187617793e932`
- Vocabulary SHA-256: `83863afd12846c337c8880348f1a7e275450be87ba04cf17445cca3ffd0939bd`
- sherpa static-link ONNX v1.13.6 SHA-256: `01e87037afca2ed49085062aace5c012e60321e8e23e3a72b6d9ac02c843f66c`
- Microsoft ONNX Runtime Android 1.27.0 AAR SHA-256: `077dec5e2d821234c7dc0aba584bec8f999854b546c754cab93a90741c56fbeb`

The earlier local upstream design snapshot said 1.27.1 plus pickFirst. The fixed source instead uses static-link sherpa with Android ONNX 1.27.0: sherpa embeds its own 1.27.1, while the Java JNI and the single shared library use 1.27.0 together. Do not replace this with a shared-library pickFirst workaround. Speech models and Kotlin API version remain unchanged; K60 speech regression remains required after library changes.

Source: https://github.com/feelime/feelime/tree/011b2786402c75f3b7c58699482a91d4aab820ef
Runtime: https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.27.0/
This work does not make a new public release or settle separate training-data/model redistribution rights.
