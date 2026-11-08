#!/usr/bin/env python3
"""手机剪贴板 ⇄ 电脑 双向搬运（VoxFix + scrcpy + CopyQ）。

电脑 → 手机（反向，2026-11-08 新增）：
  监听 Windows 剪贴板，仅当所有者进程不是 scrcpy（即用户在电脑上复制）时：
  1. 文本：写入手机 files/clipboard-import/ 后发 IMPORT_TEXT 广播，
     VoxFix 接收器写系统剪贴板（描述标签「VoxFix · 电脑」），键盘剪贴板页
     自动记入历史并带「电脑」标签；单条上限 10000 字符（与键盘历史一致）。
  2. 图片：经 Pillow 取出位图转 PNG 推送，VoxFix 解码校验后入「图片」页；
     解码失败（文本混入）一律丢弃——手机图片页绝不出现文本。

手机 → 电脑（正向，原有）：
  1. 文本：scrcpy 原生自动同步把手机复制写入 Windows 剪贴板；
     本脚本监听剪贴板变化，仅当所有者进程是 scrcpy.exe（或无窗口模式 owner=None）时，
     把文本写入 CopyQ「手机文本」标签。
  2. 图片：VoxFix 截图工具保存后图片落在
     /data/data/local.voxfix.debug/files/screenshot-clipboard/<32hex>.png；
     本脚本用 adb run-as（debug 包免 root）轮询新文件，
     拉取后写入 CopyQ「手机图片」标签。

防回环（三层）：
  a. 来源判定：反向推送只认"非 scrcpy 来源"；正向搬运只认 scrcpy/None 来源。
  b. 回声过滤：反向推送过的文本哈希记录在 state，scrcpy 把手机剪贴板同步回来时
     不进「手机文本」标签，只清理 CopyQ 主历史里的回声副本。
  c. 图片不进系统剪贴板（直接入 ScreenshotStore），无回声路径。

用法：
  python tools/copyq_phone_sync.py            # 前台运行，Ctrl+C 停止
  python tools/copyq_phone_sync.py --any-owner  # 测试用：不校验所有者进程
状态文件：.android/copyq-phone-sync/state.json（已搬运图片 id、推送哈希记录，防重复/防回环）
"""

import argparse
import ctypes
import ctypes.wintypes as wt
import hashlib
import io
import json
import os
import subprocess
import sys
import threading
import time
from pathlib import Path

# ---- 配置（按本机实际情况调整） ----
COPYQ = r"D:\CopyQ\CopyQ\copyq.exe"
ADB = r"D:\Projects\VoxFix\.android\sdk\platform-tools\adb.exe"
SERIAL = "6959dba1"          # USB 序列号（优先）
WIFI_PREFIX = "192.168.2.40"  # 无线调试地址前缀（adb connect 192.168.2.40:<port>）
PACKAGE = "local.voxfix.debug"
STORE_DIR = "files/screenshot-clipboard"
TAB_TEXT = "手机文本"
TAB_IMAGE = "手机图片"
ALLOWED_OWNERS = {"scrcpy.exe"}
TEXT_POLL_MS = 150
IMAGE_POLL_SEC = 2.0
# 反向推送
IMPORT_DIR = "clipboard-import"
PUSH_TEXT_LIMIT = 10000     # 与 VoxFix 键盘剪贴板历史单条上限一致
PUSH_MAX_IMAGE = 16 * 1024 * 1024  # 与 ScreenshotStore 单图上限一致
PUSH_ECHO_SEC = 600         # 反向推送文本的回声判定窗口
STATE_FILE = Path(__file__).resolve().parent.parent / ".android" / "copyq-phone-sync" / "state.json"

# VoxFix ClipboardImportReceiver 的广播 action（与 android 端常量一一对应）
ACTION_IMPORT_TEXT = "local.voxfix.debug.IMPORT_TEXT"
ACTION_IMPORT_IMAGE = "local.voxfix.debug.IMPORT_IMAGE"

