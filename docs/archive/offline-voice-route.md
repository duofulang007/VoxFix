# Redmi K60 离线语音输入路线调研

调研日期：2026-10-09。设备与需求由用户提供：Redmi K60、Android 15 / HyperOS 3.0.8.0；中文、免费、本地识别、边说边出字、轻微纠错、自动标点、尽量保留讯飞键盘习惯。本次只读调研，不安装 APK、不下载模型、不修改应用代码。

## 结论

最少重复开发的顺序是：先试用 Feelime 原版，确认手机上的识别速度和准确率；若通过，仅调整键盘外观和常用入口，让字母键位置、键高、浅色主题和麦克风位置靠近现有习惯。不要第一步拆分识别引擎、重写键盘或制作无障碍悬浮输入器。

Feelime 是目前发现最贴合需求的完整开源候选，但不能承诺其效果已经在这台 K60 上验证。它提供完整拼音键盘及内置离线语音，README 声明采用 Zipformer 实时识别、Paraformer 分段重识别、标点恢复；HTML/CSS/JS 键盘可以定制。[官方项目](https://github.com/feelime/feelime)

## 沉浸调用与讯飞边界

完整替代键盘是最沉浸的路线：选择 Feelime 为当前输入法，点聊天框出现键盘；工具栏点麦克风说话，或长按空格说话、松手提交、上滑撤销。该项目已有这些交互，不必自己造。[官方键盘设计 §12](https://github.com/feelime/feelime/blob/main/docs/design/keyboard.md#12-语音离线识别)

保留讯飞为打字键盘，则需要切换到另一个语音 IME。尚未找到讯飞小米版允许第三方替换内置语音按钮的公开证据。不能因为应用实现 RecognitionService，就推断讯飞会调用它。Android 的标准输入机制是 InputMethodService 与宿主 InputConnection；需要选中输入法才能直接服务当前编辑器。[Android IME 文档](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)

Feelime 的 manifest 注册了标准 IME 服务，未看到 RecognitionService 或 RECOGNIZE_SPEECH 接口，因此“作为讯飞的语音插件”不是它的现成能力。[Feelime manifest](https://github.com/feelime/feelime/blob/main/app/src/main/AndroidManifest.xml)

## 技术路线与真实体积

已有实现使用 Kotlin 原生服务、本地 WebView 键盘及 sherpa-onnx；复用时保留其录音生命周期、输入焦点保护、取消撤回和密码框限制。流式结果是临时识别结果，整句重识别发生在停顿后；这属于第二遍 ASR，不能等同于理解语义、删除口头语的语言模型润色。[官方设计](https://github.com/feelime/feelime/blob/main/docs/design/keyboard.md#12-语音离线识别)

模型下载脚本明确安装以下三组量化模型，并固定校验值：流式 Zipformer 中英模型、Paraformer zh-small、CT-Transformer 中英标点。当前脚本还下载手写模型。[setup-assets.sh](https://github.com/feelime/feelime/blob/main/scripts/setup-assets.sh)

不要用 thin 安装包大小代替最终离线占用。构建文件的注释给出 full 约 276MB、thin 约 64MB、ASR 模型约 200MB；这些是源码中的历史预算，尚未核对 v1.3.7 产物实际字节数。[构建配置](https://github.com/feelime/feelime/blob/main/app/build.gradle.kts)

模型清单提供流式 encoder 105,276,499 字节、标点模型 75,519,198 字节，另外还需 decoder、joiner、token 表及整句识别模型；部分模型字节值是构建时补齐的占位值，无法从仓库清单计算准确总和。下载的流式压缩档案还可大于最终选用文件，不能当成安装占用。[模型清单](https://github.com/feelime/feelime/blob/main/models/manifest.json)

建议先接受约几百 MB 的完整链路，测清速度与准确率，再判断是否关闭整句重识别、改更小模型或裁剪不使用的键盘能力。贸然换小模型会失去已有调校，薄包只是改变分发方式，不能让离线模型消失。

## 其他候选为什么不是首选

| 候选 | 已核实能力 | 对本需求的限制 |
| --- | --- | --- |
| Sayboard | 基于 Vosk 的语音 IME；manifest 同时导出 SayboardRecognitionService | 适合复用语音 IME 或标准 RecognitionService 壳，但中文标点、第二遍识别需另补，未证明比 Feelime 更接近完整要求 |
| FUTO Voice Input | Whisper；RECOGNIZE_SPEECH 和 voice subtype IME；支持若干第三方键盘 | 官方明确暂不支持 SpeechRecognizer API；中文简繁表现有已知限制；开发重点移向 FUTO Keyboard，未确认低延迟流式中文 |
| sherpa-onnx Android 示例 | 手机上本地实时识别，已有 APK 与原生示例 | 是引擎和示例，不是完整中文键盘；从示例起步仍需补齐 IME 和编辑器生命周期 |

来源：[Sayboard README](https://github.com/ElishaAz/Sayboard)、[Sayboard manifest](https://github.com/ElishaAz/Sayboard/blob/master/app/src/main/AndroidManifest.xml)、[FUTO 官方 README](https://github.com/futo-org/voice-input)、[sherpa Android 文档](https://k2-fsa.github.io/sherpa/onnx/android/index.html)。

如果原版键盘手感确实无法接受，再考虑第二路线：复用 Sayboard 的 voice IME 壳，加 sherpa 流式识别；从 Feelime 借鉴分段识别、标点、热词和撤销行为。这比调外观工作量更大，还要处理跨输入法切换，不作为第一步。

## 许可证与维护状态

Feelime 当前 README 和 LICENSE 标明 GPL-3.0，1.0.19 及以前历史版本为 MIT。不要把旧搜索缓存的 MIT 当作当前许可证；仓库内置词库也有独立第三方许可链。Sayboard 为 GPL-3.0；sherpa-onnx 为 Apache-2.0；FUTO Voice Input 为 FUTO Source First License 1.0，不能统称为 MIT/Apache 的宽松开源组件。具体模型权重与词库的许可仍需在决定分发版本时逐项核对。[Feelime README](https://github.com/feelime/feelime#许可证)、[Sayboard](https://github.com/ElishaAz/Sayboard)、[sherpa LICENSE](https://github.com/k2-fsa/sherpa-onnx/blob/master/LICENSE)、[FUTO README](https://github.com/futo-org/voice-input#license)

发布页在本次查询显示 v1.3.7 为 latest，页面日期为 10 月 7 日，并列有近期多个版本和手势/手写稳定性修复。这说明近期仍维护，不证明输入法已经长期稳定。[官方发布页](https://github.com/feelime/feelime/releases)

抓取的构建文件为 minSdk 26、compileSdk/targetSdk 36、仅 arm64-v8a；这些配置覆盖 Android 15 的版本范围，但不构成 HyperOS 3 / Redmi K60 实机兼容性证明。网页缓存返回的 main 构建版本仍为 1.2.2，发布页为 1.3.7，实际采用前需固定 tag 并核对 APK 来源、签名、哈希和源码版本。[构建配置](https://github.com/feelime/feelime/blob/main/app/build.gradle.kts)

## 最小实测门槛

用原版、飞行模式、真实手机先验证：冷启动首字时间、连续说话是否持续出字、停顿后纠错/标点延迟、20 条常用话的错字、英文软件名与数字、连续 5 分钟发热和耗电。宿主至少覆盖微信、ChatGPT、浏览器、Obsidian；并检查取消、切换编辑框、切换应用、旋转、收起重开、密码框。只测普通系统 EditText 不足以证明这些应用兼容。

项目已提供语音、宿主编辑器、录音长按、资源预算等设备门禁；其文档也强调识别性能必须在冷真机验证，模拟器不能代替。[官方验证文档](https://github.com/feelime/feelime/blob/main/docs/testing/verification.md)

未验证：K60 实机性能、HyperOS 麦克风行为、各聊天应用 composing 支持、v1.3.7 精确安装占用、第三方模型许可闭包，以及讯飞是否存在未公开接入入口。
