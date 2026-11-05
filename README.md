# VoxFix 安卓输入法

VoxFix 是可在系统中独立选择的 Android 输入法（`local.voxfix.debug`），参考讯飞小米版的界面与交互，接入本机离线语音。保留手机上的讯飞小米版 `com.iflytek.inputmethod.miui`。

开发从 [docs/开发入口.md](docs/开发入口.md) 开始，协作规则见 [AGENTS.md](AGENTS.md)。当前验收与未解决问题分别见 [USB 真机验收](docs/USB真机验收.md)和[问题清单](docs/现有功能问题清单.md)；历史方案只作参考。

## 当前能力

- **键盘**：原生 WebView 承载 `preview/`，优先维护 26 键；中文拼音使用 librime 1.17.0，支持个人词库、候选记忆、备份与合并导入。
- **离线语音**：SenseVoice Small R5 INT8＋silero VAD＋CT-Transformer；定稿 → CTC 热词救援 → 个人词典 → 标点。长按空格 240ms 进入，上滑取消、松手定稿；支持常驻、逐字呈现和长录音内部接续。键盘出现即预热，空闲 5 分钟释放。不保存日常录音或转写。
- **编辑与剪贴板**：滑轨长按锁定、自定义编辑框、八方向盘及宿主选区弹出；剪贴板拆词、全历史检索与候选引用，工具栏长按拖动排序。
- **屏幕取字**：独立无障碍服务、侧边弧形入口、整屏/框选与结果保护；主包由 ML Kit 定位行、PP-OCRv6 small 识别并做段落排序，不上传截图。独立 OCR 对照宿主在 `android/screenTextUiTest/`。
- **截图工程**：参考 Snow Shot 1.1.8，支持绘图、滤镜、文字/序号、贴图、单对象反复编辑与工具栏自定义；图片并入剪贴板，保存工程后可重开，裁剪另存保留工程。所有工具可从选框外起笔，导出只裁框内。见[实现与验收](docs/截图可编辑工程-实现与验收-2026-11-04.md)。
- **Snow Shot 逐项复刻（进行中）**：目标为全部 44 项（T01–T28 工具动作、A01–A16 辅助入口）。已接画笔、形状、直线、箭头、橡皮、高亮、聚光灯、序号、文字、水印、非矩形选区，以及 T22 的多选、混合态、八向边角缩放、插点、拖段、层级／对齐／分布／复制与移动／缩放吸附；智能选择支持连续点按换目标与文字行／段落细化；T27 手机长截屏已接手动跟拍、自动滚动和实时裁边，成品进图片剪贴板。T08、T15、T16、T22 仍记「实现中」，T28 录屏与 A07 捕捉光标未开工。进度与未闭环项以[交接 §0](docs/SnowShot复刻接续交接-2026-11-04.md)为准；自动化口径是 JVM 368 项与 K60 截图专项套件，**套件通过不等于原版叠图或真人通过**。
- **离线翻译**：截图工具条接 Hy-MT2-1.8B 1.25bit，约 462 MB，首次使用下载，不随 APK 打包；支持原文/译文、段落重排、编辑及撤销/重做。中文长文本译英文仍有超时问题，下载恢复与复杂版面待验，见[OCR 与翻译交接](docs/OCR与翻译续做交接-2026-11-04.md)。
- **其他**：表情包同屏搜索（搜狗图源，联网）、横屏独立布局。手写、安卓 Qwen 润色、电脑语音服务、自动改字学习和旧双遍引擎已删除。

正文黄色标记条与水滴标注已删除；编辑框、八方向盘、滑轨锁定与宿主选区必须保留。

## 目录

| 目录 | 用途 |
|---|---|
| `android/app/src/main/` | 原生输入法、截图、OCR、翻译、资源及许可 |
| `android/app/src/voice/` | R5 离线语音 |
| `android/app/src/test/` / `androidTest/` | JVM 自检与隔离安卓测试 |
| `android/screenTextUiTest/` | 独立、无联网权限的 OCR 对照宿主 |
| `preview/` | APK 共用键盘前端、网页回归、参考图 |
| `tools/` | 构建、模型/词库准备及评测脚本 |
| `docs/` | 当前约定与验收；旧方案在 `archive/`，待决策稿在 `design/` |

## 准备与构建

本机依赖不入库，首次克隆需准备 `.venv`、JDK/Gradle/SDK、拼音数据与模型：

- 公共 VAD/标点：`tools/setup_voice.py`；本机 R5：`tools/prepare_desktop_voice.py --source <R5目录>`。
- 拼音：`tools/setup_pinyin_rime.py`、`tools/prepare_personal_pinyin.py`。
- OCR：`tools/prepare_screen_ocr.py`（构建任务调用）；翻译原生库：`tools/setup_translate_native.py`、`tools/build_translate_native.py`，固定依赖与补丁在 `tools/translate-dependencies.json`、`tools/patches/`。翻译权重在手机首次使用时下载。

```powershell
pwsh -ExecutionPolicy Bypass -File tools/build_android.ps1
```

构建脚本校验依赖摘要，执行 `testDebugUnitTest assembleDebug`，不自动安装。APK 位于 `android/app/build/outputs/apk/debug/app-debug.apk`。Android 测试代码改动另需 `assembleDebugAndroidTest`。

## 验证与网页打包

```powershell
$env:NODE_PATH = 'C:/Users/23221/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules'
node preview/clipboard-images.test.mjs
node tools/check_module_boundaries.mjs
.venv/Scripts/python.exe tools/build_preview.py
```

`preview/*.test.mjs` 共 45 套，其中 38 套不依赖手机，7 套 `*-device` 需要 USB 与 WebView 调试端口转发。只跑受影响功能；修改共用状态、渲染或事件委托时跑全量非真机回归。网页测试使用 Playwright＋Edge。

开发预览使用本地 HTTP 下的 `preview/index.html`；根目录 `键盘试用页.html` 是分发产物。前端改完由 agent 打包，不使用监听模式。结构检查目前允许 `app.mjs` 的体积预警，达到 3000 行或 250 KB 必须拆分；`test-lexicon.mjs` 是测试共用模块，不进产物。

**网页通过 ≠ 原生通过；构建 ≠ 安装；安装 ≠ 真人验收。** 清理只动文档、无引用资源与无引用私有成员，不操作手机；真人触摸、真实麦克风、跨 App、下载恢复与长期稳定性不能由固定样本或构建证明。截图仍受其他应用的安全窗口/DRM 限制，不承诺通用捕获或 OCR 准确率。

## 仓库边界

只提交源码、脚本、必要文档、许可和参考图；模型、SDK/NDK、缓存、APK、密钥、录屏、日志、生成词库及单文件试用页不提交（见 `.gitignore`）。`androidTest/assets/` 的固定合成音频是测试夹具，随库保留。清理不碰用户数据、学习记录、剪贴板、个人词典和运行模型；本地备份与验收证据保留。

远端：[duofulang007/VoxFix](https://github.com/duofulang007/VoxFix)，主分支 `main`。提交和同步状态以实际 Git 检查为准。