user32 = ctypes.WinDLL("user32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)

# 从 pythonw（无控制台）启动时，adb/copyq 这类控制台程序必须禁止新建窗口
NO_WINDOW = 0x08000000 if os.name == "nt" else 0


def log(msg: str) -> None:
    line = time.strftime("[%H:%M:%S] ") + msg
    if sys.stdout is not None:  # pythonw 下 stdout 为 None，禁止 print
        try:
            print(line, flush=True)
        except Exception:
            pass
    try:
        STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(STATE_FILE.parent / "watcher.log", "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except OSError:
        pass


def load_state() -> dict:
    try:
        return json.loads(STATE_FILE.read_text("utf-8"))
    except Exception:
        return {"images": [], "text_hash": "", "pushed_text": {}, "pushed_image_hash": ""}


def save_state(state: dict) -> None:
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = STATE_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False), "utf-8")
    tmp.replace(STATE_FILE)


def copyq_main_remove_match(text: str) -> bool:
    """删除 CopyQ 主历史（默认首个标签）里手机文本的同内容副本。

    手机文本经 scrcpy 写入系统剪贴板，CopyQ 会自动记账进主历史；
    路由到「手机文本」后把主历史里那条副本移除，保持主剪贴板纯净。
    CopyQ 记账是异步的，故重试数轮。
    """
    target = text.rstrip("\r\n")
    for attempt in range(4):
        if attempt:
            time.sleep(0.5)
        try:
            out = subprocess.run([COPYQ, "read", "0"], capture_output=True,
                                 timeout=10, creationflags=NO_WINDOW)
            item = out.stdout.decode("utf-8", "replace").rstrip("\r\n")
            if item == target:
                subprocess.run([COPYQ, "remove", "0"], capture_output=True,
                               timeout=10, creationflags=NO_WINDOW)
                return True
        except Exception:
            pass
    return False


def copyq_write(tab: str, mime: str, data: bytes) -> bool:
    """copyq tab <tab> write <mime> - ；经实测不触碰系统剪贴板。"""
    try:
        r = subprocess.run([COPYQ, "tab", tab, "write", mime, "-"],
                           input=data, capture_output=True, timeout=20,
                           creationflags=NO_WINDOW)
        if r.returncode != 0:
            log(f"copyq 写入失败({tab}): rc={r.returncode} {r.stderr.decode('utf-8', 'replace')[:200]}")
            return False
        return True
    except FileNotFoundError:
        log("copyq 不存在或未运行，跳过本次（剪贴板本体不受影响）")
        return False
    except subprocess.TimeoutExpired:
        log("copyq 超时，跳过本次")
        return False


# ---------------- 文本通道 ----------------
CF_UNICODETEXT = 13
GMEM_MOVEABLE = 0x0002
WM_CLIPBOARDUPDATE = 0x031D

user32.GetClipboardSequenceNumber.restype = wt.DWORD
user32.OpenClipboard.wargtypes = [wt.HWND]
user32.GetClipboardData.restype = wt.HANDLE
user32.GetClipboardOwner.restype = wt.HWND
kernel32.GlobalLock.restype = wt.LPVOID
kernel32.GlobalLock.argtypes = [wt.HGLOBAL]
kernel32.GlobalUnlock.argtypes = [wt.HGLOBAL]
kernel32.GlobalUnlock.argtypes = [wt.HGLOBAL]
kernel32.QueryFullProcessImageNameW.argtypes = [wt.HANDLE, wt.DWORD, wt.LPWSTR, ctypes.POINTER(wt.DWORD)]
PROCESS_QUERY_LIMITED_INFORMATION = 0x1000


def owner_process_name() -> str | None:
    hwnd = user32.GetClipboardOwner()
    if not hwnd:
        return None
    pid = wt.DWORD(0)
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    if not pid.value:
        return None
    h = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, False, pid.value)
    if not h:
        return None
    try:
        size = wt.DWORD(512)
        buf = ctypes.create_unicode_buffer(512)
        if not kernel32.QueryFullProcessImageNameW(h, 0, buf, ctypes.byref(size)):
            return None
        return Path(buf.value).name.lower()
    finally:
        kernel32.CloseHandle(h)


def read_clipboard_text() -> str | None:
    if not user32.OpenClipboard(None):
        return None
    try:
        h = user32.GetClipboardData(CF_UNICODETEXT)
        if not h:
            return None
        p = kernel32.GlobalLock(h)
        if not p:
            return None
        try:
            return ctypes.wstring_at(p)
        finally:
            kernel32.GlobalUnlock(h)
    finally:
        user32.CloseClipboard()


