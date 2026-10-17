# Offline punctuation model

CT-Transformer 中英标点恢复模型，用于 `SpeechEngine` 的最终输出补标点。
Apache-2.0 license is preserved in LICENSE.

- 模型：`sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8`
- 上游：https://github.com/k2-fsa/sherpa-onnx
- 下载：https://github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2
- 压缩包：64,717,756 字节，SHA-256 `c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1`
- 包内模型：`model.int8.onnx` 75,519,198 字节，SHA-256 `65a3fb9f5ad7bfb96bf69e0dc4481df97f6ee60513c1d94ce981ba6effd524b1`
- 词表与标点符号集内嵌在 onnx 元数据里，不需要额外 `tokens.txt`。

## 为什么需要它

仓库内 SenseVoice int8 权重实测不产标点：固定音频在 `language=zh/auto` ×
`useInverseTextNormalization=true/false` 四种组合下输出完全一致且没有任何标点，疑问句亦然；
`models/sensevoice/tokens.txt` 里虽然有「，。？」token，但推理结果里从不出现。
本模型的 75,519,198 字节与项目早期调研记录的 CT-Transformer 标点模型一致。

## 行为边界（实测于桌面 1.13.6）

- 纯中文与中英混合：断句准确，`帮我把…整理成文档然后发到群里让大家确认一下` →
  两个逗号位置正确；单句 1–8 ms。
- 纯英文：会给中文逗号（`HELLO WORLD，HOW ARE YOU？`），因此代码只对含汉字的文本调用本模型，
  纯英文仍走原有句末规则。
- 已有标点：会叠标点（`你好，请问…？` → `你好，，请问…？？`），调用前先剥句读类标点。
- 阿拉伯数字与引号：会插空格或错逗号（`120.5` → `120 . 5`），因此标点必须发生在
  `formatNumbers` 之前，且文本含引号括号时不交给模型。
