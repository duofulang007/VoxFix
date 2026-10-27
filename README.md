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
- **语音走桌面R5的本地流程**：sherpa-onnx 1.13.8 + SenseVoiceSmall R5 INT8（两线程、自动语种、ITN 关闭）
  + silero VAD + CT-Transformer 标点；不再打包 Zipformer，旧的流式+二遍引擎已删除。
  长按空格240ms进入，上滑取消、松手完成定稿；键盘出现即预热，空闲5分钟释放模型，单轮最多3分钟。
  文本层只有一条管线：定稿 → CTC 热词救援 → 个人词典 → 标点。不保存日常录音或转写。
  见[接入与安装](docs/桌面R5语音接入与安装-2026-10-25.md)；旧双遍机制保留为[历史参考](docs/archive/安卓双遍语音链路-2026-10-25迁移前.md)。
- **语音常驻与联想共存**：语音未结束时联想照常展开，主键盘右下角麦克风切成半透明环形 ×；
  点一下只收起联想并按原生条件恢复同一轮识别，空格行为不变，见[联想收起](docs/联想收起与语音常驻-2026-10-26.md)。
- **低频嗡声拦截**：解码前拦掉高度重复的周期波形，草稿、定稿与短句补救共用同一门禁，
  抑制短调制嗡声误出「嗯／哦」，见[嗡声修复](docs/低频嗡声误识别修复-2026-10-26.md)。
- **个人词典**：`files/voice-dictionary.json`（与电脑版 `dictionary.json` 同格式）记录「词 / 错法」，
  功能菜单「词典」页编辑，1:1 复刻桌面模块；自动改字学习已删除，拼音候选记忆仍走 librime `entries`。
  见[词典与 CTC 救援](docs/桌面词典与CTC热词救援迁移-2026-10-25.md)。
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
- **表情包同屏搜索**：工具栏笑脸进入，搜索框、结果与自有键盘同屏，停顿300ms 自动查询；无分组、相关标签
  与 Emoji/颜文字分区。图源按用户选择暂留搜狗，微信 App 内同源未确认，
  见[同屏搜索](docs/表情包同屏搜索-2026-10-19.md)。
- **横屏独立适配**：键盘约 258dp、字母区约 696dp 居中、不保留竖屏底栏；旋转后窗口边界、可见高度与
  页面状态恢复，见[横屏适配](docs/横屏适配-2026-10-25.md)。
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
# 需要先有 .venv 与公共 VAD/标点（python tools/setup_voice.py），
# 再用 tools/prepare_desktop_voice.py --source <本机R5目录> 导入固定 R5
pwsh -ExecutionPolicy Bypass -File tools/build_android.ps1
```

脚本会按固定摘要准备 JDK / Gradle / SDK / Build-Tools / sherpa-onnx AAR，然后执行
`testDebugUnitTest assembleDebug`。产物在 `android/app/build/outputs/apk/debug/app-debug.apk`（只打包R5、词表、VAD和标点），
**不会自动安装**。包体与手机占用的实测口径见[体积与内存优化](docs/体积与内存优化-2026-10-19.md)。拼音 JNI 由 gradle 任务 `buildPinyinNative` 调用 `tools/build_pinyin_rime.py` 编译，
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
| 核心与语言 | `core`（连删与摇杆曲线）、`pinyin`、`english` |
| 横屏与表情包 | `landscape`、`meme-search`、`meme-search-ime` |
| 原生桥与语音 | `native-ime`、`voice-orb`、`system-audit`、`ime-regressions` |
| 按键与剪贴板审计 | `functional-audit`（双指交错输入、共享编辑框原生候选、保存失败、长按生命周期） |
| 启动与加载 | `startup`（入口与依赖延迟加载时都不闪出试用页） |

真机脚本（`preview/*-device.test.mjs`）需要先用 `adb forward` 把 IME WebView 的 devtools 端口转到本机，
并且手机操作要由使用者实际完成。

## 结构自检

`node tools/check_module_boundaries.mjs` 一次查完循环依赖、叶子模块越层或反向依赖、单文件体积与孤儿模块；
当前 0 越线。红线与拆分路线见[模块边界与拆分策略](docs/模块边界与拆分策略-2026-10-26.md)。

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
录屏、生成的词库与单文件试用页、日志（完整规则见 `.gitignore`）。例外是
`android/app/src/androidTest/assets/` 下的合成音频夹具（约 360 KB）：隔离 smoke 靠它们复现，
不入库则新克隆跑不起来。首次克隆后需另行准备其余本地依赖。

远端为私有仓库 `https://github.com/duofulang007/VoxFix.git`，主分支 `main`。

## 真实性边界

- 语音不是讯飞引擎移植，全部在本机离线运行；录音只在内存处理，不保存录音或文字日志，不联网识别。
- 拼音是 librime + 固定版本公开词库，词频排序和上下文预测不能等同于成熟商用引擎；不宣称与讯飞一致。
- 手写（含电脑端原型手写）与安卓端本地润色都已移除；历史说明在 `docs/archive/`，删除的代码由Git历史保留。
- 电脑端结果不能证明手机效果；延迟、内存、发热与耗电仍需真机实测。
