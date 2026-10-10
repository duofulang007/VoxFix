"""Run synthetic, non-personal texts through the installed model and save evidence."""
import json
from pathlib import Path
from polish import Polisher


if __name__ == '__main__':
    engine = Polisher();results = []
    try:
        for text in ['嗯那个我已经收到你的消息了就是就是我晚点联系你啊', '我明天下午三点去跑步然后然后跑10公里不要跑15公里回家以后打开Obsidian记录一下']:
            result = engine.polish(text)
            assert result['text'] and result['offline']
            assert '就是就是' not in result['text'] and '然后然后' not in result['text']
            results.append({'source':text, **result})
        report = {'scope':'Synthetic desktop CPU test, not user recordings or Redmi K60', 'model':'Qwen3.5-2B Q4_K_M', 'cases':results}
        path = Path(__file__).resolve().parents[1]/'docs/offline-polish-smoke.json'
        path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False,indent=2))
    finally:
        engine.close()
