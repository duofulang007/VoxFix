"""Download and hash-check pinned offline OCR candidates into ignored test storage."""
import hashlib
import json
import re
import shutil
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "80b427d69bfc5b91a12913394875910296c6f315"
DEST = ROOT / ".android/screen-ocr-eval-20261103"


def download(opener, url, target, expected=None):
    if target.exists() and expected and hashlib.sha256(target.read_bytes()).hexdigest() == expected:
        return
    temporary = target.with_suffix(target.suffix + ".part")
    try:
        with opener.open(url, timeout=60) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
        if expected and digest != expected:
            raise ValueError(f"SHA256 mismatch for {target.name}: {digest}")
        temporary.replace(target)
    finally:
        temporary.unlink(missing_ok=True)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    # This build host uses a closed loopback proxy; no proxy is needed for public downloads.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f"https://raw.githubusercontent.com/RapidAI/RapidOCR/{REVISION}/"
    download(opener, base + "python/rapidocr/default_models.yaml", DEST / "models.yaml")
    download(opener, base + "LICENSE", DEST / "RapidOCR-LICENSE")
    text = (DEST / "models.yaml").read_text(encoding="utf-8").split("openvino:", 1)[0]
    manifest = {"rapidocr_revision": REVISION, "models": []}
    for kind in ("small", "medium"):
        match = re.search(r"multi_PP-OCRv6_rec_" + kind + r":\s+model_dir: (\S+)\s+SHA256: ([0-9a-f]{64})", text)
        if not match:
            raise ValueError(f"Pinned upstream missing {kind}")
        url, digest = match.groups()
        target = DEST / f"PP-OCRv6_rec_{kind}.onnx"
        download(opener, url, target, digest)
        manifest["models"].append(dict(name=kind, url=url, sha256=digest, bytes=target.stat().st_size))
        print(f"{kind}: {target.stat().st_size:,} bytes, SHA256 verified", flush=True)
    (DEST / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    runtime = "https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.20.0/onnxruntime-android-1.20.0.aar"
    download(opener, runtime, DEST / "onnxruntime-android-1.20.0.aar", "07a8f71ef890afed8c6087a56220e6d558a492804276ee2dd7cb7f6262242027")
    assets = DEST / "assets"
    assets.mkdir(exist_ok=True)
    for model in manifest["models"]:
        source = DEST / f"PP-OCRv6_rec_{model['name']}.onnx"
        target = assets / source.name
        if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest() != model["sha256"]:
            shutil.copyfile(source, target)
    print("Offline test assets ready; production APK unchanged.")


if __name__ == "__main__":
    main()
