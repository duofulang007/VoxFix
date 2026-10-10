# ResoJot 流式方案调研

核实日期：2026-10-10。范围仅为官方 README、LICENSE、仓库元数据和公开入口；不安装插件，不分析混淆发布产物，不进行手机推理测试。

## 固定版本与许可

- 仓库：[jiaoyingxing/resojot](https://github.com/jiaoyingxing/resojot)。本次固定提交：`264edf687d9b523baa70c7d1df5330fefc3814a6`，通过官方 GitHub commits API 读取。
- [固定提交 manifest](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/manifest.json)版本 `0.9.21`，最低Obsidian `1.11.4`，`isDesktopOnly=false`。
- [固定提交 README](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/README.md)明确说明闭源分发、源码不公开，`main.js` 是压缩及轻量混淆后的发布产物。
- [固定提交 LICENSE](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/LICENSE)为 Jiao Yingxing Obsidian Plugin Proprietary License v1.0，更新时间2026-05-22。第2条不授予源码许可，第3.2条限制逆向及从发布产物推导设计，第3.3条限制衍生和并入其他项目。因此不能按成熟开源库直接复制其内部实现。
- 官方目录接口显示 `source/src/` 只有 `main.ts`、`settings.ts` 两个小文件；读取[公开 main.ts](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/source/src/main.ts)可见它仅加载设置、注册图标和Notice命令，不包含语音、ASR或润色实现。生产发布程序为根目录 `main.js`。公开目录的存在不等于完整实现和开源许可。本次不对 `main.js` 做逆向，也不下载整个仓库。

## 可以核实与不能核实的行为

以下均来自固定提交 README 的“核心功能”“支持服务”“平台与版本”“授权与隐私”各节。

|问题|官方可核实事实|边界|
|---|---|---|
|识别是否边说边出字|“录音并实时转写”功能在录音过程中逐句出字，停止后保存音频并继续后续处理|属于需要授权的功能；未说明逐token还是服务端逐句回包|
|何时开始润色|文档说明自动润色在转写完成后进行|不能据此认定录音中已开始分段润色|
|润色是否token流式|公开功能说明没有交代生成增量、SSE/token回调或刷新节奏|无法验证，不能宣称该项目已实现流式润色|
|客户端怎样替换文本|录音停止后保存音频和Markdown笔记，转写、润色、待办、提要分区写入；也支持手动润色|编辑范围锚定、临时文本、最终替换和冲突处理内部机制无法验证|
|推理运行在哪|ASR可连接本地OpenAI兼容服务或各类云服务；Windows可管理FunASR；润色可连接Ollama或云端及自定义兼容接口|移动端插件可用，不代表Android手机内直接运行这些模型；无手机本地GGUF推理证据|
|能否直接搬到Android|这是Obsidian插件，常规功能支持Android版Obsidian；Windows听写和FunASR管理是桌面能力|不能把其桌面听写或Obsidian能力记成Android原生IME能力|

结论：它可以作为“录音中及时呈现识别文本、结束后继续处理”的产品体验参考，但没有足够公开证据把它作为流式润色实现基线。拒绝将逐句ASR出字等同于LLM流式润色。

## VoxFix 最小复用建议

当前项目已有 `SpeechEngine.kt` 的 sherpa 流式识别、`MainActivity.kt` 的文本显示、`polish.cpp` 的 llama.cpp 逐token生成循环。用户本轮明确恢复对输出体验的优化要求；此前暂缓整体优化的记录应保留为历史，不应阻止此次明确要求。

1. 保留现有ASR流程，不重写识别引擎；先接通已有 llama.cpp 生成循环的增量回调，经JNI送给Kotlin，复用现有文字区域逐步呈现。
2. 中间输出是尚未完成原文保护校验的草稿。完整结果仍交给现有数字、否定、专名保护；失败或取消回退原文，不把未校验草稿当作最终跨应用输入。
3. 本轮若只实现“停止录音后增量显示润色”，应准确标注这个范围：它减少等待期间的空白，但首token前仍有加载和提示词处理，不等于边说边润色，也不保证总耗时下降。
4. 录音中分段润色还涉及稳定ASR段、上下文、过期结果取消和手机ASR/LLM并发资源竞争；不能为追求表面流畅直接每次临时识别都重新跑模型。该能力需要单独完成实现及K60实际验证，不从本次调研推断通过。

不新增推理框架，不复制ResoJot专有代码，不引入云端依赖，不保存日常录音训练，不扩大到IME重写。

## 官方来源

- [提交元数据](https://api.github.com/repos/jiaoyingxing/resojot/commits/264edf687d9b523baa70c7d1df5330fefc3814a6)
- [固定提交目录](https://api.github.com/repos/jiaoyingxing/resojot/git/trees/264edf687d9b523baa70c7d1df5330fefc3814a6?recursive=1)
- [固定提交 README](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/README.md)
- [固定提交 LICENSE](https://github.com/jiaoyingxing/resojot/blob/264edf687d9b523baa70c7d1df5330fefc3814a6/LICENSE)

尚未验证ResoJot运行时的token流式润色、其内部编辑替换策略、其Android版实际延迟；本次未重复手机模型测试。
