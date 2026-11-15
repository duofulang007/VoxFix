#!/usr/bin/env python3
"""生成应用内更新用的 manifest.json（每次发版运行一次）。

清单字段与 android/app/src/main/java/local/voxfix/debug/update/UpdateCore.kt 严格对应：
versionCode 必须是整数且大于旧版；apkSha256 必须是 64 位小写 hex；apkUrls 只接受 https（release 构建）。

用法：
    python tools/release_manifest.py                     # 用 named/ 下与当前版本号匹配的 APK
    python tools/release_manifest.py --apk <路径>        # 指定 APK
    python tools/release_manifest.py --mirror https://example.com/voxfix   # 追加国内镜像前缀
    python tools/release_manifest.py --changelog "..."   # 覆盖更新说明（横幅里显示，保持一两句）

产物：releases/manifest-<versionName>-<versionCode>.json
发版时必须把它重命名为 manifest.json 上传到 Release，latest/download 才能命中。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "android" / "app" / "build.gradle.kts"
NAMED = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "named"
OUT = ROOT / "releases"
OWNER_REPO = "duofulang007/VoxFix"


def read_version() -> tuple[str, int]:
    text = GRADLE.read_text(encoding="utf-8")
    name = re.search(r'appVersionName\s*=\s*"([^"]+)"', text)
    code = re.search(r"appVersionCode\s*=\s*(\d+)", text)
    if not name or not code:
        sys.exit("无法从 build.gradle.kts 读取 appVersionName/appVersionCode")
    return name.group(1), int(code.group(1))


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def pick_apk(version_name: str, version_code: int) -> Path:
    """文件名只带版本名（VoxFix-0.5.0.apk）；versionCode 只用于清单与安装器比较。"""
    target = NAMED / f"VoxFix-{version_name}.apk"
    if target.is_file():
        return target
    legacy = sorted(NAMED.glob(f"VoxFix-{version_name}-{version_code}-*.apk"), key=lambda p: p.stat().st_mtime)
    if legacy:
        return legacy[-1]
    sys.exit(f"named/ 下找不到 VoxFix-{version_name}.apk，请先 assembleDebug")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("--mirror", action="append", default=[], help="国内镜像前缀，可重复")
    parser.add_argument("--changelog", default="")
    parser.add_argument("--date", default="")
    args = parser.parse_args()

    version_name, version_code = read_version()
    apk = Path(args.apk) if args.apk else pick_apk(version_name, version_code)
    if not apk.is_file():
        sys.exit(f"APK 不存在：{apk}")

    tag = f"v{version_name}"
    github_url = f"https://github.com/{OWNER_REPO}/releases/download/{tag}/{apk.name}"
    urls = [github_url]
    for base in args.mirror:
        urls.append(f"{base.rstrip('/')}/{apk.name}")

    manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "date": args.date,
        "changelog": args.changelog,
        "apkSizeBytes": apk.stat().st_size,
        "apkSha256": sha256_of(apk),
        "apkUrls": urls,
    }

    OUT.mkdir(exist_ok=True)
    payload = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    target = OUT / f"manifest-{version_name}-{version_code}.json"
    target.write_text(payload, encoding="utf-8")
    # 客户端只认 releases/latest/download/manifest.json 这个固定地址，上传名必须是 manifest.json；
    # 这里同步写一份，避免手工复制时用到上一版的内容。
    (OUT / "manifest.json").write_text(payload, encoding="utf-8")

    size_mb = manifest["apkSizeBytes"] / (1024 * 1024)
    print(f"APK     {apk.name}")
    print(f"大小    {manifest['apkSizeBytes']} 字节（{size_mb:.1f} MB）")
    print(f"SHA256  {manifest['apkSha256']}")
    print(f"清单    {target.relative_to(ROOT)}")
    print()
    print("发版步骤：")
    print(f"  1. GitHub → Releases → Create a new release，Tag = {tag}，标题 {version_name}")
    print(f"  2. 上传附件：{apk.name}")
    print(f"  3. 上传附件：把 {target.name} 改名为 manifest.json 再上传")
    print("  4. 发布（Publish release），不要留成 draft——draft 对客户端不可见")
    print(f"  5. 手机端「关于 → 检查更新」应显示发现新版本 {version_name}")


if __name__ == "__main__":
    main()
