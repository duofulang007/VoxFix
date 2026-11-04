"""Fetch and patch the pinned llama.cpp source used by the offline translator.

The translator needs the unmerged STQ1_0 kernel (llama.cpp PR #22836).  The
patches are a MIT-licensed port published by dodola/hy-mt2-android; this script
pins every byte it consumes and refuses to guess when a patch no longer applies.
"""

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "tools/translate-dependencies.json"
DOWNLOAD_DIR = ROOT / ".model-downloads"
SOURCE_ROOT = ROOT / ".android/native-source"
RECEIPT = ".voxfix-source.json"


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(url, target, expected_bytes, expected_sha):
    if target.exists() and target.stat().st_size == expected_bytes and sha256_file(target) == expected_sha:
        print("SOURCE_CACHED " + target.name)
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    if partial.exists():
        partial.unlink()
    print("DOWNLOAD " + url)
    request = urllib.request.Request(url, headers={"User-Agent": "VoxFix-translate-setup"})
    with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as handle:
        total = 0
        while True:
            block = response.read(1024 * 256)
            if not block:
                break
            handle.write(block)
            total += len(block)
            if total % (1024 * 1024) < 1024 * 256:
                print("  %d/%d bytes" % (total, expected_bytes))
    if partial.stat().st_size != expected_bytes:
        raise SystemExit("SIZE_MISMATCH %s got=%d want=%d" % (target.name, partial.stat().st_size, expected_bytes))
    actual = sha256_file(partial)
    if actual != expected_sha:
        raise SystemExit("SHA256_MISMATCH %s got=%s want=%s" % (target.name, actual, expected_sha))
    partial.replace(target)
    print("SOURCE_READY " + target.name + " " + actual)


def extract(archive, target):
    if target.exists():
        shutil.rmtree(target)
    staging = target.with_name(target.name + ".staging")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            name = member.filename
            if name.startswith("/") or ".." in Path(name).parts:
                raise SystemExit("UNSAFE_ZIP_ENTRY " + name)
        bundle.extractall(staging)
    roots = [item for item in staging.iterdir() if item.is_dir()]
    if len(roots) != 1:
        raise SystemExit("UNEXPECTED_ARCHIVE_LAYOUT")
    roots[0].replace(target)
    shutil.rmtree(staging)
    print("EXTRACTED " + str(target))


def apply_patches(source, spec, force):
    receipt_file = source / RECEIPT
    expected = {
        "commit": spec["commit"],
        "patches": [{"name": p["name"], "sha256": p["sha256"]} for p in spec["patches"]],
    }
    if receipt_file.exists() and not force:
        try:
            if json.loads(receipt_file.read_text(encoding="utf-8")) == expected:
                print("PATCHES_CACHED " + spec["commit"][:12])
                return
        except ValueError:
            pass
    patch_dir = ROOT / spec["patchDirectory"]
    for patch in spec["patches"]:
        path = patch_dir / patch["name"]
        if not path.exists():
            raise SystemExit("MISSING_PATCH " + str(path))
        actual = sha256_file(path)
        if actual != patch["sha256"]:
            raise SystemExit("PATCH_SHA256_MISMATCH %s got=%s" % (path.name, actual))
    # The source lives under .android/, which this repository ignores on purpose. git apply
    # silently skips ignored paths ("Skipped patch"), so use GNU patch, which has no such rule.
    # Each patch is checked against the tree *after* the previous one, because 0002-0005 depend
    # on the symbols introduced by 0001.
    tool = shutil.which("patch") or "patch"
    for patch in spec["patches"]:
        path = patch_dir / patch["name"]
        check = subprocess.run([tool, "-p1", "--dry-run", "--batch", "--forward", "-i", str(path)], cwd=source,
                               capture_output=True, text=True)
        if check.returncode != 0 or "FAILED" in check.stdout:
            raise SystemExit("PATCH_CONFLICT %s\n%s" % (path.name, (check.stdout + check.stderr).strip()))
        applied = subprocess.run([tool, "-p1", "--batch", "--forward", "-i", str(path)], cwd=source,
                                 capture_output=True, text=True)
        if applied.returncode != 0 or "FAILED" in applied.stdout:
            raise SystemExit("PATCH_FAILED %s\n%s" % (path.name, (applied.stdout + applied.stderr).strip()))
        print("PATCHED " + path.name)
    receipt_file.write_text(json.dumps(expected, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--force", action="store_true", help="re-extract and re-apply patches")
    args = parser.parse_args()
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    llama = manifest["llama"]
    archive = DOWNLOAD_DIR / llama["archive"]["file"]
    download(llama["archive"]["url"], archive, llama["archive"]["bytes"], llama["archive"]["sha256"])
    target = SOURCE_ROOT / ("llama.cpp-" + llama["commit"][:12])
    if args.force or not target.exists():
        extract(archive, target)
    apply_patches(target, llama, args.force)
    print("LLAMA_SOURCE_READY " + str(target))


if __name__ == "__main__":
    sys.exit(main())