def text_loop(any_owner: bool) -> None:
    state = load_state()
    last_seq = user32.GetClipboardSequenceNumber()
    log(f"文本监听启动（轮询 {TEXT_POLL_MS}ms，来源过滤={'关(测试)' if any_owner else 'scrcpy.exe'}）")
    while True:
        time.sleep(TEXT_POLL_MS / 1000.0)
        try:
            seq = user32.GetClipboardSequenceNumber()
            if seq == last_seq:
                continue
            last_seq = seq
            owner = owner_process_name()
            # 无窗口 scrcpy（--no-window）写剪贴板没有所有者窗口（OpenClipboard(NULL)），owner 为 None
            if not any_owner and owner is not None and owner not in ALLOWED_OWNERS:
                continue
            text = read_clipboard_text()
            if not text:
                continue
            digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
            if digest == state.get("text_hash"):
                log("文本与上次相同 → 去重跳过（CopyQ 主历史仍会记账）")
                continue  # 连续相同内容去重
            # 回声过滤：最近由本脚本推送到手机的文本，经 scrcpy 同步回来时不进「手机文本」，
            # 只清理 CopyQ 主历史里的回声副本（保持主历史纯净，也避免电脑→手机→电脑假手机来源）。
            pushed = load_state().get("pushed_text", {})
            pushed_at = pushed.get(digest)
            if pushed_at and time.time() - pushed_at < PUSH_ECHO_SEC:
                log(f"电脑推送回声 → 跳过「{TAB_TEXT}」，仅清理主历史副本：{text[:40].replace(chr(10), chr(92) + 'n')}")
                copyq_main_remove_match(text)
                continue
            if copyq_write(TAB_TEXT, "text/plain", text.encode("utf-8")):
                merged = load_state()  # 与图片线程共享一个文件，读-改-写防覆盖
                merged["text_hash"] = digest
                save_state(merged)
                preview = text[:40].replace("\n", "\\n")
                log(f"文本 → {TAB_TEXT}（{len(text)} 字符，来自 {owner}）：{preview}")
                if copyq_main_remove_match(text):
                    log("已从主剪贴板历史移除同内容副本")
        except Exception as error:  # 剪贴板被占用等，下一次循环再试
            log(f"文本通道异常（忽略继续）: {error}")


# ---------------- 图片通道 ----------------
def adb(*args: str, binary: bool = False) -> bytes | str:
    result = subprocess.run([ADB, "-s", get_serial(), *args],
                            capture_output=True, timeout=30,
                            creationflags=NO_WINDOW)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.decode("utf-8", "replace").strip()[:200])
    return result.stdout if binary else result.stdout.decode("utf-8", "replace")


_serial_cache = {"value": None, "checked": 0.0}


def get_serial() -> str:
    """优先 USB（6959dba1），不在则用无线（192.168.2.40:<port>）；每 10s 最多刷新一次。"""
    now = time.monotonic()
    if _serial_cache["value"] and now - _serial_cache["checked"] < 10:
        return _serial_cache["value"]
    _serial_cache["checked"] = now
    try:
        out = subprocess.run([ADB, "devices"], capture_output=True, timeout=10,
                             text=True, creationflags=NO_WINDOW).stdout
    except Exception:
        out = ""
    online = [line.split("\t")[0] for line in out.splitlines()
              if "\tdevice" in line]
    chosen = next((s for s in online if s == SERIAL), None) \
        or next((s for s in online if s.startswith(WIFI_PREFIX)), None) \
        or (online[0] if online else None)
    if chosen != _serial_cache["value"]:
        log(f"adb 设备切换：{_serial_cache['value']} → {chosen or '无（等待连接）'}")
    _serial_cache["value"] = chosen
    return chosen or SERIAL


