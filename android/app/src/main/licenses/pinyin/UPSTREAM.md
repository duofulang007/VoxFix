# Rime Android core and dictionary

Provider: https://github.com/feelime/feelime
Pinned commit: 9377b95c94e55d1ec1fc0b9007c24a09b38df475
Core librime commit: 417db2385f732cb0fa194b497042c42abb897d99 (1.17.0)

Only the unmodified ARM64 librime core, matching C++ runtime, one precompiled
Luna/Frost dictionary, and simplified-Chinese conversion data are used.
No Feelime keyboard, ASR, other input languages, Octagram model or Lua is bundled.

The Feelime C adapter is GPL-3.0; librime is BSD-3-Clause. Dictionary and
linked-component licenses and original notices are packaged alongside this
file from the pinned upstream license closure. The matching source and build
recipes are available at the pinned repository commit above, including
spikes/native-engine-smoke/native-src/rime/feelime_rime_bridge.cpp and
third_party/manifest.json. The public API header is from the pinned librime tree.

Every downloaded file is checked against its Git blob ID; published SHA-256
values are also checked for the core, runtime, and compiled dictionary.
The local JNI adapter is compiled with the existing Android NDK r28b.
Packaging strips debug symbols from verified copies; originals remain intact.

The vendored `rime_levers_api.h` is the unmodified BSD-licensed header from
`rime/librime@417db2385f732cb0fa194b497042c42abb897d99/src/rime_levers_api.h`.
It is used only to export historical user dictionaries before migrating them to
the local unified personal lexicon. Existing Rime user database files are retained.
