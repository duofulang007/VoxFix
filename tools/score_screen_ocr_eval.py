"""Score the independent K60 OCR comparison without counting repeats as new text."""
import argparse
import json
import math
import re
import unicodedata
from collections import defaultdict
from pathlib import Path


def normalize(text, content=False):
    return "".join(c for c in text if not c.isspace() and not (content and unicodedata.category(c).startswith("P")))


def edits(left, right):
    previous = list(range(len(right) + 1))
    for i, a in enumerate(left, 1):
        current = [i]
        for j, b in enumerate(right, 1):
            current.append(min(current[-1] + 1, previous[j] + 1, previous[j - 1] + (a != b)))
        previous = current
    return previous[-1]


def accuracy(rows, content=False):
    pairs = [(normalize(r["expected"], content), normalize(r["actual"], content)) for r in rows]
    count = sum(len(a) for a, _ in pairs)
    errors = sum(edits(a, b) for a, b in pairs)
    return dict(images=len(rows), characters=count, edits=errors, cer=100 * errors / count if count else None,
                exact=sum(a == b for a, b in pairs))


def timing(rows, key="ms"):
    values = sorted(r[key] for r in rows)
    return {name: values[min(len(values) - 1, math.ceil(len(values) * fraction) - 1)]
            for name, fraction in (("p50", .5), ("p95", .95), ("max", 1))}


def score(data):
    assert edits("abc", "adc") == 1 and edits("", "ab") == 2 and edits("ab", "ba") == 2
    assert normalize(" 金额：1.5 元\n", True) == "金额15元"
    rows = data["rows"]
    if len(rows) != 588 or data["internetPermission"] is not False:
        raise ValueError("Incomplete or non-offline run")
    index = {}
    for row in rows:
        key = (row["engine"], row["round"], row["sample"], row["size"], row["dark"])
        if key in index:
            raise ValueError("Duplicate comparison row")
        index[key] = row
    results = {}
    for engine in ("mlkit", "small", "medium"):
        all_rows = [r for r in rows if r["engine"] == engine]
        unique = [r for r in all_rows if r["round"] == 0 and r["sample"] >= 0]
        if len(unique) != 96 or len(all_rows) != 196:
            raise ValueError("Wrong per-engine sample count")
        changes = defaultdict(int)
        for r in unique:
            baseline = index["mlkit", 0, r["sample"], r["size"], r["dark"]]
            repeated = index[engine, 1, r["sample"], r["size"], r["dark"]]
            if baseline["expected"] != r["expected"]:
                raise ValueError("Unmatched input")
            a = edits(normalize(r["expected"], True), normalize(r["actual"], True))
            b = edits(normalize(baseline["expected"], True), normalize(baseline["actual"], True))
            changes["improved" if a < b else "regressed" if a > b else "same"] += 1
            changes["repeat_changed"] += r["actual"] != repeated["actual"]
        results[engine] = dict(strict=accuracy(unique), content=accuracy(unique, True),
            original48=accuracy([r for r in unique if r["sample"] < 6]),
            timing=timing([r for r in all_rows if r["sample"] >= 0]),
            extraTiming=timing([r for r in all_rows if r["sample"] >= 0], "extraMs"),
            blankFalsePositives=sum(bool(normalize(r["actual"])) for r in all_rows if r["sample"] < 0),
            versusBaseline=dict(changes),
            bySize={str(z): accuracy([r for r in unique if r["size"] == z], True) for z in (16,24,36,64)},
            bySample={str(z): accuracy([r for r in unique if r["sample"] == z], True) for z in range(12)},
            numericExact=sum(re.findall(r"[0-9]+(?:[,.][0-9]+)*", r["expected"]) == re.findall(r"[0-9]+(?:[,.][0-9]+)*", r["actual"]) for r in unique))
    return dict(results=results, elapsedMs=data["elapsedMs"], smallLoadMs=data["smallLoadMs"], mediumLoadMs=data["mediumLoadMs"], memory=data["memory"])


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    args = parser.parse_args()
    output = score(json.loads(args.input.read_text(encoding="utf-8-sig")))
    target = args.input.with_name(args.input.stem + "-summary.json")
    target.write_text(json.dumps(output, ensure_ascii=False, indent=2), encoding="utf-8")
    for engine, result in output["results"].items():
        print(engine, "strict CER", round(result["strict"]["cer"], 3), "content CER", round(result["content"]["cer"], 3),
              "timing", result["timing"], "vs baseline", result["versusBaseline"], "numeric exact", result["numericExact"])
    print("load ms", output["smallLoadMs"], output["mediumLoadMs"], "elapsed ms", output["elapsedMs"])


if __name__ == "__main__":
    main()