def image_loop() -> None:
    state = load_state()
    # 键为 "<id>:<revision>"；兼容旧版纯 id 记录（旧记录都是 IMAGE，revision 0）
    seen = {k if ":" in k else f"{k}:0" for k in state.get("images", [])}
    failures = 0
    log(f"图片轮询启动（{IMAGE_POLL_SEC}s，按 index.json，{PACKAGE}:{STORE_DIR}）")
    while True:
        time.sleep(IMAGE_POLL_SEC)
        try:
            raw = adb("shell", "run-as", PACKAGE, "cat", f"{STORE_DIR}/index.json")
            items = json.loads(raw).get("items", [])
        except Exception as error:
            failures += 1
            if failures % 10 == 1:
                log(f"图片轮询：设备不可达或索引损坏（{error}），继续重试")
            continue
        failures = 0
        for item in items:
            image_id = item.get("id", "")
            revision = item.get("revision", 0)
            kind = item.get("kind", "IMAGE")
            key = f"{image_id}:{revision}"
            if not image_id or key in seen:
                continue
            # PROJECT 的成品在 projects/<id>/<rev>/composite.png；IMAGE 在 <id>.png
            rel = (f"projects/{image_id}/{revision}/composite.png"
                   if kind == "PROJECT" else f"{image_id}.png")
            try:
                data = adb("exec-out", "run-as", PACKAGE, "cat", f"{STORE_DIR}/{rel}", binary=True)
                if data[:8] != b"\x89PNG\r\n\x1a\n":
                    log(f"图片 {image_id} 非 PNG 内容，跳过（{rel}）")
                    seen.add(key)
                    merged = load_state()
                    merged["images"] = sorted(seen)[-2000:]
                    save_state(merged)
                    continue
                if copyq_write(TAB_IMAGE, "image/png", data):
                    seen.add(key)
                    merged = load_state()  # 与文本线程共享一个文件，读-改-写防覆盖
                    merged["images"] = sorted(seen)[-2000:]
                    save_state(merged)
                    log(f"图片 → {TAB_IMAGE}（{image_id[:8]}… {kind} r{revision}，{len(data) // 1024} KB）")
            except Exception as error:
                log(f"图片 {image_id} 拉取失败（下轮重试）: {error}")


def main() -> None:
    parser = argparse.ArgumentParser(description="手机剪贴板 ⇄ 电脑 双向搬运")
    parser.add_argument("--any-owner", action="store_true", help="测试用：文本不校验所有者进程")
    args = parser.parse_args()
    if Path(COPYQ).is_file() is False:
        sys.exit(f"copyq 不存在: {COPYQ}")
    threading.Thread(target=image_loop, daemon=True).start()
    threading.Thread(target=push_loop, args=(args.any_owner,), daemon=True).start()
    try:
        text_loop(args.any_owner)
    except KeyboardInterrupt:
        log("已停止")


# ---------------- 反向通道：电脑 → 手机 ----------------
def push_file_to_phone(name: str, data: bytes, action: str) -> bool:
    """内容经本地临时文件 → /data/local/tmp → 应用私有目录，再广播通知 VoxFix 处理。

    adb push 的文件权限（644）允许应用 uid 读取，run-as cat 复制后即删中转文件；
    全程真实文件，不走 stdin/base64，规避 CRLF 破坏，文本与图片同一套路径。
    """
    serial = get_serial()
    remote = f"/data/local/tmp/voxfix-import-{name}"
    target = f"files/{IMPORT_DIR}/{name}"
    tmp = STATE_FILE.parent / f"push-{name}"
    try:
        tmp.write_bytes(data)
        subprocess.run([ADB, "-s", serial, "push", str(tmp), remote],
                       capture_output=True, timeout=60,
                       creationflags=NO_WINDOW)
        result = subprocess.run(
            [ADB, "-s", serial, "shell", "run-as", PACKAGE, "sh", "-c",
             f"mkdir -p files/{IMPORT_DIR} && cat {remote} > {target}"],
            capture_output=True, timeout=30, creationflags=NO_WINDOW)
        if result.returncode != 0:
            log(f"推送落盘失败（{name}）: {result.stderr.decode('utf-8', 'replace')[:200]}")
            return False
        broadcast = subprocess.run(
            [ADB, "-s", serial, "shell", "am", "broadcast", "-a", action,
             "-p", PACKAGE, "--es", "name", name],
            capture_output=True, timeout=30, creationflags=NO_WINDOW)
        out = broadcast.stdout.decode("utf-8", "replace")
        if broadcast.returncode != 0 or "Broadcast completed" not in out:
            log(f"导入广播未送达（{name}）: {out[:120]} {broadcast.stderr.decode('utf-8', 'replace')[:120]}")
            return False
        return True
    finally:
        subprocess.run([ADB, "-s", serial, "shell", "rm", "-f", remote],
                       capture_output=True, timeout=15, creationflags=NO_WINDOW)
        try:
            tmp.unlink()
        except OSError:
            pass


