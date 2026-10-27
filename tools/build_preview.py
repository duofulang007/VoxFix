"""Bundle the preview so double-clicking one HTML file needs no server.

    python tools/build_preview.py

新增 preview 模块只需在 app.mjs 里 import，这里不用登记。
"""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
preview = root / 'preview'
target = root / '键盘试用页.html'

ENTRY = 'app.mjs'
# 只认 app.mjs 图里的静态依赖；同时覆盖 `import {x} from './a.mjs'` 与 `import './a.mjs'`。
IMPORT_RE = re.compile(r"^import\b[^;]*?'\./([\w.-]+\.mjs)'", re.M)


def strip_module_syntax(text):
    """去掉 import 语句与行首 `export `，让模块能顺序拼成一个 <script>。"""
    out = []
    open_import = None
    for line in text.split('\n'):
        if open_import is not None:
            open_import += line
            if ';' in line:
                open_import = None
            continue
        if line.startswith('import '):
            if ';' not in line:
                open_import = line
            continue
        out.append(line[len('export '):] if line.startswith('export ') else line)
    if open_import is not None:
        raise SystemExit('preview 模块里有没以分号结束的 import：\n' + open_import[:120])
    return '\n'.join(out)


def module_order(entry):
    """依赖在前、入口在后的拓扑序；遇到循环依赖直接报错而不是静默拼错。"""
    order, done, stack = [], set(), []

    def visit(name):
        if name in done:
            return
        if name in stack:
            raise SystemExit('preview 模块存在循环依赖，无法打包：'
                             + ' -> '.join(stack[stack.index(name):] + [name]))
        stack.append(name)
        for dep in IMPORT_RE.findall((preview / name).read_text(encoding='utf-8')):
            visit(dep)
        stack.pop()
        done.add(name)
        order.append(name)

    visit(entry)
    return order


def render():
    """拼出单文件 HTML（不落盘）。"""
    html = (preview / 'index.html').read_text(encoding='utf-8')
    css = (preview / 'style.css').read_text(encoding='utf-8')
    native_css = (preview / 'native-ime.css').read_text(encoding='utf-8')
    meme_css = (preview / 'meme-search.css').read_text(encoding='utf-8')
    landscape_css = (preview / 'landscape.css').read_text(encoding='utf-8')
    modules = module_order(ENTRY)
    bundle = '\n'.join(strip_module_syntax((preview / name).read_text(encoding='utf-8')) for name in modules)
    stray = re.findall(r'^\s*(?:import|export)\b.*$', bundle, flags=re.M)
    if stray:
        raise SystemExit('打包后仍残留模块语法，需要补充整理规则：' + stray[0][:80])
    dictionary = (preview / 'pinyin-data.json').read_text(encoding='utf-8')
    packs = (preview / 'phrase-packs.json').read_text(encoding='utf-8')
    script = 'const embeddedEnglishWords='+json.dumps((preview/'english-words.txt').read_text(encoding='utf-8'))+';\nconst embeddedEnglishLicense='+json.dumps((preview/'vendor/rime-ice/LICENSE').read_text(encoding='utf-8'))+';\nconst embeddedPinyinData=' + dictionary + ';\nconst embeddedPhrasePacks='+packs+';\n' + bundle
    assert '</script' not in script.lower()
    html = html.replace('<link rel="stylesheet" href="style.css">', '<style>' + css + '</style>')
    html = html.replace('<link rel="stylesheet" href="native-ime.css">', '<style>' + native_css + '</style>')
    html = html.replace('<link rel="stylesheet" href="meme-search.css">', '<style>' + meme_css + '</style>')
    html = html.replace('<link rel="stylesheet" href="landscape.css">', '<style>' + landscape_css + '</style>')
    html = html.replace('<script type="module" src="app.mjs"></script>', '<script>\n' + script + '\n</script>')
    assert 'src="app.mjs"' not in html and all(f'href="{name}"' not in html for name in ('style.css', 'native-ime.css', 'meme-search.css', 'landscape.css'))
    return html


def build():
    """内容没变就不写盘，重复跑不会反复重写 13MB。"""
    html = render()
    if target.exists() and target.read_text(encoding='utf-8') == html:
        print(f'{target.name}: 内容未变，跳过写入')
        return False
    target.write_text(html, encoding='utf-8')
    print(f'Built {target.name}: {target.stat().st_size:,} bytes')
    return True


if __name__ == '__main__':
    build()
