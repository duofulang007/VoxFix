# Upstream WeText

Processor, token parser and string helpers copied from `wenet-e2e/WeTextProcessing` v1.2.0, Apache-2.0; original notices and LICENSE retained.

Source snapshot: https://github.com/wenet-e2e/WeTextProcessing/tree/v1.2.0/runtime
Archive SHA-256: f80303fd6998f4514c703a24cf83704903e3bcbf67e3494eb9108746b411dad6

Compatibility edits only: explicit BYTE constructors for the older Android OpenFst API, and throw if a graph cannot load. No arithmetic or normalization rules reimplemented.

Graphs: official `wetext` PyPI 0.1.8, Apache-2.0. OpenFst Android library: upstream Android example's `wenet-openfst-android:1.0.2` dependency. Version, archive and graph digests are fixed in `tools/setup_text_normalization.py`.

WeText works on numeric spans here; applying its whole-sentence ITN changed `十三香` and unit spelling in evaluation. Application-specific selection and original-text guards remain outside upstream code.
