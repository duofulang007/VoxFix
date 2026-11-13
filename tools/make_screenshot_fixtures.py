#!/usr/bin/env python3
"""Generate deterministic fixtures for the on-device screenshot control tests.

Outputs to testdata/screenshot/:
  text-page.png      中文正文 + 小字 + 英文/标点，用于识别、翻译、文字标注、长文换行
  color-chart.png    渐变、色块、细线、棋盘格，用于调色板/滴管/滤镜/画笔/高亮
  checker-alpha.png  透明棋盘格，用于不透明度、聚光灯、水印
  long-list.html     200 行列表，用于长截屏拼接与重开
  animation.html     动态色块 + 计时，用于录屏/暂停/旋转
  translate-cn.txt   600+ 字中文，用于翻译分段与长文
  translate-en.txt   600+ 字符英文，用于反向翻译
  qr-links.png       两个二维码（短链接 + 纯文本），用于识码/标记

Run: python tools/make_screenshot_fixtures.py
"""
from pathlib import Path
import random

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "testdata" / "screenshot"
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_BOLD = "C:/Windows/Fonts/msyhbd.ttc"

CN = (
    "截图控件真机测试素材。这一段用于检查识别顺序、标点转换与长文换行："
    "当一行文字超过文本框宽度时，应当自动折行，行距保持一致，背景只覆盖文字行本身。"
    "第二句包含英文 Snow Shot 1.1.8、数字 2026-11-09、以及全角标点：，。！？；："
)
EN = (
    "The quick brown fox jumps over the lazy dog. This line checks Latin glyph metrics, "
    "mixed CJK/Latin spacing, and whether OCR keeps punctuation such as commas, periods, "
    "and quotation marks in the expected order."
)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD if bold else FONT, size)


def text_page(path: Path) -> None:
    image = Image.new("RGB", (1440, 2400), "white")
    draw = ImageDraw.Draw(image)
    y = 90
    draw.text((80, y), "截图控件测试页", font=font(72, True), fill=(20, 20, 20))
    y += 120
    draw.text((80, y), "1. 正文与折行", font=font(44, True), fill=(30, 30, 30))
    y += 70
    for _ in range(2):
        for line in wrap(draw, CN, font(34), 1280):
            draw.text((80, y), line, font=font(34), fill=(35, 35, 35))
            y += 52
        y += 16
    draw.text((80, y), "2. 英文与混排", font=font(44, True), fill=(30, 30, 30))
    y += 70
    for line in wrap(draw, EN, font(30), 1280):
        draw.text((80, y), line, font=font(30), fill=(35, 35, 35))
        y += 46
    y += 24
    draw.text((80, y), "3. 小字与密集行", font=font(44, True), fill=(30, 30, 30))
    y += 70
    for i in range(10):
        draw.text((80, y), f"{i + 1:02d}  小字样本 10sp：识别是否漏行、是否串行；ABCdef 0123456789。",
                  font=font(22), fill=(60, 60, 60))
        y += 34
    y += 24
    draw.text((80, y), "4. 用于翻译的长段落", font=font(44, True), fill=(30, 30, 30))
    y += 70
    long_cn = CN * 4
    for line in wrap(draw, long_cn, font(32), 1280):
        if y > 2320:
            break
        draw.text((80, y), line, font=font(32), fill=(35, 35, 35))
        y += 48
    image.save(path)


