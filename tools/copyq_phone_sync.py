#!/usr/bin/env python3
"""手机剪贴板 ⇄ 电脑 双向搬运（VoxFix + scrcpy + CopyQ）。

电脑 → 手机（反向，2026-11-08 改为标签驱动）：
  只推送用户在 CopyQ 里**主动添加**到「手机文本」「手机图片」标签的条目，
  电脑上随意复制的内容不进手机：
  1. 文本：轮询标签条数，新增时 `copyq read` 取文本（≤10000 字符）推给手机，
     键盘剪贴板页记入历史并带「电脑」标签。
  2. 图片：CopyQ v16 的 CLI/脚本读不出二进制条目（read/copy 均返回空，实测），
     但 `exporttab` 导出文件含原始 PNG 字节——条数变化时导出并用
     PNG magic+IEND 走链提取，新图推给手机，VoxFix 解码校验后入「图片」页
     （解码失败即丢弃，图片页绝不出现文本）。
  首次观测到标签时只记基线不推送（避免把历史条目一次性灌进手机）。

手机 → 电脑（正向，原有）：
  1. 文本：scrcpy 原生自动同步把手机复制写入 Windows 剪贴板；
     仅当所有者进程是 scrcpy.exe（或无窗口模式 owner=None）时，
     写入 CopyQ「手机文本」标签。
  2. 图片：VoxFix 截图工具保存后图片落在
     /data/data/local.voxfix.debug/files/screenshot-clipboard/；
     adb run-as（debug 包免 root）轮询新文件，拉取后写入 CopyQ「手机图片」标签。

防回流/防回环：
  a. 正向通道写入标签的条目哈希记入 seen 集合，反向通道跳过（手机内容不回流）。
  b. 反向推送过的文本哈希也在 seen 集合：scrcpy 回声不进「手机文本」，只清主历史副本。
  c. 反向推送不监听 Windows 剪贴板，PC 随意复制与手机完全无关。

用法：
  python tools/copyq_phone_sync.py            # 前台运行，Ctrl+C 停止
  python tools/copyq_phone_sync.py --any-owner  # 测试用：不校验所有者进程
状态文件：.android/copyq-phone-sync/state.json（seen 集合防回流/防重复）
"""

import argparse
import atexit
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
TAB_POLL_SEC = 2.0          # 标签条数轮询间隔
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
        return {"images": [], "text_hash": "", "seen_text": [], "seen_images": []}


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
            # 回流/回声过滤：该内容已在「手机文本」标签里（正向写入或反向推送过），
            # 不重复入标签，只清理 CopyQ 主历史的副本（保持主剪贴板纯净）。
            if digest in load_state().get("seen_text", []):
                log(f"文本已在「{TAB_TEXT}」标签 → 跳过，仅清理主历史副本：{text[:40].replace(chr(10), chr(92) + 'n')}")
                copyq_main_remove_match(text)
                continue
            if copyq_write(TAB_TEXT, "text/plain", text.encode("utf-8")):
                merged = load_state()  # 与图片/推送线程共享一个文件，读-改-写防覆盖
                merged["text_hash"] = digest
                seen = merged.get("seen_text", [])
                seen.append(digest)
                merged["seen_text"] = seen[-1000:]
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
            # 电脑推送导入的图片（source=pc）不回流：它们本来就在「手机图片」标签里，
            # 回写会造成用户看到两条（原条目 + 回流副本）
            if item.get("source") == "pc":
                seen.add(key)
                merged = load_state()
                merged["images"] = sorted(seen)[-2000:]
                save_state(merged)
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
                    merged = load_state()  # 与文本/推送线程共享一个文件，读-改-写防覆盖
                    merged["images"] = sorted(seen)[-2000:]
                    # 登记内容哈希：反向通道看到该条目时不回流手机
                    imgs = merged.get("seen_images", [])
                    imgs.append(hashlib.sha256(data).hexdigest())
                    merged["seen_images"] = imgs[-2000:]
                    save_state(merged)
                    log(f"图片 → {TAB_IMAGE}（{image_id[:8]}… {kind} r{revision}，{len(data) // 1024} KB）")
            except Exception as error:
                log(f"图片 {image_id} 拉取失败（下轮重试）: {error}")


LOCK_FILE = STATE_FILE.parent / "watcher.lock"


