"""Bundle the preview so double-clicking one HTML file needs no server."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
preview = root / 'preview'
html = (preview / 'index.html').read_text(encoding='utf-8')
css = (preview / 'style.css').read_text(encoding='utf-8')
native_css = (preview / 'native-ime.css').read_text(encoding='utf-8')
core = re.sub(r'^export ', '', (preview / 'core.mjs').read_text(encoding='utf-8'), flags=re.M)
orb = re.sub(r'^export ', '', (preview / 'voice-orb.mjs').read_text(encoding='utf-8'), flags=re.M)
pinyin = re.sub(r'^export ', '', (preview / 'pinyin.mjs').read_text(encoding='utf-8'), flags=re.M)
pinyin = re.sub(r'^import .*?;\n', '', pinyin, flags=re.M)
english = re.sub(r'^export ', '', (preview / 'english.mjs').read_text(encoding='utf-8'), flags=re.M)
split_module = re.sub(r'^export ', '', (preview / 'split.mjs').read_text(encoding='utf-8'), flags=re.M)
lexicon = re.sub(r'^export ', '', (preview / 'lexicon.mjs').read_text(encoding='utf-8'), flags=re.M)
lexicon = re.sub(r'^import .*?;\n', '', lexicon, flags=re.M)
app = re.sub(r'^import .*?;\n', '', (preview / 'app.mjs').read_text(encoding='utf-8'), flags=re.M)
dictionary = (preview / 'pinyin-data.json').read_text(encoding='utf-8')
packs = (preview/'phrase-packs.json').read_text(encoding='utf-8')
script = 'const embeddedEnglishWords='+json.dumps((preview/'english-words.txt').read_text(encoding='utf-8'))+';\nconst embeddedEnglishLicense='+json.dumps((preview/'vendor/rime-ice/LICENSE').read_text(encoding='utf-8'))+';\nconst embeddedPinyinData=' + dictionary + ';\nconst embeddedPhrasePacks='+packs+';\n' + core + '\n' + lexicon + '\n' + orb + '\n' + pinyin + '\n' + english + '\n' + split_module + '\n' + app
assert '</script' not in script.lower()
html = html.replace('<link rel="stylesheet" href="style.css">', '<style>' + css + '</style>')
html = html.replace('<link rel="stylesheet" href="native-ime.css">', '<style>' + native_css + '</style>')
html = html.replace('<script type="module" src="app.mjs"></script>', '<script>\n' + script + '\n</script>')
assert 'src="app.mjs"' not in html and 'href="style.css"' not in html
target = root / '键盘试用页.html'
target.write_text(html, encoding='utf-8')
print(f'Built {target.name}: {target.stat().st_size:,} bytes')