def wrap(draw: ImageDraw.ImageDraw, text: str, f: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines, current = [], ""
    for ch in text:
        candidate = current + ch
        if draw.textlength(candidate, font=f) > width and current:
            lines.append(current)
            current = ch
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


def color_chart(path: Path) -> None:
    width, height = 1440, 1920
    image = Image.new("RGB", (width, height), "white")
    draw = ImageDraw.Draw(image)
    for x in range(width):
        draw.line([(x, 0), (x, 320)], fill=(int(255 * x / width), 40, 255 - int(255 * x / width)))
    for y in range(320, 640):
        draw.line([(0, y), (width, y)], fill=(30, int(255 * (y - 320) / 320), 120))
    colors = [(255, 0, 0), (255, 128, 0), (255, 255, 0), (0, 200, 0), (0, 160, 255), (80, 0, 255), (255, 0, 200), (0, 0, 0)]
    cell = width // len(colors)
    for i, color in enumerate(colors):
        draw.rectangle([i * cell, 660, (i + 1) * cell, 820], fill=color)
    for i in range(12):
        x = 40 + i * 110
        draw.line([(x, 860), (x, 1160)], fill=(20, 20, 20), width=max(1, 12 - i))
    for i in range(120):
        x = 60 + (i % 12) * 110
        y = 1200 + (i // 12) * 46
        draw.text((x, y), f"{i:03d} Aa", font=font(22), fill=(20, 20, 20))
    draw.rectangle([60, 1580, 1380, 1840], outline=(20, 20, 20), width=4)
    draw.text((80, 1600), "细线/边缘/抗锯齿检查：1px 线、圆角、斜线", font=font(28), fill=(20, 20, 20))
    image.save(path)


def checker_alpha(path: Path) -> None:
    size = 32
    image = Image.new("RGBA", (1200, 900), (255, 255, 255, 255))
    draw = ImageDraw.Draw(image)
    for y in range(0, 900, size):
        for x in range(0, 1200, size):
            if (x // size + y // size) % 2 == 0:
                draw.rectangle([x, y, x + size, y + size], fill=(210, 210, 210, 255))
    draw.ellipse([120, 120, 520, 520], fill=(255, 0, 0, 128))
    draw.rectangle([620, 160, 1080, 480], fill=(0, 80, 255, 90))
    image.save(path)


def qr_codes(path: Path) -> bool:
    try:
        import qrcode  # type: ignore
    except Exception:
        return False
    image = Image.new("RGB", (1400, 760), "white")
    draw = ImageDraw.Draw(image)
    items = [("https://example.com/snow-shot?from=voxfix", 40), ("VOXFIX-QR-CONTENT-2026-11-09", 740)]
    for text, x in items:
        qr = qrcode.QRCode(border=2, box_size=8)
        qr.add_data(text)
        qr.make(fit=True)
        img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        image.paste(img, (x, 40))
        draw.text((x, 640), text[:38], font=font(22), fill=(20, 20, 20))
    image.save(path)
    return True


def long_list(path: Path) -> None:
    rows = "\n".join(
        f'<li><b>{i + 1:03d}</b> 记录 {2026 - i % 3}-{i % 12 + 1:02d}-{i % 28 + 1:02d} '
        f'<span>用于长截屏拼接的行内容 {i + 1}，包含中文、English、0123456789。</span></li>'
        for i in range(200)
    )
    path.write_text(
        "<!doctype html><meta charset='utf-8'><title>长截屏测试列表</title>"
        "<style>body{margin:0;font:16px/1.7 sans-serif;background:#fafafa;color:#222}"
        "header{position:sticky;top:0;background:#1677ff;color:#fff;padding:14px 18px;font-size:18px}"
        "ol{list-style:none;margin:0;padding:8px 14px}li{padding:10px 6px;border-bottom:1px solid #e5e5e5}"
        "b{display:inline-block;width:52px;color:#1677ff}span{color:#666}</style>"
        "<header>长截屏测试 · 200 行 · 顶部固定栏</header><ol>" + rows + "</ol>",
        encoding="utf-8",
    )


def animation(path: Path) -> None:
    path.write_text(
        "<!doctype html><meta charset='utf-8'><title>录屏测试场景</title>"
        "<style>body{margin:0;height:100vh;display:grid;place-items:center;font:700 42px sans-serif;"
        "background:#101418;color:#fff;overflow:hidden}#box{width:70vw;height:32vh;border-radius:18px;"
        "background:#1677ff;transition:background .4s}#clock{margin-top:24px;font-size:28px;color:#9ad}"
        "</style><div><div id='box'></div><div id='clock'>0.0s</div></div><script>"
        "let t=0;const start=performance.now();setInterval(()=>{t=(performance.now()-start)/1000;"
        "box.style.background=`hsl(${(t*90)%360} 80% 55%)`;box.style.transform=`translateX(${Math.sin(t)*12}vw)`;"
        "clock.textContent=t.toFixed(1)+'s';},50);</script>",
        encoding="utf-8",
    )


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    text_page(OUT / "text-page.png")
    color_chart(OUT / "color-chart.png")
    checker_alpha(OUT / "checker-alpha.png")
    long_list(OUT / "long-list.html")
    animation(OUT / "animation.html")
    (OUT / "translate-cn.txt").write_text((CN + "\n") * 8, encoding="utf-8")
    (OUT / "translate-en.txt").write_text((EN + "\n") * 6, encoding="utf-8")
    qr = qr_codes(OUT / "qr-links.png")
    print(f"fixtures written to {OUT}")
    print("qr-links.png:", "ok" if qr else "skipped (python qrcode not installed)")


if __name__ == "__main__":
    random.seed(7)
    main()