def acquire_lock() -> bool:
    """单实例锁：伴生的 WMI 存活检测有竞态，可能拉起第二个守护；
    这里用 O_EXCL 锁文件兜底，持有者活着则新实例直接退出。"""
    STATE_FILE.parent.mkdir(parents=True, exist_ok=True)
    try:
        fd = os.open(str(LOCK_FILE), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(fd, str(os.getpid()).encode())
        os.close(fd)
        return True
    except FileExistsError:
        try:
            old = int(LOCK_FILE.read_text().strip())
            out = subprocess.run(["tasklist", "/FI", f"PID eq {old}"],
                                 capture_output=True, text=True,
                                 encoding="utf-8", errors="replace",
                                 creationflags=NO_WINDOW).stdout
            if str(old) in out:
                return False
        except (OSError, ValueError):
            pass
        try:
            LOCK_FILE.unlink()
        except OSError:
            pass
        return acquire_lock()


def main() -> None:
    parser = argparse.ArgumentParser(description="手机剪贴板 ⇄ 电脑 双向搬运")
    parser.add_argument("--any-owner", action="store_true", help="测试用：文本不校验所有者进程")
    args = parser.parse_args()
    if Path(COPYQ).is_file() is False:
        sys.exit(f"copyq 不存在: {COPYQ}")
    if not acquire_lock():
        log("已有剪贴板守护在运行，本次退出")
        return
    atexit.register(lambda: LOCK_FILE.unlink(missing_ok=True))
    threading.Thread(target=image_loop, daemon=True).start()
    threading.Thread(target=push_loop, daemon=True).start()
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
        # 整条远端命令必须是单个 argv：adb 会把多个参数用空格拼给远端 sh，
        # sh -c 的引号撑不过去（mkdir Needs 1 argument 实测）。
        remote_cmd = (f"run-as {PACKAGE} sh -c "
                      f"'mkdir -p files/{IMPORT_DIR} && cat {remote} > {target}'")
        result = subprocess.run([ADB, "-s", serial, "shell", remote_cmd],
                                capture_output=True, timeout=30,
                                creationflags=NO_WINDOW)
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


def record_seen(kind: str, digest: str) -> None:
    """把内容哈希记入对应标签的已见集合（防回流/防重复）。"""
    merged = load_state()
    key = f"seen_{kind}"
    seen = merged.get(key, [])
    seen.append(digest)
    merged[key] = seen[-2000:]
    save_state(merged)


def copyq_tab_count(tab: str) -> int | None:
    try:
        out = subprocess.run([COPYQ, "tab", tab, "count"], capture_output=True,
                             timeout=10, creationflags=NO_WINDOW)
        if out.returncode != 0:
            return None
        return int(out.stdout.decode("utf-8", "replace").strip() or "0")
    except Exception:
        return None


def copyq_tab_read(tab: str, index: int) -> bytes | None:
    try:
        out = subprocess.run([COPYQ, "tab", tab, "read", str(index)],
                             capture_output=True, timeout=10,
                             creationflags=NO_WINDOW)
        if out.returncode != 0:
            return None
        return out.stdout
    except Exception:
        return None


def copyq_tab_export(tab: str, target: str) -> bool:
    """exporttab 导出整个标签；v16 的 CLI read/copy 取不出二进制条目（实测返回空），
    导出文件里才有原始 PNG 字节。命令 rc=0 时文件是异步落盘的，需轮询等待。"""
    try:
        out = subprocess.run([COPYQ, "tab", tab, "exporttab", str(target)],
                             capture_output=True, timeout=30,
                             creationflags=NO_WINDOW)
        if out.returncode != 0:
            log(f"exporttab 失败（{tab}，path={target}）: {out.stderr.decode('utf-8', 'replace')[:150]}")
            return False
        for _ in range(15):  # 最多等 3s，文件由 CopyQ 主进程异步写出
            if os.path.isfile(target) and os.path.getsize(target) > 0:
                return True
            time.sleep(0.2)
        log(f"exporttab 后文件未出现（{target.name}），下轮重试")
        return False
    except Exception as error:
        log(f"exporttab 异常（{tab}）: {error}")
        return False


def extract_pngs(blob: bytes) -> list[bytes]:
    """按 PNG magic + IEND 走链提取所有完整 PNG。"""
    import struct
    magic = b"\x89PNG\r\n\x1a\n"
    images, i = [], 0
    while True:
        j = blob.find(magic, i)
        if j < 0:
            break
        pos = j + 8
        ok = False
        while pos + 8 <= len(blob):
            length = struct.unpack(">I", blob[pos:pos + 4])[0]
            ctype = blob[pos + 4:pos + 8]
            pos += 12 + length
            if ctype == b"IEND":
                images.append(blob[j:pos])
                ok = True
                break
            if length > len(blob):
                break
        i = pos if ok else j + 8
    return images


def push_loop() -> None:
    """CopyQ「手机文本/手机图片」标签 → 手机。只推用户主动放进标签的新条目；
    电脑上随意复制不进手机（本线程不读 Windows 剪贴板）。"""
    baseline: dict[str, int] = {}
    # 正斜杠：反斜杠会被 CopyQ 脚本解析器当转义序列吃掉（\t → TAB 实测）
    export_tmp = STATE_FILE.parent.as_posix() + "/tab-export.tmp"
    log(f"标签推送启动（只推「{TAB_TEXT}」「{TAB_IMAGE}」标签新增条目；"
        f"文本≤{PUSH_TEXT_LIMIT} 字符；首次观测只记基线不推送）")
    while True:
        time.sleep(TAB_POLL_SEC)
        # ---- 文本标签 ----
        try:
            count = copyq_tab_count(TAB_TEXT)
            if count is not None and baseline.get(TAB_TEXT) != count:
                first = TAB_TEXT not in baseline
                state = load_state()
                seen = set(state.get("seen_text", []))
                all_ok = True
                for i in range(count):
                    data = copyq_tab_read(TAB_TEXT, i)
                    if not data:
                        continue
                    digest = hashlib.sha256(data).hexdigest()
                    if digest in seen:
                        continue
                    if first:
                        seen.add(digest)  # 基线：已有条目只登记不推送
                        continue
                    text = data.decode("utf-8", "replace")
                    truncated = len(text) > PUSH_TEXT_LIMIT
                    payload = text[:PUSH_TEXT_LIMIT] if truncated else text
                    name = f"pc-{int(time.time() * 1000)}.txt"
                    if push_file_to_phone(name, payload.encode("utf-8"), ACTION_IMPORT_TEXT):
                        seen.add(digest)
                        record_seen("text", digest)
                        preview = payload[:40].replace("\n", "\\n")
                        log(f"「{TAB_TEXT}」第 {i} 条 → 手机「文本」页（{len(payload)} 字符"
                            f"{'，已截断' if truncated else ''}）：{preview}")
                    else:
                        all_ok = False  # 失败不推进基线，下轮重试
                if first:
                    save_state({**load_state(), "seen_text": list(seen)[-1000:]})
                    log(f"「{TAB_TEXT}」基线 {count} 条已登记（不推送）")
                if all_ok:
                    baseline[TAB_TEXT] = count
        except Exception as error:
            log(f"文本标签推送异常（忽略继续）: {error}")
        # ---- 图片标签 ----
        try:
            count = copyq_tab_count(TAB_IMAGE)
            if count is not None and baseline.get(TAB_IMAGE) != count:
                first = TAB_IMAGE not in baseline
                all_ok = True
                # 每轮处理一个新条目：推送 + 规范化（写回标准 image/png 条目、删原
                # 自定义格式条目，消除 CopyQ 的 <数据> 显示）。规范化会增删条目，
                # 所以重复导出重扫，直到没有新哈希。
                for _ in range(count + 4):
                    if not copyq_tab_export(TAB_IMAGE, export_tmp):
                        all_ok = False
                        break
                    state = load_state()
                    seen = set(state.get("seen_images", []))
                    target = None
                    for pos, data in enumerate(extract_pngs(open(export_tmp, "rb").read())):
                        digest = hashlib.sha256(data).hexdigest()
                        if digest in seen:
                            continue
                        if first:
                            seen.add(digest)  # 基线：已有条目只登记不推送
                            record_seen("images", digest)
                            continue
                        target = (pos, data, digest)
                        break
                    if target is None:
                        if first:
                            log(f"「{TAB_IMAGE}」基线已登记（不推送）")
                        break
                    pos, data, digest = target
                    if len(data) > PUSH_MAX_IMAGE:
                        log(f"图片过大不推送（{len(data) // 1024} KB），与手机图片页上限一致")
                        seen.add(digest)
                        record_seen("images", digest)
                        continue
                    name = f"pc-{int(time.time() * 1000)}.png"
                    if not push_file_to_phone(name, data, ACTION_IMPORT_IMAGE):
                        all_ok = False  # 失败不登记 seen，下轮重试
                        break
                    seen.add(digest)
                    record_seen("images", digest)
                    # 规范化为标准条目：write 插到顶部（已实测），原条目移到 pos+1
                    write = subprocess.run([COPYQ, "tab", TAB_IMAGE, "write", "image/png", "-"],
                                           input=data, capture_output=True, timeout=30,
                                           creationflags=NO_WINDOW)
                    if write.returncode != 0:
                        all_ok = False
                        log(f"「{TAB_IMAGE}」新图已推手机，但规范化写入失败（下轮重试）")
                        break
                    remove = subprocess.run([COPYQ, "tab", TAB_IMAGE, "remove", str(pos + 1)],
                                            capture_output=True, timeout=15,
                                            creationflags=NO_WINDOW)
                    if remove.returncode != 0:
                        all_ok = False
                        log(f"「{TAB_IMAGE}」新图已推手机，但移除原 <数据> 条目失败"
                            f"（index {pos + 1}）")
                        break
                    log(f"「{TAB_IMAGE}」新图 → 手机「图片」页（{len(data) // 1024} KB），"
                        f"原 <数据> 条目已转为标准图片条目")
                if all_ok:
                    baseline[TAB_IMAGE] = copyq_tab_count(TAB_IMAGE) or count
        except Exception as error:
            log(f"图片标签推送异常（忽略继续）: {error}")


if __name__ == "__main__":
    main()
