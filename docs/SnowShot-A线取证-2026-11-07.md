# Snow Shot A 线取证（2026-11-07，第二十四轮）

目的：把[平台差异清单](SnowShot手机平台差异清单-2026-11-07.md)§5 里 A 线剩余的 13 个条目，按固定提交
`afdc340b14e7f92a294a95c374d13ada0d8c7a82` 逐条取证，结论只分四类：**已对齐**、**已改**、
**手机侧扩展（原版无对应行为，不参与 1:1）**、**差异未做**。取证源码树用
`.android/snow-apps-afdc340b14e7f92a294a95c374d13ada0d8c7a82/`（完整树，比 `reference/` 子集全）。

本轮实际改动五项：**T19 补齐结果栏 Formatting／Punctuation**、**T18 打开链接的判定按原版**、
**T24 贴图 Scale 菜单 + 旋转/翻转**、**A09 二维码标记层 + 每标记弹层**。

## 1. T19 识别文字：结果栏 Formatting／Punctuation（已改）

### 1.1 上游事实

| 事实 | 出处 |
| --- | --- |
| 结果栏顺序：Eye（第 0 位）→ Edit → Text translation → Jump → **Formatting** → **Punctuation** → Reset → Translation settings | `screenshottoolpalette.cpp:7380-7510` |
| Formatting 选项 `keep`＝Keep line breaks、`remove`＝Remove line breaks；Punctuation 选项 `half`＝Half-width、`full`＝Full-width；两者都可清空 | `:7467-7494` |
| 可见性只跟 OCR 内容：`setVisible(ocrVisible)`；可用性 `available && !translating`（编辑态用 `!editingLocked`） | `:1425-1429`、`:2472-2477`、`:2511-2515` |
| 改下拉会**先进入编辑**：`if (!m_editing && !m_translating) beginTextEditing();` | `screenshotrecognitionsessioncontroller.cpp:1139-1170` |
| 变换是**从基线重算**，不是叠加；两个都清空即丢基线；非法值规范化为空 | `screenshotocrtexteditingsession.cpp:44-110` |
| 手动改字、撤销、重做都 `clearTransforms()`，下拉回到空值 | 同上 `:126-160` |
| 复制时非编辑态套的是**设置里的默认值**；默认两个都是空串 ⇒ 恒等 | `screenshotrecognitionsessioncontroller.cpp:1313-1318` |
| 变换实现：`remove` 删所有 `\n`；标点按 32 个 ASCII 符号与全角一一映射互转 | `screenshotocrtexttransform.cpp`（全文已读） |

### 1.2 手机实现

- 新 `ScreenshotTextTransforms.kt`：`removeLineBreaks`／`convertPunctuation`／`apply` 逐行移植；
  另含 `ScreenshotTextTransformState`（基线、重算、清空、非法值归一）。
- `ScreenshotTextLayer` 持有该状态：`setFormatting`／`setPunctuation` 按需建整段编辑器，
  替换草稿记为**一条历史**；用户改字（两个 `TextWatcher`）、撤销、重做都清空状态。
- 结果栏新增两个文字按钮（`格式`／`标点`，选中后显示 `保留换行`／`移除换行`、`半角`／`全角`），
  点按弹菜单，已选值时多一项「恢复默认」（对应原版 `setAllowClear`）。位置在 Edit 之后，
  顺序与原版一致；结果栏改由横向滚动容器承载，窄屏仍可到达全部入口。
- 应用前若不在编辑态，先 `setEditing(true, document=true)`，与上游 `beginTextEditing()` 同序。

### 1.3 差异

- **原版的变换只作用于文字草稿**（复制／另存文本），退出编辑后 `endTextEditing` 重新
  `applyPresentation(entry.presentation)`，图上仍显示识别原文（`screenshotrecognitionsessioncontroller.cpp:846-870`）。
  手机的结果层本身就是可编辑结果层，变换会同时改变结果层与其导出像素——**属手机侧既有模型，
  不是原版行为**，已登记在 §7。
