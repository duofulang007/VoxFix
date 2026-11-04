"""Download the pinned Hy-MT2 GGUF without ever trusting a partial file.

The phone downloads the same file at runtime; this script exists so the desktop
smoke test and the Android asset manifest agree on one byte count and one SHA.
"""

import argparse
import hashlib
import json
import os
import struct
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/translate-dependencies.json"


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def fetch(url, target, expected_bytes, resume):
    offset = target.stat().st_size if resume and target.exists() else 0
    if offset > expected_bytes:
        target.unlink()
        offset = 0
    headers = {"User-Agent": "VoxFix-translate-model", "Accept-Encoding": "identity"}
    if offset:
        headers["Range"] = "bytes=%d-" % offset
    request = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        status = response.getcode()
        if status == 200:
            offset = 0  # server ignored Range; restart instead of appending garbage
            mode = "wb"
        elif status == 206:
            mode = "ab"
        else:
            raise urllib.error.HTTPError(url, status, "unexpected status", response.headers, None)
        with target.open(mode) as handle:
            received = offset
            while True:
                block = response.read(1024 * 256)
                if not block:
                    break
                handle.write(block)
                received += len(block)
                if received // (1024 * 1024) != (received - len(block)) // (1024 * 1024):
                    print("  %d/%d bytes (%.1f%%)" % (received, expected_bytes, received * 100.0 / expected_bytes), flush=True)
        return target.stat().st_size


def relabel(model, final):
    """The pinned GGUF uses the pre-rename STQ_0 tensor id (42); patched llama.cpp expects 43."""
    spec = model.get("relabelTensorType")
    if not spec:
        return
    raw = (ROOT / spec["offsets"]).read_bytes()
    if len(raw) % 4 != 0:
        raise SystemExit("OFFSET_TABLE_CORRUPT")
    offsets = struct.unpack("<%dI" % (len(raw) // 4), raw)
    changed = 0
    with final.open("r+b") as handle:
        for offset in offsets:
            handle.seek(offset)
            current = struct.unpack("<I", handle.read(4))[0]
            if current == spec["from"]:
                handle.seek(offset)
                handle.write(struct.pack("<I", spec["to"]))
                changed += 1
            elif current != spec["to"]:
                raise SystemExit("TENSOR_TYPE_UNEXPECTED %d at %d" % (current, offset))
        handle.flush()
        os.fsync(handle.fileno())
    print("MODEL_RELABELLED tensors=%d" % changed)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", help="source id from translate-dependencies.json")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    model = manifest["model"]
    target_dir = ROOT / model["targetDirectory"]
    target_dir.mkdir(parents=True, exist_ok=True)
    final = target_dir / model["file"]
    partial = target_dir / (model["file"] + ".part")
    if final.exists() and not args.force:
        # The relabelled copy no longer matches the upstream SHA, so accept both.
        accepted = {model["sha256"], model.get("relabelSha256")} - {None}
        if final.stat().st_size == model["bytes"] and sha256_file(final) in accepted:
            print("MODEL_READY " + str(final))
            return 0
        final.unlink()
    sources = [s for s in model["sources"] if not args.source or s["id"] == args.source]
    if not sources:
        raise SystemExit("UNKNOWN_SOURCE " + str(args.source))
    errors = []
    for source in sources:
        try:
            print("FETCH " + source["id"] + " " + source["url"], flush=True)
            size = fetch(source["url"], partial, model["bytes"], resume=source.get("range", True))
            if size != model["bytes"]:
                raise SystemExit("SIZE_MISMATCH got=%d want=%d" % (size, model["bytes"]))
            print("VERIFY sha256 ...", flush=True)
            actual = sha256_file(partial)
            if actual != model["sha256"]:
                partial.unlink()
                raise SystemExit("SHA256_MISMATCH got=%s want=%s" % (actual, model["sha256"]))
            partial.replace(final)
            relabel(model, final)
            print("MODEL_READY %s %d bytes" % (final, size))
            return 0
        except Exception as error:  # try the next pinned mirror
            errors.append("%s: %s" % (source["id"], error))
            print("SOURCE_FAILED " + errors[-1], flush=True)
    raise SystemExit("ALL_SOURCES_FAILED\n" + "\n".join(errors))


if __name__ == "__main__":
    sys.exit(main())
