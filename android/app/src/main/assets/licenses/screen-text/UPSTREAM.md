# Screen text extraction

## Bundled recognition component

- `com.google.mlkit:text-recognition-chinese:16.0.1`, from Google's Maven repository.
- Official integration: https://developers.google.com/ml-kit/vision/text-recognition/v2/android
- Terms (not an open-source runtime license): https://developers.google.com/ml-kit/terms
- Incorporated API terms: https://developers.google.com/terms
- The bundled model works without a first-use download. Images and recognized text are processed on-device. ML Kit may send API performance/utilization metrics and contact Google for SDK maintenance; this is not a promise of zero network traffic.
- Android platform TextView provides selection, handles, cross-line selection and copying. No third-party selection implementation is included.

## Interaction research only

XGesture https://github.com/qpst4/XGesture at commit
`7a0dfde12343ef7180b331380f244b37fa5a041e` was reviewed:
`README_zh.md`, `THIRD_PARTY_NOTICES.md`, `docs/ATTRIBUTIONS.md`,
`inspire/InspireCoordinator.kt`, `inspire/AccessibilityNodeManager.kt`,
`overlay/FloatBallTextPickCoordinator.kt`, `overlay/FloatBallTextPick.kt`,
`overlay/pickresult/PickResultSelectableText.kt` (under `app/src/main/java/com/slideindex/app/`).

XGesture is AGPL-3.0 and identifies GestureEVO (GPL-3.0-only) as a code source,
with Nova Text/FooView interaction references. VoxFix does not copy or link that
code, artwork or framework. No claim is made to modifying FooView source.
Only the public workflow is used as a reference; capture, presentation and
integration here use Android/ML Kit APIs. The host repository has no declared
overall license granting permission to relicense its existing code as AGPL.

Screenshot-only extraction is intentional: existing UI labels cannot prove that
embedded image/video text has also been extracted. One on-demand screenshot/OCR
path keeps behavior consistent. No polling, text-node traversal, translation,
search, saved screenshots or OCR history is added.