- 原版下拉是带占位文案的 `AdSelect`；手机用同尺寸文字按钮 + 弹出菜单表达同一组选项（无下拉控件）。

### 1.4 验收

- JVM **454 项 0 失败**（+8 `ScreenshotTextTransformsTest`，+4 `ScreenshotQrLinksTest`）。
- 设备 `screenshot-toolbar` PASS，新增 `transform=ok`：两个入口在结果栏内、初始 `格式/标点`、
  `REMOVE` 后文本无换行且标签变 `移除换行`、`KEEP` 从基线恢复换行、清空回到 `格式` 且文本复原。
- 复跑 `screenshot-editing`／`screenshot-parity-foundation`／`screenshot-refinement`／
  `screenshot-overlay-suites` 全 PASS；装机主包 `ff9e58b0348d100a7c6b5e31f3662ec9630e914c062f61f1861755bf7c27b8e8`；
  用户数据摘要装前装后一致（排除套件残留），默认输入法仍 VoxFix，`Crashed services` 为空。
  证据 `.android/t19-20261107/`。

## 2. T18 识码：打开链接的判定按原版（已改）

- 上游 `ScreenshotQrController::webUrl`（`screenshotqrcontroller.cpp:290-303`）：去首尾空白后
  **整段必须是一个 URL**；任何空白或控制字符直接判否；必须绝对地址、host 非空、scheme 只认 http／https。
  不自动跳转，只有弹层里的「Open URL」按钮才跳。
- 手机原判定只看 scheme（`Uri.parse(x).scheme in {http,https}`），`https://` 这类空 host 也会给「打开」。
  已改 `ScreenshotQrLinks.webUrl()` 按上游四条规则实现，识码结果只在**唯一一条**时给打开入口。
- **差异**：原版复制/打开后 `closeEditor` 会把整个截图收掉，手机只收起弹层（避免误关；原版这点在手机上等于"复制一条码就退出截图"）。
  自动识别已对齐：默认 `auto_recognize_qr_code = true`，进入编辑态即识别、标记默认可见（`m_visible = true`），手机在进入「编辑选区」时后台识别并直接显示标记。
- 验收：`ScreenshotQrLinksTest` 4 项（纯 URL、带空白的句子、非 http(s)、空 host）；
  设备 `screenshot-selection-bar` 断言（`parity-foundation` 内）：标记中心＝识别框质心、
  选区外丢弃、命中半径 12dp、开关标签随可见性切换、关闭后标记消失。

## 3. T17 保存：取证（未改代码）

| 项 | 上游 | 手机 |
| --- | --- | --- |
| 入口 | 两个动作：`Quick save`（创建后 `hide()`，默认不进主栏，靠工具栏配置加入）与 `Save as file`（默认可见） | 单一「保存」入口 |
| 落点 | `imageSaveDirectory()` + `autoSaveFilenameFormat()`；Save as file 走文件对话框（可用 Snow 自带对话框） | 应用私有截图剪贴板（工程 + 每次保存一个修订） |
| 有识别文本时 | **保存的是识别文本文件**（`fileExportSnapshot()` 存在则走 `saveRecognitionTextWithSystemDialog`） | 始终保存图片/工程 |
| 格式 | Png／Jpeg／Webp／Jxl／Avif／Bmp／Pdf 七种 | Png |
| 保存后 | `detachCaptureForExport()` 取消截图窗口，历史记 `SavedToFile` | 关闭会话并提示「已保存到图片剪贴板」，不复制 |
| 复制 | 保存不写剪贴板 | 保存不写剪贴板（一致） |

结论：**「保存不复制」与原版一致**；落点、格式、快速保存入口与「有文本时存文本」属文件系统能力，
手机没有等价界面，记手机侧差异。工程与修订（新建／更新／重开／失败／旧图／冲突）已有
`ScreenshotProjectSmoke` 覆盖，本轮未改。

## 4. T20 编辑识别文字：取证（未改代码）

