# llama.cpp（翻译运行库）

- 上游：https://github.com/ggml-org/llama.cpp
- 固定提交：`de7fa0a3c6a2e1b4cd9f22eb8d6bf5b12dbdb63b`（2026-10-08）
- 许可：MIT（见同目录 `LICENSE`）
- STQ1_0 补丁来源：https://github.com/dodola/hy-mt2-android/tree/main/patches/llama.cpp
  - 补丁 0001–0003 来自上游 PR #22836（作者 jinlongsong，MIT，PR 仍未合并）
  - 0004 为预填充 2×2 dotprod tile 优化
  - 0005 修复 `GGML_BACKEND_DL` 下线程池只设置一个 CPU 后端的问题
- 本地补丁副本：`tools/patches/llama.cpp-de7fa0a/`，每个文件的 SHA-256 记录在 `tools/translate-dependencies.json`

只编译 `arm64-v8a`，以 `c++_static` 链接成单个 `libvoxfix_translate.so`，产物放在 `.android/translate/jni/`，不进入 Git。应用不分发 llama.cpp 的示例或服务端代码。
