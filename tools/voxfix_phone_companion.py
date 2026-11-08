#!/usr/bin/env python3
"""VoxFix 手机伴生进程（pythonw 运行，全程无窗口）。

职责：
1. USB 插入   → 自动拉起可见投屏（scrcpy 窗口，无黑色命令框）
2. USB 拔出   → 投屏自动关闭（scrcpy 自退 + 兜底查杀）
3. 保活剪贴板同步：无窗口 scrcpy（无线文本通道）+ copyq_phone_sync.py 守护

插拔检测（双通道，任一可用即可）：
- 快：`adb track-devices` 事件流，设备变化毫秒级推送；行级状态机解析，
  不按帧长度切字节（Windows 下 adb 客户端可能做 CRLF 换行翻译，
  字节计数会错位，曾导致事件流整体失效）。
- 稳：每 RECONCILE_SEC 秒跑一次 `adb devices` 对账，事件流出任何问题
  （卡死/崩溃/漏帧）也能在最迟一个周期内发现插拔变化并补上动作。

慢速维护（MAINT_SEC）：剪贴板守护存活检查、无窗口 scrcpy 文本通道，
依赖 PowerShell 进程枚举，开销大故放宽周期。

单实例保护：.android/voxfix-companion/companion.pid（旧实例存活时不重复启动）。
日志：.android/voxfix-companion/companion.log
随系统自启：把 start_phone_helper.vbs 的快捷方式放进 shell:startup。
"""

import os
import queue
import subprocess
import sys
import threading
import time

SCRCPEX = (r"C:\Users\23221\AppData\Local\Microsoft\WinGet\Packages"
           r"\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe"
           r"\scrcpy-win64-v5.0.1\scrcpy.exe")
PYTHONW = (r"C:\Users\23221\.workbuddy\binaries\python"
           r"\versions\3.13.12\pythonw.exe")
WATCHER = r"D:\Projects\VoxFix\tools\copyq_phone_sync.py"
ADB = r"D:\Projects\VoxFix\.android\sdk\platform-tools\adb.exe"
USB_SERIAL = "6959dba1"
LOG_DIR = r"D:\Projects\VoxFix\.android\voxfix-companion"
PID_FILE = os.path.join(LOG_DIR, "companion.pid")
LOG_FILE = os.path.join(LOG_DIR, "companion.log")
RECONCILE_SEC = 3.0      # adb devices 对账周期（事件流的兜底）
MAINT_SEC = 10.0         # 慢速维护周期（PowerShell 枚举，开销大）
TRACK_RETRY_SEC = 2.0    # track-devices 断开后重连间隔
HEADLESS_RETRY_SEC = 30.0
CREATE_NO_WINDOW = 0x08000000


def log(msg: str) -> None:
    try:
        with open(LOG_FILE, "a", encoding="utf-8") as f:
            f.write(time.strftime("[%Y-%m-%d %H:%M:%S] ") + msg + "\n")
    except OSError:
        pass


def spawn(cmd: list) -> subprocess.Popen:
    return subprocess.Popen(cmd, creationflags=CREATE_NO_WINDOW,
                            close_fds=True)


def run_hidden(cmd: list, timeout: int = 20) -> subprocess.CompletedProcess:
    """控制台程序（adb/powershell/tasklist）一律不弹窗口。

    输出按 UTF-8 容错解码：tasklist/powershell 在中文系统上吐 GBK，
    ASCII 部分（PID/路径）不受影响，避免解码异常炸掉主线程。
    """
    return subprocess.run(cmd, capture_output=True, timeout=timeout,
                          text=True, encoding="utf-8", errors="replace",
                          creationflags=CREATE_NO_WINDOW)


def adb_devices() -> list:
    try:
        out = run_hidden([ADB, "devices"], 10).stdout
        return [line.split("\t")[0] for line in out.splitlines()
                if line.endswith("\tdevice")]
    except Exception:
        return []


