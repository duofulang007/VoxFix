# Nemotron 3.5 ASR 适配评估

核对日期：2026-10-20。本轮只查资料，不更换模型、不改源码、不下载权重、不构建或安装 APK。

> 后续实测已推翻本文「技术上值得做离线候选 A/B」的乐观部分：A/B 做完，K60 上跑不进实时且净准确率无收益，
> 结论改为暂不接入。实测数据见 [Nemotron-3.5-ASR实测-2026-10-20.md](Nemotron-3.5-ASR实测-2026-10-20.md)。

## 外部事实

- **名称真实存在**：`nvidia/nemotron-3.5-asr-streaming-0.6b`，2026-06-04 发布，600M 参数；它是英文 Nemotron Speech Streaming 的多语言扩展。原生输出标点与大小写，缓存编码器状态、只计算新块，支持 80/160/320/560/1120ms 分块。这些属于模型与训练能力，不能直接复制几行逻辑给另一模型。[NVIDIA 模型卡](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)
- **中文可用，但不是最高档语言**：普通话 zh-CN 列为 broad-coverage；40 locales 中仅 32 个开箱转写，另 8 个须微调。粤语未列支持语言，不能当成已有粤语能力。`auto` 的说明是自动识别并标注每条话语语言，不能据此断言句内中英混说优于现有方案。官方没有提供与 VoxFix 同音频、同条件的普通话或中英混说对比。[NVIDIA 模型卡](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b#supported-languages)
- **已有 sherpa-onnx 接入路线**：维护者文档提供 3.5 多语言 80/160/560/1120ms 四种 int8 包；以 560ms 为例，encoder 627M、decoder 14M、joiner 9.1M（文档文件大小原口径，合约 650M）；支持按 stream 指定语言或自动检测。[sherpa 官方说明](https://k2-fsa.github.io/sherpa/onnx/nemo/nemotron-streaming.html)；[支持 PR #3671](https://github.com/k2-fsa/sherpa-onnx/pull/3671) 已于 2026-06-12 合并，包含 Java/Kotlin 已有的 stream option 接口说明。不能说它必须使用 CUDA，也不能因上游支持就宣称 K60 已可用。
- **另一条本地路线**：NVIDIA 官方 `NeMo-Speech.cpp` 提供 ggml 原生 C++、C ABI、CPU/Metal/Vulkan/CUDA 路线，支持该模型与实时采集；此次在其入口说明中未找到 Android/K60 专用构建或性能验收，属于“未核实”，并非“不支持”。[官方仓库](https://github.com/NVIDIA/NeMo-Speech.cpp)；NVIDIA 权重仓库现有 Q8_0 GGUF 为 **742MB**，文件大小不等于运行内存。[官方文件列表](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b/tree/main)
- **热词确有实现，但要分运行时**：NeMo-Speech.cpp 的 cache-aware RNNT 支持无外置语言模型的 greedy word boosting，可提供姓名/术语短语并限制强度；这不证明 sherpa 的 Nemotron 解码路径可直接沿用 VoxFix 的 Zipformer 热词参数，需要单独确认。[NVIDIA 配置与 word boosting](https://github.com/NVIDIA/NeMo-Speech.cpp/blob/main/docs/asr/configuration.md#word-boosting)
- **许可要分开保留**：模型卡标注 OpenMDW-1.1；NeMo-Speech.cpp 自有代码为 Apache-2.0，第三方组件另有许可。NIM 容器、API 与权重的条款不同，不能统称一个开源许可。[模型卡许可](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b#licenseterms-of-use)、[运行库许可](https://github.com/NVIDIA/NeMo-Speech.cpp#license)、[NIM 条款](https://build.nvidia.com/nvidia/nemotron-asr-streaming/modelcard)

## 对 VoxFix 的建议

技术上值得做离线候选 A/B：试验替换第一遍流式模型，避免叠加第三遍。当前 `android/app/build.gradle.kts` 引用 sherpa-onnx 1.13.8 AAR，`SpeechEngine.kt` 使用 `OnlineRecognizer`，模型类型固定为 `zipformer`；优先复用这条运行时，避免先引入第二套 C++ 运行库。版本较新只说明有适配基础，不能代替 Kotlin 语言选项与实际解码验证。最值得对照的是实时标点、英语术语与中英混说；缓存流式与热词不是现有项目完全缺失的能力。普通话尚无同集收益，粤语没有官方覆盖，暂不直接替换日常模型。二遍、CT 标点回退和个人词库可保留复用；新路径的热词解码、实时标点与现有后处理是否冲突须单独回归。

先用现有固定音频比较普通话、粤语、中英混说、否定词、金额单位与人名；确认文字收益后，才测 K60 的冷启动、首字、稳定输出、松手收尾、峰值内存、持续录入温升和耗电。80ms 是可选音频块长度，不是 K60 首字延迟承诺；官方 GPU/桌面 CPU 吞吐不能外推手机。

未做模型下载、源码接入或 K60 测试；主要风险是内存与耗电增加、中文/粤语退步、以及当前热词和松手收尾行为不兼容。


