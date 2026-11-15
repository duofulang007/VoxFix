#!/usr/bin/env python3
"""把当前版本的 APK + manifest.json 发布成 GitHub Release。

设计要点：
- 默认只创建 draft 并上传、校验，不自动发布；确认无误后加 --publish。
- 上传后逐个核对附件的 name / size / state，任何不符直接失败，避免"半成品 Release"被当正式版。
- 凭证优先取 GH_TOKEN 环境变量，否则用 git credential manager 里的 GitHub 凭证（不落盘、不打印）。
- 网络：默认直连。uploads.github.com 经本机代理会因 schannel 证书吊销检查失败，实测直连可用；
  需要代理时显式设 VOXFIX_PROXY。

用法：
    python tools/publish_release.py                  # 建 draft + 上传 + 校验
    python tools/publish_release.py --publish        # 校验通过后正式发布
    python tools/publish_release.py --reuse          # 复用同名 draft（补传/重传）
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELEASES = ROOT / "releases"
NAMED = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "named"
OWNER_REPO = "duofulang007/VoxFix"
PROXY = os.environ.get("VOXFIX_PROXY", "").strip()
CURL = ["curl", "-sS", "--ssl-no-revoke"]


def token() -> str:
    env = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if env:
        return env.strip()
    proc = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    for line in proc.stdout.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    sys.exit("取不到 GitHub 凭证：请设置 GH_TOKEN 或先 git push 登录一次")


def run(cmd: list[str]) -> tuple[str, str]:
    """跑 curl，返回 (stdout, stderr)。Windows 下 curl 的错误信息是本地编码，容错解码。"""
    if PROXY:
        cmd[1:1] = ["--proxy", PROXY]
    out = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    return out.stdout or "", out.stderr or ""


def api(method: str, path: str, tok: str, payload: dict | None = None, timeout: int = 60) -> dict:
    url = path if path.startswith("http") else f"https://api.github.com{path}"
    cmd = CURL + [
        "-X", method, url,
        "-H", f"Authorization: token {tok}",
        "-H", "Accept: application/vnd.github+json",
        "-H", "User-Agent: VoxFix-release",
        "-w", "\n__HTTP__%{http_code}",
        "--max-time", str(timeout),
    ]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json", "--data-binary", json.dumps(payload, ensure_ascii=False)]
    stdout, stderr = run(cmd)
    body, _, code = stdout.rpartition("\n__HTTP__")
    code = code.strip() or "000"
    if not code.startswith("2"):
        sys.exit(f"{method} {url} 失败 HTTP {code}\n{body[:600]}\n{stderr[:400]}")
    return json.loads(body) if body.strip() else {}


def try_get(path: str, tok: str) -> dict | None:
    url = f"https://api.github.com{path}"
    cmd = CURL + ["-X", "GET", url, "-H", f"Authorization: token {tok}",
                  "-H", "Accept: application/vnd.github+json", "-w", "\n__HTTP__%{http_code}", "--max-time", "30"]
    stdout, _ = run(cmd)
    body, _, code = stdout.rpartition("\n__HTTP__")
    if not (code.strip() or "").startswith("2"):
        return None
    return json.loads(body) if body.strip() else None


def upload(asset: Path, release_id: int, tok: str) -> dict:
    size_mb = asset.stat().st_size / (1024 * 1024)
    print(f"  上传 {asset.name}（{size_mb:.1f} MB）…", flush=True)
    url = (
        f"https://uploads.github.com/repos/{OWNER_REPO}/releases/{release_id}/assets"
        f"?name={urllib.parse.quote(asset.name)}"
    )
    cmd = CURL + [
        "-X", "POST", url,
        "-H", f"Authorization: token {tok}",
        "-H", "Content-Type: application/octet-stream",
        "-H", "User-Agent: VoxFix-release",
        "--data-binary", f"@{asset}",
        "-w", "\n__HTTP__%{http_code}",
        "--max-time", "5400",
    ]
    stdout, stderr = run(cmd)
    body, _, code = stdout.rpartition("\n__HTTP__")
    code = code.strip() or "000"
    if not code.startswith("2"):
        sys.exit(f"上传 {asset.name} 失败 HTTP {code}\n{body[:600]}\n{stderr[:400]}")
    data = json.loads(body)
    print(f"    → state={data.get('state')} size={data.get('size')} id={data.get('id')}")
    return data


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag", default="")
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("--reuse", action="store_true", help="复用同名 draft Release")
    args = parser.parse_args()

    manifest_file = RELEASES / "manifest.json"
    if not manifest_file.is_file():
        sys.exit("releases/manifest.json 不存在，先跑 tools/release_manifest.py")
    manifest = json.loads(manifest_file.read_text(encoding="utf-8"))
    version_name = manifest["versionName"]
    version_code = manifest["versionCode"]
    tag = args.tag or f"v{version_name}"

    apk_name = Path(urllib.parse.urlparse(manifest["apkUrls"][0]).path).name
    apk = NAMED / apk_name
    if not apk.is_file():
        sys.exit(f"APK 不存在：{apk}")

    # 前提校验：清单里的 hash/大小必须与真实文件一致，否则发出去必然装不上
    digest = hashlib.sha256()
    with apk.open("rb") as handle:
        for chunk in iter(lambda: handle.read(8 * 1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != manifest["apkSha256"]:
        sys.exit("清单 sha256 与 APK 不符，拒绝发布")
    if apk.stat().st_size != manifest["apkSizeBytes"]:
        sys.exit("清单 apkSizeBytes 与 APK 不符，拒绝发布")

    tok = token()
    me = api("GET", "/user", tok)
    print(f"账号 {me.get('login')} · 仓库 {OWNER_REPO} · tag {tag} · versionCode {version_code}")

    notes_file = RELEASES / f"RELEASE-{version_name}.md"
    notes = notes_file.read_text(encoding="utf-8") if notes_file.is_file() else manifest.get("changelog", "")

    release = try_get(f"/repos/{OWNER_REPO}/releases/tags/{tag}", tok) if args.reuse else None
    if release is None:
        release = api("POST", f"/repos/{OWNER_REPO}/releases", tok, {
            "tag_name": tag,
            "target_commitish": "main",
            "name": version_name,
            "body": notes,
            "draft": True,
            "prerelease": False,
        })
        print(f"已创建 draft Release id={release['id']}")
    else:
        print(f"复用已有 Release id={release['id']}（draft={release.get('draft')}）")

    have = {a["name"]: a for a in release.get("assets", [])}
    for asset in (manifest_file, apk):
        if asset.name in have and have[asset.name].get("size") == asset.stat().st_size:
            print(f"  已存在且大小一致，跳过 {asset.name}")
            continue
        data = upload(asset, release["id"], tok)
        if data.get("size") != asset.stat().st_size:
            sys.exit(f"{asset.name} 上传后大小不一致：{data.get('size')} != {asset.stat().st_size}")

    final = api("GET", f"/repos/{OWNER_REPO}/releases/{release['id']}", tok, timeout=30)
    names = {a["name"]: a for a in final.get("assets", [])}
    print("附件核对：")
    ok = True
    for asset in (manifest_file, apk):
        info = names.get(asset.name)
        if not info:
            print(f"  ✗ {asset.name} 缺失")
            ok = False
            continue
        good = info["size"] == asset.stat().st_size and info["state"] == "uploaded"
        ok = ok and good
        print(f"  {'✓' if good else '✗'} {asset.name} state={info['state']} size={info['size']}")
    if not ok:
        sys.exit("附件校验不通过，未发布")
    print(f"清单地址：https://github.com/{OWNER_REPO}/releases/download/{tag}/manifest.json")

    if args.publish:
        api("PATCH", f"/repos/{OWNER_REPO}/releases/{release['id']}", tok, {"draft": False})
        print(f"已发布：https://github.com/{OWNER_REPO}/releases/tag/{tag}")
    else:
        print("当前仍是 draft。确认无误后加 --publish 正式发布。")


if __name__ == "__main__":
    main()