def record_pushed_text(digest: str) -> None:
    merged = load_state()
    pushed = merged.get("pushed_text", {})
    pushed[digest] = time.time()
    now = time.time()
    pushed = {key: value for key, value in pushed.items()
              if now - value < PUSH_ECHO_SEC * 6}
    merged["pushed_text"] = dict(sorted(pushed.items(), key=lambda kv: kv[1])[-50:])
    save_state(merged)


def push_loop(any_owner: bool) -> None:
    """电脑剪贴板 → 手机。scrcpy/无来源（手机回传）一律跳过，这是第一层防回环。"""
    try:
        from PIL import Image, ImageGrab  # type: ignore
        pillow_ready = True
    except Exception:
        pillow_ready = False
        log("未安装 Pillow，图片推送关闭（pip install pillow 后重启脚本生效）；文本推送不受影响")
    last_seq = user32.GetClipboardSequenceNumber()
    log(f"电脑→手机推送启动（文本≤{PUSH_TEXT_LIMIT} 字符，图片={'开' if pillow_ready else '关'}，"
        f"来源过滤={'关(测试)' if any_owner else '跳过 scrcpy/无来源'}）")
    while True:
        time.sleep(TEXT_POLL_MS / 1000.0)
        try:
            seq = user32.GetClipboardSequenceNumber()
            if seq == last_seq:
                continue
            last_seq = seq
            owner = owner_process_name()
            if not any_owner and (owner is None or owner in ALLOWED_OWNERS):
                continue  # 手机回传路径，归 text_loop 处理
            image: "Image.Image | None" = None
            if pillow_ready:
                grabbed = ImageGrab.grabclipboard()
                if isinstance(grabbed, Image.Image):
                    image = grabbed
                elif isinstance(grabbed, list):
                    log(f"复制的是文件列表（{len(grabbed)} 项），推送通道不支持，跳过")
                    continue
            if image is not None:
                buffer = io.BytesIO()
                image.save(buffer, "PNG")
                data = buffer.getvalue()
                digest = hashlib.sha256(data).hexdigest()
                state = load_state()
                if digest == state.get("pushed_image_hash"):
                    continue  # 同一张图重复复制，跳过
                if len(data) > PUSH_MAX_IMAGE:
                    log(f"图片过大不推送（{len(data) // 1024} KB > {PUSH_MAX_IMAGE // 1024} KB），手机图片页上限一致")
                    continue
                name = f"pc-{int(time.time() * 1000)}.png"
                if push_file_to_phone(name, data, ACTION_IMPORT_IMAGE):
                    merged = load_state()
                    merged["pushed_image_hash"] = digest
                    save_state(merged)
                    log(f"图片 → 手机「图片」页（{image.width}×{image.height}，{len(data) // 1024} KB，来自 {owner}）")
                continue
            text = read_clipboard_text()
            if not text:
                continue
            digest = hashlib.sha256(text.encode("utf-8")).hexdigest()
            state = load_state()
            if state.get("pushed_text", {}).get(digest):
                continue  # 刚推送过又出现在电脑剪贴板（回声落点），跳过
            truncated = len(text) > PUSH_TEXT_LIMIT
            payload = text[:PUSH_TEXT_LIMIT] if truncated else text
            name = f"pc-{int(time.time() * 1000)}.txt"
            if push_file_to_phone(name, payload.encode("utf-8"), ACTION_IMPORT_TEXT):
                record_pushed_text(digest)
                preview = payload[:40].replace("\n", "\\n")
                log(f"文本 → 手机「文本」页（{len(payload)} 字符{'，已截断' if truncated else ''}，来自 {owner}）：{preview}")
        except Exception as error:  # 剪贴板被占用、adb 掉线等，下一轮再试
            log(f"推送通道异常（忽略继续）: {error}")


if __name__ == "__main__":
    main()