- 上游：Edit 是**带选中态的同一按钮**（`setScreenshotToolPaletteButtonActive(m_textEditButton, editing)`，
  标签始终 Edit），进入编辑时清掉 OCR 展示层并换成整段编辑器；退出时恢复识别展示；
  Reset（`textResetRequested`）清变换并 `replaceText(originalText)`；首次进入编辑会套
  `applyInitialTransforms(设置默认值)`，默认为空。
- 手机：Edit 标签切换为「完成编辑」，编辑结果**写回结果层**（保存/导出含编辑后的文字）。
  这是与上游「只改草稿」的模型差异，已登记 §7，本轮不改（改动会动到工程、翻译与导出三层）。
- 其余（跨行选择、输入法遮挡、撤销重做、完成、保存重开）已有设备断言，见 `screenshot-editing`
  与 `edit=document` 子项。

## 5. T24 贴图、A08、A09、A13、A14–A16、T05

| 条目 | 取证要点 | 结论 |
| --- | --- | --- |
| T24 贴图 | 上游 `ScreenshotPinnedWindow`：缩放百分比 = 当前窗口宽 ÷ 初始窗口宽 ×100，边界 **10–500%**（`screenshotpinnedwindow.cpp:341-342`、`applyScale` 5584）；透明度 **25–100%**（`:344-345`），菜单为 ±10% + 25/50/75/100 预设 + 当前值；另有**顺时针/逆时针旋转、水平/垂直翻转、重置变换**（`applyImageOperation` 5471，90° 换向并保持缩放比例）、阴影、另存（`saveAsFile`）、快速保存、复制 | 手机已有置顶、等比缩放、透明度滑杆（25–100，与上游同域）、二次编辑、关闭；**本轮补上 Scale 菜单（±10% 与 25/50/75/100 预设，10–500 收口）与旋转/翻转/重置**（`ScreenshotPins.applyTransform`：以未变换裁切为源、90° 换向、窗口按同一比例换向）。**编辑仍走原始截图纸，编辑结果会把贴图换回未旋转朝向**（与上游一致，变换不写入工程）。**未做**：阴影与右键菜单其余项；手机窗口不能超出屏幕，100% 以上实际受屏幕宽限制（平台限制） |
| A08 重新截图 | `startRecapture` 只在 **Editing + move 工具激活** 时可用；先隐藏自身窗口再异步截屏，失败回滚窗口并报错（`screenshotcaptureworkflow.cpp:112-140`、`screenshotcontroller.cpp:2060-2100`） | 手机入口同样只在「编辑选区」态出现；`checkRecapture` 已断言：失败时窗口恢复、选区与对象不变、提示原因；尺寸不符时 `Session.replaceFrame` 拒绝并保留旧图；成功换图后选区/历史不变、已编辑文字进「上次文字草稿」。余系统级拒绝（如 `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` 的端到端路径）需真机触发 |
| A09 选区栏识码 | 上游按钮是**标记显隐开关**：`setQrCodeState(available, visible, error)`，点按只 `setMarkersVisible`；标记 24 逻辑像素圆、中心＝识别框四角质心、只在「编辑态 + 移动工具 + 中心在选区内」显示；`auto_recognize_qr_code` 默认 true，进入编辑态自动识别、`m_visible` 默认 true | **已按上游实现**：进入「编辑选区」即时后台识别并显示标记，选区栏按钮为显隐开关（标签随状态切），标记层 `ScreenshotQrMarkers`（24dp 圆、2dp 描边、命中 12dp、质心定位、选区外丢弃），点按弹层含内容/复制/打开；主栏识码保留合并结果对话框（手机侧便利入口）。余差异：原版复制/打开后收起整个截图，手机只收弹层 |
| A13 调整文字位置 | 原版没有独立「调整文字位置」入口（文字块位置在对象选择/编辑里改） | **手机侧扩展**，不参与 1:1，不再排期 |
| A14 设置 | 原版主栏没有设置按钮，截图设置走应用设置页 | **手机侧入口**（输入法菜单→截图设置），不参与 1:1 |
| A15 重置 | 同上，属于手机布局页能力 | **手机侧扩展**，不参与 1:1 |
| A16 拖动柄 | 原版主栏有 `m_mainPanel->addTrailingDragHandle()`（`screenshottoolpalette.cpp:4030-4032`），从尾部拖整条工具栏 | 手机已接，行为一致；差异仅在触摸热区 |
| T05 选择整屏 | 原版没有「选择整屏」入口 | **手机侧扩展**，不参与 1:1 |