def pick_serial(devices: list) -> str:
    """优先 USB 设备，其次无线设备（形如 192.168.x.x:port）。"""
    if USB_SERIAL in devices:
        return USB_SERIAL
    for serial in devices:
        if ":" in serial:
            return serial
    return ""


def track_devices_loop(snapshots: queue.Queue) -> None:
    """adb track-devices 事件流线程：设备插拔毫秒级推送。

    协议（经 adb 客户端转发后）：每帧 = 4 位十六进制长度前缀 +
    设备行（`serial\\tstate\\n`）+ 空行。Windows 客户端可能做 CRLF
    翻译导致字节计数与实际不符，因此不按帧长切字节，改为行级状态机：
    空行结束一帧；每帧首行剥掉前 4 个字符（长度前缀与内容无关）。
    adb server 重启会使进程退出 → 读到 EOF 后隔 TRACK_RETRY_SEC 重连。
    """
    while True:
        proc = None
        try:
            proc = subprocess.Popen(
                [ADB, "track-devices"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW, close_fds=True,
                bufsize=0)
            # 必须无缓冲读：read(n) 要等满 n 字节才返回，
            # 事件帧只有几十字节 → 永远阻塞收不到任何事件
            buf = b""
            expect_prefix = True   # 下一行是帧首行（带 4 位长度前缀）
            lines = []
            while True:
                chunk = os.read(proc.stdout.fileno(), 4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    raw, buf = buf.split(b"\n", 1)
                    line = raw.decode("utf-8", "replace").rstrip("\r")
                    if expect_prefix:
                        if len(line) >= 4:
                            line = line[4:]
                        expect_prefix = False
                    if line == "":
                        if lines:
                            devices = []
                            for item in lines:
                                parts = item.split("\t")
                                if len(parts) == 2 and parts[1] == "device":
                                    devices.append(parts[0])
                            snapshots.put(devices)
                        lines = []
                        expect_prefix = True
                    else:
                        lines.append(line)
        except Exception as error:
            log(f"track-devices 异常: {error}")
        finally:
            if proc is not None:
                try:
                    proc.kill()
                except Exception:
                    pass
        time.sleep(TRACK_RETRY_SEC)


def process_commands() -> dict:
    """pid -> cmdline（scrcpy.exe / python.exe / pythonw.exe）。"""
    result = {}
    try:
        out = run_hidden(
            ["powershell", "-NoProfile", "-Command",
             "Get-CimInstance Win32_Process -Filter \"Name='scrcpy.exe' "
             "OR Name='python.exe' OR Name='pythonw.exe'\" | "
             "ForEach-Object { \"$($_.ProcessId)|$($_.CommandLine)\" }"],
            15).stdout
    except Exception as error:
        log(f"进程枚举失败（跳过本轮）: {error}")
        return result
    for line in out.splitlines():
        line = line.strip()
        if "|" in line:
            pid, _, cmdline = line.partition("|")
            if pid.isdigit():
                result[int(pid)] = cmdline or ""
    return result


def visible_scrcpy_running() -> bool:
    return any("scrcpy" in (c or "") and "--no-window" not in (c or "")
               for c in process_commands().values())


def pid_alive(pid: int) -> bool:
    code = run_hidden(["tasklist", "/FI", f"PID eq {pid}"], 10).stdout
    return str(pid) in code


def single_instance() -> bool:
    try:
        old = int(open(PID_FILE).read().strip())
        if old != os.getpid() and pid_alive(old):
            return False
    except (OSError, ValueError):
        pass
    os.makedirs(LOG_DIR, exist_ok=True)
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    return True


def on_usb_plug(first: bool) -> None:
    if visible_scrcpy_running():
        log("投屏窗口已在运行 → 不重复拉起")
        return
    spawn([SCRCPEX, "-s", USB_SERIAL])
    log("USB 插入 → 已拉起投屏窗口（事件触发）" if not first
        else "启动时设备已在线 → 拉起投屏窗口")


def on_usb_unplug() -> None:
    log("USB 拔出 → 投屏随断连自动关闭")
    for pid, cmdline in process_commands().items():
        if "scrcpy" in (cmdline or "") and "--no-window" not in (cmdline or ""):
            run_hidden(["taskkill", "/PID", str(pid), "/F"], 10)
            log(f"兜底结束残留投屏进程 {pid}")


def main() -> None:
    if not single_instance():
        log("已有伴生进程在运行，本次退出")
        return
    log("伴生进程启动（track-devices 事件驱动 + 3s 对账兜底）")

    # pythonw 无 stderr：任何未捕获异常都是无声死亡，必须落日志才可诊断
    def log_sys_hook(tp, val, tb) -> None:
        log(f"主线程崩溃: {tp.__name__}: {val}")

    def log_thread_hook(args) -> None:
        log(f"线程异常 {args.exc_type.__name__}: {args.exc_value}")

    sys.excepthook = log_sys_hook
    threading.excepthook = log_thread_hook

    snapshots = queue.Queue()
    threading.Thread(target=track_devices_loop, args=(snapshots,),
                     daemon=True, name="track-devices").start()

    usb_was_online = None
    last_devices = []
    last_headless_try = 0.0
    last_maint = 0.0
    last_reconcile = 0.0
    headless_serial = ""
    while True:
        try:
            # ① 事件流快照（毫秒级）；② 对账兜底（最迟 RECONCILE_SEC）
            devices = None
            try:
                devices = snapshots.get(timeout=RECONCILE_SEC)
            except queue.Empty:
                pass
            now = time.monotonic()
            if now - last_reconcile >= RECONCILE_SEC:
                last_reconcile = now
                poll = adb_devices()
                if devices is None or poll != devices:
                    devices = poll

            if devices is not None:
                if devices != last_devices:
                    log(f"设备表变化: {devices}")
                last_devices = devices
                usb_online = USB_SERIAL in devices
                first = usb_was_online is None
                if first or usb_online != usb_was_online:
                    if usb_online:
                        on_usb_plug(first)
                    else:
                        on_usb_unplug()
                usb_was_online = usb_online

            # 慢速维护：PowerShell 枚举开销大，放宽到 MAINT_SEC
            if now - last_maint < MAINT_SEC:
                continue
            last_maint = now

            procs = process_commands()
            watcher = any("copyq_phone_sync.py" in (c or "")
                          for c in procs.values())
            if not watcher:
                spawn([PYTHONW, WATCHER])
                log("剪贴板守护未在运行 → 已拉起")

            headless_pid = next(
                (pid for pid, c in procs.items()
                 if "scrcpy" in (c or "") and "--no-window" in (c or "")), 0)

            serial = pick_serial(last_devices)

            # 设备序列号变化（USB↔无线切换/重连换端口）→ 旧通道必死，
            # 杀掉僵尸让下一轮按新序列号重拉（解决 hang 住但进程还在的情况）
            if headless_pid and headless_serial and serial and \
                    serial != headless_serial:
                run_hidden(["taskkill", "/PID", str(headless_pid), "/F"], 10)
                log(f"设备切换 {headless_serial} → {serial}，"
                    f"重启无窗口 scrcpy（旧进程 {headless_pid}）")
                headless_serial = ""
            elif headless_pid and not headless_serial and serial:
                headless_serial = serial

            if not headless_pid and serial and \
                    time.monotonic() - last_headless_try > HEADLESS_RETRY_SEC:
                last_headless_try = time.monotonic()
                spawn([SCRCPEX, "--no-window", "--no-video",
                       "--no-audio", "-s", serial])
                headless_serial = serial
                log(f"无窗口 scrcpy（文本通道）未在运行 → 已按 {serial} 拉起")
        except Exception as error:
            log(f"主循环异常（忽略继续）: {error}")


if __name__ == "__main__":
    main()
