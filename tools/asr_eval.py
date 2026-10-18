"""语音配置对照台：同一批音频，跑不同解码配置，逐字比对差异。

用途是回答"改配置到底有没有用"，不是算通用准确率。真值缺失时只输出配置间差异；
目录里存在同名 `.txt` 时顺带算字符错误率。

    .venv/Scripts/python.exe tools/asr_eval.py --audio .android/punct-audit-20261017/fixtures

音频格式：16 kHz 单声道 float32 裸 PCM（`.f32`），与本项目真机固定音频一致。
"""

import argparse
import json
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).resolve().parent.parent
MODELS = ROOT / "models"


def load_audio(path):
    return np.fromfile(path, dtype=np.float32)


def streaming_recognizer(method, paths):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(paths["tokens"]),
        encoder=str(paths["encoder"]),
        decoder=str(paths["decoder"]),
        joiner=str(paths["joiner"]),
        num_threads=2,
        decoding_method=method,
        max_active_paths=4,
        enable_endpoint_detection=False,
        model_type="zipformer",
    )


def run_streaming(recognizer, samples):
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, samples)
    stream.accept_waveform(16000, np.zeros(8000, dtype=np.float32))
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    return recognizer.get_result(stream)


def offline_recognizer(paths, language):
    return sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(paths["sensevoice"]),
        tokens=str(paths["sv_tokens"]),
        num_threads=4,
        language=language,
        use_itn=True,
    )


def run_offline(recognizer, samples):
    """返回整个 result：语种标签（`lang`）与文本一样是配置对照的观测对象。

    `language="auto"` 时 `lang` 是 SenseVoice 内建 LID 的判定结果；写死语种时它仍会输出，
    但通常跟着提示走。真机二遍用 auto，这里必须能看到它判了什么。
    """
    stream = recognizer.create_stream()
    stream.accept_waveform(16000, samples)
    recognizer.decode_stream(stream)
    return stream.result


def cer(truth, hyp):
    """字符级编辑距离 / 真值长度，忽略空白。"""
    a = "".join(truth.split())
    b = "".join(hyp.split())
    if not a:
        return 0.0 if not b else 1.0
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(current[j - 1] + 1, previous[j] + 1,
                               previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1] / len(a)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", required=True, help="含 *.f32 的目录")
    parser.add_argument("--out", default="", help="结果 JSON 路径")
    parser.add_argument("--quiet", action="store_true")
    args = parser.parse_args()

    streaming_paths = {
        "tokens": MODELS / "streaming/tokens.txt",
        "encoder": MODELS / "streaming/encoder.int8.onnx",
        "decoder": MODELS / "streaming/decoder.onnx",
        "joiner": MODELS / "streaming/joiner.int8.onnx",
    }
    offline_paths = {
        "sensevoice": MODELS / "sensevoice/model.int8.onnx",
        "sv_tokens": MODELS / "sensevoice/tokens.txt",
    }
    for path in list(streaming_paths.values()) + list(offline_paths.values()):
        if not path.exists():
            raise SystemExit(f"模型缺失：{path}")

    files = sorted(Path(args.audio).glob("*.f32"))
    if not files:
        raise SystemExit(f"{args.audio} 里没有 .f32")

    greedy = streaming_recognizer("greedy_search", streaming_paths)
    beam = streaming_recognizer("modified_beam_search", streaming_paths)
    zh = offline_recognizer(offline_paths, "zh")
    auto = offline_recognizer(offline_paths, "auto")

    rows = []
    for path in files:
        samples = load_audio(path)
        row = {"id": path.stem, "seconds": round(len(samples) / 16000, 2)}
        for name, fn in (
            ("stream_greedy", lambda: run_streaming(greedy, samples)),
            ("stream_beam", lambda: run_streaming(beam, samples)),
            ("sv_zh", lambda: run_offline(zh, samples)),
            ("sv_auto", lambda: run_offline(auto, samples)),
        ):
            started = time.perf_counter()
            outcome = fn()
            # 二遍返回整个 result：文本之外还要记它自己判的语种。
            row[name] = outcome.text if name.startswith("sv_") else outcome
            if name.startswith("sv_"):
                row[name + "_lang"] = outcome.lang
            row[name + "_ms"] = round((time.perf_counter() - started) * 1000)
        truth_file = path.with_suffix(".txt")
        if truth_file.exists():
            truth = truth_file.read_text(encoding="utf-8").strip()
            row["truth"] = truth
            for name in ("stream_greedy", "stream_beam", "sv_zh", "sv_auto"):
                row[name + "_cer"] = round(cer(truth, row[name]), 4)
        rows.append(row)

    if not args.quiet:
        for row in rows:
            print(f"\n=== {row['id']}  {row['seconds']}s ===")
            for name in ("stream_greedy", "stream_beam", "sv_zh", "sv_auto"):
                mark = ""
                if "truth" in row:
                    mark = f"  CER={row[name + '_cer']:.2%}"
                if name.startswith("sv_"):
                    mark += f"  lang={row[name + '_lang']}"
                print(f"  {name:<14} {row[name + '_ms']:>6}ms  {row[name]}{mark}")
            changes = [
                name for name in ("stream_beam", "sv_zh", "sv_auto")
                if row[name] != row["stream_greedy" if name == "stream_beam" else "sv_zh"]
            ]
            if changes:
                print(f"  与基线不同：{', '.join(changes)}")

    if args.out:
        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(
            json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n写入 {out}")

    print("\n=== 汇总 ===")
    for name in ("stream_greedy", "stream_beam", "sv_zh", "sv_auto"):
        total = sum(row[name + "_ms"] for row in rows)
        print(f"  {name:<14} 合计 {total}ms")
    with_truth = [row for row in rows if "truth" in row]
    if with_truth:
        for name in ("stream_greedy", "stream_beam", "sv_zh", "sv_auto"):
            mean = sum(row[name + "_cer"] for row in with_truth) / len(with_truth)
            print(f"  {name:<14} 平均 CER {mean:.2%}")


if __name__ == "__main__":
    main()