## 6. 条目去向（三类，别混）

**① 平台不做（D01–D16）**：平台无对应能力／系统层做不到／桌面输入专属，见[平台差异清单](SnowShot手机平台差异清单-2026-11-07.md)。

**② 手机侧必需入口：保留，不计入 1:1 对位**（复刻只增不减，2026-11-07 用户澄清）。
原版靠**快捷键／右键／悬停**完成的能力，手机没有物理键盘鼠标，只能用可见入口替代；
这类入口**必须保留**，只是不拿它去和原版的某个按钮对位：

| 入口 | 原版做法 | 手机为何必须有 |
| --- | --- | --- |
| T03 复制文字 | 快捷键 `copy_text`／`copy_text_and_end_screenshot` | 手机无全局快捷键，没有按钮就复制不了 |
| T04 分享 | 系统分享菜单（桌面「复制到剪贴板」之外的路径） | 手机的主要外发方式 |
| T05 选择整屏 | 无（桌面拖选整屏） | 手指很难精确拖到整屏边界 |
| A10／A11 读数点按切 px/dp | 两个独立按钮 | 手机窄屏只留一个读数入口，点按切换 |
| A13 调整文字位置 | 无独立入口 | 手指拖动比改对象属性直接 |
| A14 设置／A15 重置 | 应用设置页 | 手机输入法菜单内的入口 |
| A16 拖动柄 | `addTrailingDragHandle()` | 与原文一致，保留 |
| T28 授权步骤、录制前设置阶段 | 无（桌面直接录） | Android 必须先申请 MediaProjection |

**③ 差异未做／待做**：A09 标记模型、T18 标记弹层、T24 旋转与阴影、T19／T20 的草稿模型。
这些是"还没做"，不是"决定不做"。

## 7. 仍未闭环

1. **T18／A09 的收尾差异**：原版复制/打开后收起整个截图（`closeEditor`），手机只收弹层。
2. **T24** 的右键菜单其余项未做；100% 以上受屏幕宽限制（平台限制）。旋转/翻转已接，编辑后朝向回到未旋转（与上游同）。
3. T19／T20 的「草稿 vs 结果层」按**手机侧既定行为**保留（编辑直接改结果层与导出像素，用户 2026-11-07 明确"别把操作搞没"）；如要改成原版的"只改草稿"，需单独确认，会动到工程、翻译与导出三层。
4. A08 的系统级拒绝路径（安全窗口／服务失效）需真机触发。
5. 原版同尺度叠图（B 线）与真人走查（C 线）仍缺。

## 8. 选区浮动工具栏：按用户口径判定不做（2026-11-07）

取证发现上游另有**选区浮动工具栏**（`screenshotselectiontoolbarwidget.cpp`）：X／Y／W／H 数值字段（滚轮步进）、圆角 0–256（`screenshotselectionlimits.h:5`）、阴影 0–64（导出扩边，`screenshotgeometry.cpp:47`、`screenshotexportservice.cpp:80`，合成用九宫格阴影资产 `screenshotselectionshadowrenderer.cpp`）、比例锁定（`kNewMarqueeAspectRatio = 1.0`）。手机只有 W×H 读数（A10／A11）与隐藏（A12）。

**用户判定：不做。** 这些是桌面精度/装饰控件，手机直接拖框即可完成同类操作；四个入口（圆角／位置／方形／阴影）实现后已整体回退，并新增红线「只做能实现且必要的」。**不再排期，也不再作为差异登记。**
