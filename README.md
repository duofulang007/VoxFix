# VoxFix 安卓输入法

VoxFix 是一个安装后可在系统里独立选择的自有 Android 输入法（`local.voxfix.debug`），复刻讯飞小米版的界面与交互，
并接入本机离线语音。它不是对讯飞输入法的功能注入；手机上的讯飞输入法（小米版 `com.iflytek.inputmethod.miui`）保持不动。

**开发请看 [docs/开发入口.md](docs/开发入口.md)**，那里是唯一的当前状态入口。本页只做仓库说明。

## 现在是什么状态

- 独立 ARM64 原生 Debug 输入法，Android 15 / API 35，已在 Redmi K60 上安装并选为当前输入法。
- 键盘前端就是 `preview/` 的原型（构建时按清单同步到 assets，由原生 WebView 承载）：
  26 键中英文、大小写、数字与符号、剪贴板、常用语、表情、键盘选择、夜间模式；
  键盘高度／字号／间距／圆角／背景按官方截图固定，不提供尺寸自定义。
- **中文拼音走原生 librime 1.17.0**：`PinyinEngine.kt` + `cpp/rime_jni.cpp` → `libvoxfix_rime.so`，
  词库为 luna_pinyin 加个人词条，并保留 speller `algebra` 简拼修复。
- **语音走 sherpa-onnx 1.13.8**：Zipformer 中英流式（第一遍，边说边进输入框）+ SenseVoice 通用版第二遍
  （松手**先上屏实时文字**，二遍回来在 composing 内改写并补标点数字）+ silero VAD（阈值 0.4）断句，
  另有独立 CT-Transformer 标点在二遍超时退路时补位。长按空格 300ms 进入语音，上滑取消、松手结束。
  普通打字不加载语音，使用后空闲30秒释放；二遍后台加载，见[语音收尾预算](docs/语音二遍收尾预算与通用模型-2026-10-18.md)。
- 底部滑轨：碰一下即可拖动光标，离触控点越远走得越快（跟手 / 高速两档）；长按在「起点锁定」与「自由移动」
  之间切换，锁定时把光标钉成起点并呼出右侧编辑框，收起面板不等于解锁。
- **删除键有独立连删曲线**（90ms 起步到 30ms 封底），与滑轨摇杆分档速度互不影响，见
  [删除连删速度](docs/删除连删速度-2026-10-18.md)。
- **工具栏**：长按图标 380ms 直接抬起，拖到别的图标上松手即落位并持久保存；没有排序模式或提示行，
  见[工具栏长按拖动](docs/工具栏长按拖动排序-2026-10-18.md)。
- **剪贴板**：离线拆词与连续拖选；最多300条全历史检索，支持字面／全拼／简拼／混拼与受控近似。
  中文26键普通候选为20px自然宽度、可横向滑动；有匹配时在右侧悬浮入口，整入口点击展开210px连体列表，
  点条目引用、长按编辑。引用与剪贴板编辑共用「取消 / 拆词 / 保存」编辑框。
- **拼音个性化学习**：原生成功选词同步到本地记忆，补齐分段短语、全拼／简拼／混拼共享召回与接续联想；
  全量个人词库默认开启并支持完整备份／通用文本导出与合并导入，不保存日常上屏文本，不联网。
- **手写已移除**：停用源码、测试与配套许可已删除，可从Git历史追溯；不打包手写模型或运行库。
- **安卓端 Qwen 本地整理已移除**：手机上不再保留 GGUF 模型，输入法不阻塞上屏、不调用大模型。
- **电脑端语音服务已移除**：`voice/`、`preview/voice.mjs` 与配套服务脚本于 2026-10-18 删除，
  语音只在手机原生侧真实运行；删除内容可从Git历史追溯。

真人长按/取消、跨App与真实麦克风响应仍需在 K60 上逐场景确认。验收记录在
[docs/USB真机验收.md](docs/USB真机验收.md)，未解决项在 [docs/现有功能问题清单.md](docs/现有功能问题清单.md)。

## 目录结构

| 目录 | 作用 |
|---|---|
| `android/` | 原生输入法工程：Kotlin 源码、librime JNI、拼音 schema、许可与测试 |
| `preview/` | 键盘前端源码（同时是浏览器原型）、交互测试、官方参考截图 |
| `tools/` | 构建、模型与词库准备、APK 检查等脚本 |
| `docs/` | 当前文档；历史材料在 `docs/archive/`（索引见其 README） |

