"""Desktop smoke test for the pinned Hy-MT2 GGUF.

This is not a benchmark: it checks that the patched llama.cpp can load the
relabelled model, follows the Hy-MT2 prompt, keeps numbers/emails, and does not
leak template tokens. Latency comes from llama-bench, not process startup.
"""

import argparse
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOS = "<｜hy_begin▁of▁sentence｜>"
USER = "<｜hy_User｜>"
ASSISTANT = "<｜hy_Assistant｜>"

CASES = [
    ("en_zh", "The meeting starts at 9:30 AM in Room 1204.", ["9:30", "1204"], None),
    ("en_zh", "This product is not covered by the warranty.", [], "not"),
    ("en_zh", "Please send the report to alice@example.com by Friday.", ["alice@example.com"], None),
    ("en_zh", "Revenue grew 12.5% year over year to $4.8 million.", ["12.5", "480"], None),
    ("en_zh", "Tap the gear icon, then select Privacy.", [], None),
    ("en_zh", "Battery life is up to 18 hours, but charging takes 90 minutes.", ["18", "90"], None),
    ("en_zh", "The update fixes the crash when opening large images.", [], None),
    ("en_zh", "No refunds after 30 days.", ["30"], "No"),
    ("ja_zh", "会議は午前9時30分に1204号室で始まります。", ["9", "30", "1204"], None),
    ("ja_zh", "この製品は保証の対象外です。", [], "対象外"),
    ("ja_zh", "金曜日までにalice@example.comへ報告書を送ってください。", ["alice@example.com"], None),
    ("ja_zh", "売上は前年比12.5%増の480万ドルでした。", ["12.5", "480"], None),
    ("ja_zh", "歯車アイコンをタップして、プライバシーを選択します。", [], None),
    ("ja_zh", "バッテリーは最大18時間もちますが、充電には90分かかります。", ["18", "90"], None),
    ("ja_zh", "この更新は大きな画像を開くときのクラッシュを修正します。", [], None),
    ("ja_zh", "30日を過ぎた返金はできません。", ["30"], "ません"),
    ("ko_zh", "회의는 오전 9시 30분에 1204호에서 시작합니다.", ["9", "30", "1204"], None),
    ("ko_zh", "이 제품은 보증 대상이 아닙니다.", [], "아닙니다"),
    ("ko_zh", "금요일까지 alice@example.com으로 보고서를 보내 주세요.", ["alice@example.com"], None),
    ("ko_zh", "매출은 전년 대비 12.5% 증가한 480만 달러였습니다.", ["12.5", "480"], None),
    ("ko_zh", "톱니바퀴 아이콘을 누른 다음 개인정보 보호를 선택하세요.", [], None),
    ("ko_zh", "배터리는 최대 18시간 사용할 수 있지만 충전에는 90분이 걸립니다.", ["18", "90"], None),
    ("ko_zh", "이 업데이트는 큰 이미지를 열 때 발생하는 충돌을 수정합니다.", [], None),
    ("ko_zh", "30일 이후에는 환불할 수 없습니다.", ["30"], "없습니다"),
    ("zh_en", "会议上午9点30分在1204房间开始。", ["9", "30", "1204"], None),
    ("zh_en", "本产品不在保修范围内。", [], "not"),
    ("zh_en", "请在周五前把报告发到 alice@example.com。", ["alice@example.com"], None),
    ("zh_en", "营收同比增长12.5%，达到480万美元。", ["12.5", "4.8"], None),
    ("zh_en", "点击齿轮图标，然后选择隐私。", [], None),
    ("zh_en", "电池最长可用18小时，但充电需要90分钟。", ["18", "90"], None),
    ("zh_en", "这次更新修复了打开大图时的崩溃。", [], None),
    ("zh_en", "30天后不予退款。", ["30"], None),
]

TARGETS = {"en_zh": "中文", "ja_zh": "中文", "ko_zh": "中文", "zh_en": "英文"}
NEGATION = {
    "en_zh": ["不", "没", "未", "无", "否", "别"],
    "ja_zh": ["不", "没", "未", "无", "否", "外"],
    "ko_zh": ["不", "没", "未", "无", "否", "外"],
    "zh_en": ["not", "no", "never", "n't", "without"],
}
NUMBER = re.compile(r"\d+(?:[.,]\d+)?")


def prompt_for(direction, text):
    return "%s%s将以下文本翻译为%s，注意只需要输出翻译后的结果，不要额外解释：\n%s%s" % (
        BOS, USER, TARGETS[direction], text, ASSISTANT)


def clean_cli(stdout):
    """llama-cli echoes the prompt in conversation mode even with --no-display-prompt."""
    text = stdout.rsplit(ASSISTANT, 1)[-1]
    for cut in ("[ Prompt:", "Exiting...", "available commands:"):
        text = text.split(cut, 1)[0]
    return text.strip()


def run(cli, model, prompt):
    command = [str(cli), "-m", str(model), "-f", str(write_prompt(prompt)), "-n", "256", "--temp", "0",
               "-t", "4", "-st", "--no-display-prompt", "--no-warmup"]
    started = time.time()
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    return clean_cli(result.stdout), result.returncode, time.time() - started, result.stderr


def write_prompt(prompt):
    path = ROOT / ".android/translate-eval-prompt.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(prompt, encoding="utf-8")
    return path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cli", default=str(ROOT / ".android/llama-host-build/bin/llama-cli.exe"))
    parser.add_argument("--model", default=str(ROOT / "models/hy-mt2/Hy-MT2-1.8B-1.25Bit.gguf"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--case", default="", help="1-based case numbers, comma separated")
    args = parser.parse_args()
    cli = Path(args.cli)
    model = Path(args.model)
    if not cli.exists():
        raise SystemExit("CLI_MISSING " + str(cli))
    if not model.exists():
        raise SystemExit("MODEL_MISSING " + str(model))
    cases = CASES[:args.limit] if args.limit else CASES
    if args.case:
        wanted = {int(item) for item in args.case.split(",") if item.strip()}
        cases = [case for index, case in enumerate(CASES, 1) if index in wanted]
    failures = 0
    for index, (direction, text, keep, negation) in enumerate(cases, 1):
        output, code, elapsed, stderr = run(cli, model, prompt_for(direction, text))
        problems = []
        if code != 0:
            problems.append("exit=%d" % code)
        if not output:
            problems.append("empty")
        if "hy_" in output or "<|" in output:
            problems.append("template-leak")
        if output.strip() == text.strip():
            problems.append("echo")
        for token in keep:
            if token not in output:
                problems.append("missing:" + token)
        if NUMBER.findall(text) and len(NUMBER.findall(output)) < len(NUMBER.findall(text)):
            problems.append("fewer-numbers")
        if negation and not any(marker in output.lower() for marker in NEGATION[direction]):
            problems.append("negation-lost")
        status = "FAIL" if problems else "OK"
        if problems:
            failures += 1
        print("[%02d] %s %s %5.1fs %s" % (index, status, direction, elapsed, ",".join(problems)))
        print("     src: " + text)
        print("     out: " + output.replace("\n", " ")[:300])
        if problems:
            tail = stderr.strip().splitlines()[-3:]
            for line in tail:
                print("     log: " + line[:220])
    print("SUMMARY cases=%d failures=%d" % (len(cases), failures))
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
