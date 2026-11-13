# 截图控件真机测试素材

由 `python tools/make_screenshot_fixtures.py` 生成，内容确定、可重复。

| 文件 | 用途 |
| --- | --- |
| `text-page.png` | OCR 行序/漏行、翻译分段、文字标注折行与逐行背景、字号边界 |
| `color-chart.png` | 调色板与滴管、画笔线宽、高亮、滤镜边界、抗锯齿 |
| `checker-alpha.png` | 透明度、聚光灯、水印、马赛克/模糊边界 |
| `qr-links.png` | 识码、标记显隐、复制、打开链接判定（两个码：URL / 纯文本） |
| `long-list.html` | 长截屏拼接、固定栏、长图重开、裁剪（200 行） |
| `animation.html` | 录屏、暂停恢复、旋转、GIF/APNG/WebP 帧率（50ms 变色 + 计时） |
| `translate-cn.txt` / `translate-en.txt` | 翻译分段、超时、取消、语言互换 |

用法见 `docs/真机控件测试流程-2026-11-09.md` §2。