## 构建安卓包

```powershell
# 使用 PowerShell 7（pwsh），需要先有 .venv（Python）与 models/（语音模型，tools/setup_voice.py 准备）
pwsh -ExecutionPolicy Bypass -File tools/build_android.ps1
```

脚本会按固定摘要准备 JDK / Gradle / SDK / Build-Tools / sherpa-onnx AAR，然后执行
`testDebugUnitTest assembleDebug`。产物在 `android/app/build/outputs/apk/debug/app-debug.apk`，
**不会自动安装**。拼音 JNI 由 gradle 任务 `buildPinyinNative` 调用 `tools/build_pinyin_rime.py` 编译，
前提是 `.android/pinyin-rime/` 已由 `tools/setup_pinyin_rime.py` + `tools/prepare_personal_pinyin.py` 准备好。

> 个人词库与英文词表必须由 `stagePinyin` 打包，再由 `preparePersonalData()` 落到 `filesDir/pinyin-personal/`。
> 缺失或 metadata 非法时 schema 编译会失败，前端会静默回退，而不是报错。

## 网页交互检查

网页检查是改动后的第一道回归，**不等于原生通过**。每个 `preview/*.test.mjs` 都是独立脚本，用 Node 直接跑：

```bash
node preview/core.test.mjs
```

| 分组 | 脚本 |
|---|---|
| 布局与按压 | `fixed-layout`、`key-press`、`focus-ring`、`interaction-transitions`、`candidates`、`letter-hold`、`toolbar-drag` |
| 滑轨与编辑框 | `selection-lock`、`selection-drag`、`selection-direction`、`selection-editor`、`selection-delete`、`selection-pad` |
| 剪贴板与词库 | `clipboard-search`、`clipboard-match-ime`、`clipboard-reference`、`clipboard-panel-search`、`split`、`learning`、`learning-ime`、`lexicon`、`lexicon-browser` |
| 拼音与英文 | `pinyin`、`english` |
| 原生桥与语音 | `native-ime`、`voice-orb`、`system-audit`、`ime-regressions` |

真机脚本（`preview/*-device.test.mjs`）需要先用 `adb forward` 把 IME WebView 的 devtools 端口转到本机，
并且手机操作要由使用者实际完成。

## 网页试用页（与手机功能面对齐）

`preview/index.html` + `preview/app.mjs` 既是浏览器原型，也是 APK 内的键盘前端。试用页只保留手机端真实存在的功能：
快速翻页、手势说明、语音交互演示（不录音）、剪贴板与个人词库设置。**电脑端语音服务、离线整理（Qwen 润色）、
个人语音热词、长句与领域词库、录屏对照已从页面和相关代码移除**，语音只在手机原生侧真实运行。

- 单文件试用页：`python tools/build_preview.py` 生成根目录 `键盘试用页.html`（生成物，不入库），
  双击即可打开，无需起服务。
- 网页词库（非 librime 路径）：`tools\setup_lexicon.py` → `tools\build_pinyin.py`，
  生成 `preview/pinyin-data.json` 与 `preview/pinyin-lite.json`（生成物，不入库）。

## 仓库边界

只提交源码、脚本、必要文档/许可与浏览器参考图。不提交：模型权重、SDK/NDK、缓存、APK、签名密钥、
录屏、生成的词库与单文件试用页、日志（完整规则见 `.gitignore`）。首次克隆后需另行准备这些本地依赖。

远端为私有仓库 `https://github.com/duofulang007/VoxFix.git`，主分支 `main`。

## 真实性边界

- 语音不是讯飞引擎移植，全部在本机离线运行；录音只在内存处理，不保存录音或文字日志，不联网识别。
- 拼音是 librime + 固定版本公开词库，词频排序和上下文预测不能等同于成熟商用引擎；不宣称与讯飞一致。
- 手写（含电脑端原型手写）与安卓端本地润色都已移除；历史说明在 `docs/archive/`，删除的代码由Git历史保留。
- 电脑端结果不能证明手机效果；延迟、内存、发热与耗电仍需真机实测。
