"""Bundle the preview so double-clicking one HTML file needs no server."""
import base64
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
preview = root / 'preview'
html = (preview / 'index.html').read_text(encoding='utf-8')
css = (preview / 'style.css').read_text(encoding='utf-8')
native_css = (preview / 'native-ime.css').read_text(encoding='utf-8')
core = re.sub(r'^export ', '', (preview / 'core.mjs').read_text(encoding='utf-8'), flags=re.M)
voice = re.sub(r'^export ', '', (preview / 'voice.mjs').read_text(encoding='utf-8'), flags=re.M)
orb = re.sub(r'^export ', '', (preview / 'voice-orb.mjs').read_text(encoding='utf-8'), flags=re.M)
pinyin = re.sub(r'^export ', '', (preview / 'pinyin.mjs').read_text(encoding='utf-8'), flags=re.M)
ink = re.sub(r'^export ', '', (preview / 'ink.mjs').read_text(encoding='utf-8'), flags=re.M)
english = re.sub(r'^export ', '', (preview / 'english.mjs').read_text(encoding='utf-8'), flags=re.M)
app = re.sub(r'^import .*?;\n', '', (preview / 'app.mjs').read_text(encoding='utf-8'), flags=re.M)
refs = {file.name: 'data:image/jpeg;base64,' + base64.b64encode(file.read_bytes()).decode('ascii') for file in (preview / 'reference').glob('*.jpg')}
import json
app = app.replace("'reference/'+(referenceMap[ref]||referenceMap.letters)", "referenceImages[referenceMap[ref]||referenceMap.letters]")
dictionary = (preview / 'pinyin-data.json').read_text(encoding='utf-8')
packs = (preview/'phrase-packs.json').read_text(encoding='utf-8')
script = 'const embeddedEnglishWords='+json.dumps((preview/'english-words.txt').read_text(encoding='utf-8'))+';\nconst embeddedEnglishLicense='+json.dumps((preview/'vendor/rime-ice/LICENSE').read_text(encoding='utf-8'))+';\nconst embeddedPinyinData=' + dictionary + ';\nconst embeddedPhrasePacks='+packs+';\nconst referenceImages=' + json.dumps(refs) + ';\n' + core + '\n' + voice + '\n' + orb + '\n' + pinyin + '\n' + ink + '\n' + english + '\n' + app
assert '</script' not in script.lower()
html = html.replace('<link rel="stylesheet" href="style.css">', '<style>' + css + '</style>')
html = html.replace('<link rel="stylesheet" href="native-ime.css">', '<style>' + native_css + '</style>')
html = html.replace('<script type="module" src="app.mjs"></script>', '<script>\n' + script + '\n</script>')
assert 'src="app.mjs"' not in html and 'href="style.css"' not in html and len(refs) == 15
target = root / '键盘试用页.html'
target.write_text(html, encoding='utf-8')
print(f'Built {target.name}: {target.stat().st_size:,} bytes; {len(refs)} embedded reference images')
