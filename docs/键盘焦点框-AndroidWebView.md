# 键盘焦点框 · Android WebView 自己把 DOM 焦点丢给工具栏第一个按钮

2026-10-13。用户报：底部滑条（光标轨）往左滑之后，键盘工具栏上会冒出一个蓝框，看着像「菜单栏被选中」了。

## 现象与量化

用户截图只裁了键盘（426×426；键盘实际 411 CSS 宽、3.5 DPR，换算系数 ≈1.036）。量这个框：

- bbox ≈ x 0..73 CSS、y 0..50 CSS，**框内像素与键盘底色 `--kb-bg #edeef2` 完全一致（没有填充）**；
- 边框最深像素 ≈ `rgb(82,125,160)`，粗约 2 CSS px；左边缘被屏幕左边裁掉（框比键盘内边距还靠外）。
- 尺寸正好等于工具栏第一个按钮（`flex:1` 均分后约 63px 宽、40px 高）加上 `outline-offset:3px` 和 2px 描边 —— 就是 `preview/style.css` 里的全局 `button:focus-visible{outline:2px solid #4385b8;outline-offset:3px}`。

## 现场证据（CDP 直连 IME WebView）

`adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`，playwright `connectOverCDP` 读页面：

- 触发后 `document.activeElement` = `BUTTON.toolbar-key`（`data-action=menu`，工具栏第一个），该节点匹配 `:focus-visible`，`document.hasFocus()` 为 true，`outline-style` 为 `solid`、颜色 `rgb(67,133,184)`。
- **不是页面调的焦点**：把 `HTMLElement.prototype.focus`、`focusin/focusout`、`#keyboard` 的 childList 全打上日志后复现，日志里只有一条 `init` —— 焦点是 WebView 自己给的。
- **不是页面重载**：`window.__marker` 与 `performance.now()` 触发前后连续（≈1000s），DOM 是同一份。
- 复现步骤：`input keyevent 3`（回桌面）→ `am start -n local.voxfix.debug/.MainActivity` → 点试用框让输入法重新附着 → 焦点落到工具栏第一个按钮。**属于竞态**：同样三步第二次跑空，与用户「有时滑动才出现」的观感一致。

## 根因

Android WebView 在**窗口焦点失而复得**而文档里没有焦点元素时（回桌面再回来、输入法重新附着、宿主重启输入），会自行把 DOM 焦点交给文档里第一个可聚焦元素；键盘里它就是工具栏第一个按钮（menu）。浏览器随即按 `:focus-visible` 画出 2px 外框。

与已知的 `:active` 问题同源：**由 WebView 代管的伪类在这套壳里不可控**（见 `docs/按键按压反馈-官方对照.md`）。触摸键盘没有「键盘焦点」这一说。

## 修复

`preview/style.css` 一行：

```css
#keyboard :focus-visible,#ime-local-editor :focus-visible{outline:none}
```

键盘内一律不画浏览器焦点框；键盘外的设置区不受影响（焦点框保留）。`键盘试用页.html` 已重新打包。

## 验证

- 新增 `preview/focus-ring.test.mjs`：CDP `CSS.forcePseudoState` 把 `:focus-visible` 钉在指定节点上再读计算样式 —— 键盘内（工具栏按钮／字母键／功能键／滑轨／候选条）必须是 `none`；同一条测试里断言键盘外 `.control-tabs button` 仍是 `solid`，证明伪类确实被钉住、断言不是空转；另加「程序化 focus 工具栏第一个按钮后仍无轮廓」的真实路径。
- **反证**：临时删掉该规则，测试立刻 `actual:'solid'` 失败；恢复后 PASS。
- 网页回归 15 个测试（core / key-press / fixed-layout / interaction-transitions / selection-direction / candidates / pinyin / english / voice / workflow / native-ime / ime-regressions / selection-drag / focus-ring 等）全过。
- **真机（K60，新 APK `install -r -t` 之后）**：
  - CDP 把 `:focus-visible` 钉在 `#keyboard .toolbar button` 上：`matches(':focus-visible')` 为 true 而 `outline-style` 为 `none`（修复前同一状态是 `solid 2px rgb(67,133,184)`）；对照 `.control-tabs button` 仍是 `solid` 2px。
  - **页内截帧**（`Page.captureScreenshot`，411×350，页面自身像素，与输入法窗口是否在屏上无关）：同一会话里先注入对冲规则模拟修复前，同一状态下工具栏前 60 行蓝像素 **494**；移除后 **0**。注入的 style 已当场删除。
  - 对照图：`.android/focus-ring-device-ab.png`（上＝模拟修复前，下＝修复后），`.android/focus-ring-ab.png`（浏览器同尺度对照）。

## 边界

2026-10-16 补充：分组名称输入框位于独立的 `#ime-local-editor` 中，原先未被键盘内规则覆盖，程序聚焦时出现蓝色外框。规则现覆盖该辅助编辑区，保留原有浅灰边框及输入焦点。焦点框检查、18项交互回归和APK构建通过，包内样式与源码一致；用户随后要求安装，已保留数据覆盖安装，37个备份文件前后逐字节一致；未注入触摸或打字，真人效果待验。渲染图在忽略目录 `.android/group-editor-no-outline.png`。

只封「可见外框」。DOM 焦点本身仍可能落在工具栏第一个按钮上 —— 不影响输入（键盘的按压/选中态只由 `.pressed`/`.selected` 表达，没有任何 `:focus` 样式），所以没有加 blur 守卫，避免与「长按剪贴板后给 sheet 按钮对焦」「滑轨/候选条用 tabindex 支持方向键」这些真实对焦互相干扰。
