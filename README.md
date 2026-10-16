# VoxFix 安卓输入法

VoxFix 是一个安装后可在系统里独立选择的自有 Android 输入法（`local.voxfix.debug`），复刻讯飞小米版的界面与交互，
并接入本机离线语音。它不是对讯飞输入法的功能注入；手机上原有的两个讯飞包保持不动。

**开发请看 [docs/开发入口.md](docs/开发入口.md)**，那里是唯一的当前状态入口。本页只做仓库说明。

## 现在是什么状态

- 独立 ARM64 原生 Debug 输入法，Android 15 / API 35，已在 Redmi K60 上安装并选为当前输入法。
- 键盘前端就是 `preview/` 的原型（构建时按清单同步到 assets，由原生 WebView 承载）：
  26 键中英文、大小写、数字与符号、剪贴板、常用语、表情、键盘选择、夜间模式；
  键盘高度／字号／间距／圆角／背景按官方截图固定，不提供尺寸自定义。
- **中文拼音走原生 librime 1.17.0**：`PinyinEngine.kt` + `cpp/rime_jni.cpp` → `libvoxfix_rime.so`，
  词库为 luna_pinyin 加个人词条，并保留 speller `algebra` 简拼修复。
- **语音走 sherpa-onnx 1.13.8**：Zipformer 中英流式（第一遍，边说边进输入框）+ SenseVoice 第二遍
  （松手后整段补标点与数字格式）+ silero VAD 断句。长按空格 300ms 进入语音，上滑取消、松手结束。
- 底部滑轨：碰一下即可拖动光标，离触控点越远走得越快（跟手 / 高速两档）；长按在「起点锁定」与「自由移动」
  之间切换，锁定时把光标钉成起点并呼出右侧编辑框，收起面板不等于解锁。
- **剪贴板**：长按条目可拆词（离线词频切分，点词选中并临时上屏、再点撤回，返回只收起面板、保留已选文字）；
  候选栏第二个候选位可预览并直接引用剪贴板内容；输入时可检索全部已保存历史（最多 300 条，支持字面／全拼／
  简拼／混拼与受控近似），点数量展开与第二候选等宽连接的窄浮层。候选引用与剪贴板编辑共用一个
  「取消 / 拆词 / 保存」编辑框。
- **拼音个性化学习**：原生成功选词同步到本地记忆，补齐分段短语、全拼／简拼／混拼共享召回与接续联想；
  可关闭，不保存日常上屏文本，不联网。
- **手写已停用**：源文件在 `android/disabled-handwriting/`，不参与编译，模型与运行库不打包。
- **安卓端 Qwen 本地整理已移除**：手机上不再保留 GGUF 模型，输入法不阻塞上屏、不调用大模型。

验收记录在 [docs/USB真机验收.md](docs/USB真机验收.md)，未解决项在
[docs/现有功能问题清单.md](docs/现有功能问题清单.md)。

## 目录结构

| 目录 | 作用 |
|---|---|
| `android/` | 原生输入法工程：Kotlin 源码、librime JNI、拼音 schema、许可与测试 |
| `android/disabled-handwriting/` | 已停用的手写实现与测试，仅供日后参考，不参与编译 |
| `preview/` | 键盘前端源码（同时是浏览器原型）、交互测试、官方参考截图 |
| `voice/` | 电脑端离线语音与整理服务，仅用于桌面原型 |
| `tools/` | 构建、模型与词库准备、APK 检查等脚本 |
| `docs/` | 当前文档；历史材料在 `docs/archive/`（索引见其 README） |

## 构建安卓包

```powershell
# 需要先有 .venv（Python）与 models/（语音模型，tools/setup_voice.py 准备）
powershell -ExecutionPolicy Bypass -File tools/build_android.ps1
```

脚本会按固定摘要准备 JDK / Gradle / SDK / Build-Tools / sherpa-onnx AAR，然后执行
`testDebugUnitTest assembleDebug`。产物在 `android/app/build/outputs/apk/debug/app-debug.apk`，
**不会自动安装**。拼音 JNI 由 gradle 任务 `buildPinyinNative` 调用 `tools/build_pinyin_rime.py` 编译，
前提是 `.android/pinyin-rime/` 已由 `tools/setup_pinyin_rime.py` + `tools/prepare_personal_pinyin.py` 准备好。

> 个人词库与英文词表必须由 `stagePinyin` 打包，再由 `preparePersonalData()` 落到 `filesDir/pinyin-personal/`。
> 缺失或 metadata 非法时 schema 编译会失败，前端会静默回退，而不是报错。

## 网页交互检查

网页检查是改动后的第一道回归，**不等于原生通过**。

```bash
node preview/native-ime.test.mjs      # 以及 ime-regressions / candidates / fixed-layout / key-press /
                                      # focus-ring / interaction-transitions / pinyin / english / learning /
                                      # split / clipboard-* / selection-* / letter-hold / voice* / core
                                      # 各自同名 *.test.mjs
```

真机脚本（`preview/*-device.test.mjs`）需要先用 `adb forward` 把 IME WebView 的 devtools 端口转到本机，
并且手机操作要由使用者实际完成。

## 电脑端原型（非当前主线）

`preview/` + `voice/` 保留了一套完整的电脑网页试用版：真实录音、流式出字、本地整理。
它曾是主要开发方式，现在只用于网页侧试验，**不代表手机原生能力**。

- `voice/server.py`：本机语音服务，端口 `127.0.0.1:18743`，只监听本机。手机上的 `127.0.0.1` 访问不到它。
- 模型准备：`.venv\Scripts\python.exe tools\setup_voice.py`（语音）。
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
- 手写（含电脑端原型手写）与安卓端本地润色都已移除，相关代码只作为历史保留在 `docs/archive/` 与 `android/disabled-handwriting/`。
- 电脑端结果不能证明手机效果；延迟、内存、发热与耗电仍需真机实测。
