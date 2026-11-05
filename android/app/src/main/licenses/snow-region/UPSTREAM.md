# Snow Shot visual region detector

VoxFix builds the unchanged Apache-2.0 crates `visual-region-detector` and
`snow-visual-region-detector-c` from Snow Shot 1.1.8, commit
[`afdc340b14e7f92a294a95c374d13ada0d8c7a82`](https://github.com/mg-chao/snow-apps/tree/afdc340b14e7f92a294a95c374d13ada0d8c7a82/snow-crates).
The Android bridge converts the frozen screenshot from ARGB to BGR888 and calls
the upstream C ABI. The APK uses the pure Rust backend.

Long screenshots use the unchanged `snow-stitch-images` and
`snow-stitch-images-c` crates from the same commit and license. The Android
bridge supplies cropped RGBA frames to the upstream frame pool. Additional
dependency notices for the stitcher are in THIRD-PARTY-STITCH.txt.
