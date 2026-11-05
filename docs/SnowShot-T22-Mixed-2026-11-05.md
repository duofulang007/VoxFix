# T22 多选混合样式（Mixed）

本轮沿用 `D:\Projects\VoxFix` 当前工作区和全部未提交成果；用户撤回新聊天切换后，由原聊天单线继续。T22 仍实现中，本记录不表示完整变换、绑定、菜单或全部44项完成。

## 原版依据

固定版本 Snow Shot 1.1.8，提交 `afdc340b14e7f92a294a95c374d13ada0d8c7a82`。先列固定提交完整树，再下载全文，未因旧取证子集缺文件而猜测。

- 引擎 `style.rs`：`style_toolbar_source`、`shape_style_mixed`、`filter_style_mixed`、`serial_number_style_mixed`、`set_filter_style`、`set_text_style`、`serial_number_with_style_properties`；全文在 `.android/snow-t22/reference/style.rs`，原始路径见该目录来源记录。
- `api.rs` 的 `ShapeStylePatch` 和各形状 `supported_properties`：原版以显式属性位修改对应类别，其他字段和类别保持。
- [工具按钮全文](https://raw.githubusercontent.com/mg-chao/snow-apps/afdc340b14e7f92a294a95c374d13ada0d8c7a82/snow_shot/src/presentation/tools/screenshottoolpalettebuttons.cpp)：`drawMixedValueMark` 在预览按钮上画右上斜线；数值显示 `-`。
- [样式控件全文](https://raw.githubusercontent.com/mg-chao/snow-apps/afdc340b14e7f92a294a95c374d13ada0d8c7a82/snow_shot/src/presentation/tools/screenshottoolpalettestylecontrols.cpp)、[样式组件全文](https://raw.githubusercontent.com/mg-chao/snow-apps/afdc340b14e7f92a294a95c374d13ada0d8c7a82/snow_shot/src/presentation/tools/screenshottoolpalettestylecomponents.cpp)、[样式模型全文](https://raw.githubusercontent.com/mg-chao/snow-apps/afdc340b14e7f92a294a95c374d13ada0d8c7a82/snow_shot/src/presentation/tools/screenshottoolpalettestylemodel.cpp)：混合单选组不勾选，序号类型混合时保留可编辑能力。
- 新增全文及 `tree.json` 在 `.android/snow-t22-mixed/reference/`，路径清单 `SOURCES.txt`；均为忽略的本地证据，不提交。

## 本轮实际实现

- 多选不再只显示删除。按原版来源顺序解析：滤镜优先；文字/序号参考主对象；其后聚光灯、形状、矩形高亮、画笔高亮、线、画笔、箭头。主对象身份与样式栏来源分开，避免影响文字编辑等既有操作。
- 形状/线/画笔/箭头/高亮/文字/序号只修改来源类别；高亮矩形与画笔分开；滤镜两种区域共享滤镜类型/强度，矩形滤镜不改隐藏的画笔宽度。聚光灯沿用原版全画布遮罩配置语义。
- 所有现有样式控件传显式属性集，点击与当前代表对象相同的值也会统一所选类别对应属性。颜色、线宽、填充、字体、箭头参数等不会复制代表对象的整套样式给其他对象。
- 预览只画临时结果，不入历史；调色板的颜色与线型修改累积到同一提交；取消不留改动；批量提交一次撤销。稳定 ID、几何、旋转、关联关系和未触及字段保留。
- 预览按钮显示原版斜线标记；数值显示 `-`；预设/单选项在混合状态不高亮某个代表值；无障碍状态标为 `Mixed`。保留多选删除入口，截图主窗口和贴图编辑器共用该路径。
- 序号无数字圆形保留隐藏的编号/字体；类型混合时仍可改编号/字体，操作只作用于支持编号的对象。
- 改已缩放文字的颜色不再重置 `fontScale`；改字号按对象缩放折算原始字号，保持未改的描边和背景几何。工程读取接受有效的折算字号，校验缩放为有限正数，避免重开时字号被错误夹回6。

## 手机侧差异与尚未完成

**原版无此行为，以下是手机侧实现**：填充由 `fillColor + hasFill` 表达；关闭填充只改开关，保留各对象隐藏填充色。现有手机加粗及旧工程序号几何版本是既有扩展，本轮保持兼容，不宣称原版具有相同字段。

本项目既有约定要求“修改选中对象不污染新建工具默认参数”。原版引擎部分样式路径会同步默认值；手机侧明确保留项目约定，选择态不写新建工具记忆。区域类型在有选中对象时不提供切换，避免把原版独立对象类别当作样式批量互转；无选中对象的新建工具仍保留既有区域选择。

本轮只把 Mixed 接到**现有**控件；未借此补齐所有原版缺失属性/工具。T16 文字排版测量、完整原版样式栏几何、缩放后描边/背景数值的物理单位统一仍需各自取证；不把当前原始数值控件当作原版物理尺寸已一致。原版同尺度叠图、真人触摸与跨窗口流程尚未验收。

T22 仍缺八向/整组缩放/翻转、精确选框、端点新增与段把手、弯折路由/绑定/吸附、完整对象菜单。后续顺序继续 T22 → A07 → T26/T27/T28 → 箭头逐档图标 → 全部已接项叠图与真人走查。

## 验证

- JVM 197 项，零失败；主包及测试包构建成功。最终主包构建日志 `build-complete.log`；最后仅修正测试重做后重选步骤，测试包日志 `build-test-selection.log`，未再改业务源码。
- K60 `screenshot-selection-style` 专项 PASS：类别范围、混合标记、同值显式属性、其他差异保留、单步撤销、预览取消/属性累积、默认值保护、缩放字体/重开、序号兼容、真实控件和多选删除入口。日志 `selection-style-verified.log`。
- 同一最终主包的10组受影响回归全部 PASS：history、editing、document、project、selected、refinement、parity-foundation、color-picker、pen-feedback、memory。每组后重绑服务，日志 `regression-*.log`、`rebind-*.log`。
- 专项首次发现旧多选提前返回仍阻断控件，已修正；后两次失败是检查步骤在撤销/重做清空选择后未重新框选，已修正测试并复验，不把这些失败日志当成通过证据。
- `controls.png` 为设备生成的固定序号控件图，已查看：类型/字体混合不误勾选、编号显示 `-`、字体显示 `Mixed`。不是用户截图、不是原版叠图或真人操作证据。
- 最终本地与手机 APK SHA256 一致：`ce3489a64e3a62a22357e55f16d52714cb05686c4eb724d165095eae147c49a5`。
- 四项用户数据仅在设备端算摘要，本轮安装/测试前后一致：屏幕设置 `1021e1b3…`、样式记忆 `1f9d1ebe…`、图片索引 `aa96ca9c…`、词典 `879c99e8…`。本轮实时基线与上一轮样式记忆摘要不同，不以历史摘要覆盖用户最新值，不读写完整用户内容。完整摘要见 `before-data.sha256`、`after-data.sha256`。
- 收尾默认输入法仍是讯飞小米版 `com.iflytek.inputmethod.miui/.FlyIME`；李跳跳/小米截图服务保留，VoxFix 服务重绑且 `Crashed services:{}`。没有切换输入法、清应用数据或提交推送。

证据目录 `.android/snow-t22-mixed/`。未改 `preview/`，分发 HTML 未变，无需无关网页回归或重新打包。未跑语音/模型等无关套件，未做原版同尺度叠图和真人手感验收。
