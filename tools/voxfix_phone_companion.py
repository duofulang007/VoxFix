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

import msvcrt
import os
import queue
import socket
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
LOCK_FILE = os.path.join(LOG_DIR, "companion.lock")   # 单实例锁；与 pid 分开，pid 保持可读
LOG_FILE = os.path.join(LOG_DIR, "companion.log")
_lock_file = None          # 单实例文件锁句柄，存活期间一直持有
RECONCILE_SEC = 3.0      # adb devices 对账周期（事件流的兜底）
MAINT_SEC = 10.0         # 慢速维护周期（PowerShell 枚举，开销大）
TRACK_RETRY_SEC = 2.0    # track-devices 断开后重连间隔
HEADLESS_RETRY_SEC = 30.0
SCRCPY_LAUNCH_LOG = os.path.join(LOG_DIR, "scrcpy-launch.log")  # scrcpy 自己的输出
LAUNCH_ATTEMPTS = 3        # 可见投屏拉起后存活校验失败的重试次数
LAUNCH_CHECKS = (3.0, 4.0)  # 拉起后 3 秒、7 秒两次存活校验（覆盖延迟退出）
LAST_VISIBLE_LAUNCH = {"t": 0.0}   # 上次可见投屏拉起成功时刻
HEADLESS_AFTER_VISIBLE_SEC = 8.0   # 可见投屏成功后再等这么久，避让并发启动
_launch_lock = threading.Lock()
USB_ONLINE = {"value": False}   # 拉起线程据此判断"这条线还在不在"
CREATE_NO_WINDOW = 0x08000000
PING_PORT = 18768          # 手机端 AdbWireless.PROBE_PORT 要与此一致
PING_REPLY = b"VOXFIX\n"
PC_INFO_ACTION = "local.voxfix.debug.PC_INFO"
PC_STATE_ACTION = "local.voxfix.debug.PC_STATE"
HEARTBEAT_SEC = 5.0        # 连着时的心跳：手机据此判断"通道还在、剪贴板可用"
CLIP_WATCHER_OK = {"value": "0"}


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
    """优先 USB 设备，其次无线 IP:port，再次 mDNS 自动连接名。

    重开无线调试后 adb 37 的 mDNS 自动连接序列号是服务名（形如
    `adb-<USB序列号>-*._adb-tls-connect._tcp`），不是 IP:port（2026-11-09 实测）；
    对 adb/scrcpy 的 -s 参数同样合法。"""
    if USB_SERIAL in devices:
        return USB_SERIAL
    for serial in devices:
        if ":" in serial:
            return serial
    for serial in devices:
        if serial.startswith(f"adb-{USB_SERIAL}-"):
            return serial
    return ""


def local_ipv4() -> list:
    """本机所有非回环 IPv4（可能同时有以太网和 Wi-Fi 两个）。"""
    addrs = set()
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127."):
                addrs.add(ip)
    except OSError as error:
        log(f"枚举本机地址失败: {error}")
    return sorted(addrs)


def ping_server() -> None:
    """手机开着弧柄时会来连这个端口，用来判断"电脑端在不在"。

    只回一行 VOXFIX 就关连接，不读数据、不解析协议。
    """
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", PING_PORT))
        server.listen(8)
    except OSError as error:
        log(f"探测端口 {PING_PORT} 监听失败（可能被防火墙或占用）: {error}")
        return
    log(f"探测端口已监听 0.0.0.0:{PING_PORT}，本机地址 {local_ipv4()}")
    while True:
        try:
            conn, _peer = server.accept()
            with conn:
                conn.settimeout(2)
                conn.sendall(PING_REPLY)
        except Exception as error:  # 单个连接出错不影响监听
            log(f"探测连接异常（忽略）: {error}")
            time.sleep(0.2)


def push_pc_info(serial: str) -> None:
    """把本机地址推给手机，手机据此判断"是不是同一网段"。"""
    if not serial:
        return
    addrs = ",".join(local_ipv4())
    if not addrs:
        return
    try:
        # 必须带 -p：Android 8+ 对清单里的隐式广播不再投递，实测无 -p 时 result=0 没人收
        run_hidden([ADB, "-s", serial, "shell", "am", "broadcast", "-a",
                    PC_INFO_ACTION, "-p", "local.voxfix.debug",
                    "--es", "addrs", addrs], 10)
        log(f"推送电脑地址 → {addrs}")
    except Exception as error:
        log(f"推送电脑地址失败: {error}")


def push_state(serial: str, transport: str) -> None:
    """心跳：每 5 秒告诉手机"电脑这边还连着、走的是 USB 还是 Wi-Fi"。

    手机侧超时 15 秒就认为断开 —— 拔线/换网/电脑休眠都会让心跳自己停掉，
    不需要手机去猜。
    """
    if not serial:
        return
    try:
        run_hidden([ADB, "-s", serial, "shell", "am", "broadcast", "-a",
                    PC_STATE_ACTION, "-p", "local.voxfix.debug",
                    "--es", "transport", transport,
                    "--es", "clip", CLIP_WATCHER_OK["value"]], 8)
    except Exception as error:
        log(f"心跳失败: {error}")


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


def single_instance() -> bool:
    """文件独占锁判单实例。

    不能靠「PID 是否存活」判断：Windows 会复用 PID（2026-11-13 实测 9496 被
    DefenderAiPlatformHost 复用，companion 每次启动都误判「已有实例」并自杀，
    插线投屏因此从 11-12 起再没被拉起）。文件锁随进程退出自动释放，不受 PID 复用影响。
    """
    global _lock_file
    os.makedirs(LOG_DIR, exist_ok=True)
    try:
        handle = open(LOCK_FILE, "a+")
    except OSError as error:
        log(f"单实例锁不可用，按无锁启动: {error}")
        return True
    try:
        handle.seek(0)
        msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
    except OSError:
        handle.close()
        return False
    _lock_file = handle          # 必须持有到进程结束，释放锁等于允许第二个实例
    with open(PID_FILE, "w") as f:
        f.write(str(os.getpid()))
    return True


def launch_visible(attempts: int = LAUNCH_ATTEMPTS) -> None:
    """拉起可见投屏，并校验它真的活了；失败时把 scrcpy 自己的输出留档。

    2026-11-14：原实现只 spawn 一次、不校验存活。开机后首次拉起时 scrcpy
    （首次 push scrcpy-server / 首次初始化 D3D11 / Defender 首次扫描 exe）
    有概率起不来或起后就退，此时没有任何重试，USB 状态也不再变化，
    于是表现为「开机后插线不弹，再拔插一次才好」。
    """
    if visible_scrcpy_running():
        log("投屏窗口已在运行 → 不重复拉起")
        return
    for attempt in range(1, attempts + 1):
        LAST_VISIBLE_LAUNCH["t"] = time.monotonic()   # 每次尝试都刷新避让窗口
        if not USB_ONLINE["value"]:
            log(f"投屏第 {attempt} 次拉起前设备已离线 → 放弃本次")
            return
        fh = None
        try:
            fh = open(SCRCPY_LAUNCH_LOG, "ab")
            fh.write((f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} "
                      f"第 {attempt} 次拉起 {USB_SERIAL} =====\n").encode())
            fh.flush()
        except OSError as error:
            log(f"scrcpy 输出留档不可写（继续拉起）: {error}")
            fh = None
        try:
            proc = subprocess.Popen(
                [SCRCPEX, "-s", USB_SERIAL],
                stdin=subprocess.DEVNULL,
                stdout=fh if fh else subprocess.DEVNULL,
                stderr=subprocess.STDOUT if fh else subprocess.DEVNULL,
                creationflags=CREATE_NO_WINDOW, close_fds=True)
        except Exception as error:
            log(f"投屏拉起异常（{attempt}/{attempts}）: {error}")
            if fh:
                fh.close()
            time.sleep(2)
            continue
        if fh:
            fh.close()
        died = False
        for wait in LAUNCH_CHECKS:
            time.sleep(wait)
            if proc.poll() is not None:
                died = True
                break
        if not died:
            LAST_VISIBLE_LAUNCH["t"] = time.monotonic()   # 让无窗口通道延后启动
            log(f"投屏窗口已拉起且存活（pid {proc.pid}，第 {attempt} 次尝试）"
                if attempt > 1 else f"USB 插入 → 已拉起投屏窗口（pid {proc.pid}）")
            return
        log(f"投屏拉起后退出（exit={proc.returncode}，"
            f"第 {attempt}/{attempts} 次），scrcpy 输出见 "
            f"{os.path.basename(SCRCPY_LAUNCH_LOG)}")
        time.sleep(2)
    log("投屏连续拉起失败 → 放弃本次（下次插拔再试）")


def _launch_guarded() -> None:
    try:
        launch_visible()
    except Exception as error:
        log(f"拉起投屏线程异常: {error}")
    finally:
        _launch_lock.release()


def on_usb_plug(first: bool) -> None:
    """插线事件：异步拉起可见投屏，主循环不阻塞（重试期间仍要发心跳）。"""
    USB_ONLINE["value"] = True
    if not _launch_lock.acquire(blocking=False):
        log("投屏拉起正在进行 → 本次跳过")
        return
    # 触发瞬间就记时间，别等校验成功 —— 否则主循环会在拉起过程中抢跑无窗口通道
    LAST_VISIBLE_LAUNCH["t"] = time.monotonic()
    if first:
        log("启动时设备已在线 → 拉起投屏窗口")
    threading.Thread(target=_launch_guarded, daemon=True,
                     name="launch-visible").start()


def on_usb_unplug() -> None:
    USB_ONLINE["value"] = False
    log("USB 拔出 → 投屏随断连自动关闭")
    for pid, cmdline in process_commands().items():
        if "scrcpy" in (cmdline or "") and "--no-window" not in (cmdline or ""):
            run_hidden(["taskkill", "/PID", str(pid), "/F"], 10)
            log(f"兜底结束残留投屏进程 {pid}")


def main() -> None:
    if not single_instance():
        # 计划任务每 5 分钟自愈探测一次，这里静默退出，避免日志每 5 分钟刷一行。
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
    threading.Thread(target=ping_server, daemon=True, name="ping").start()

    usb_was_online = None
    last_devices = []
    last_headless_try = 0.0
    last_maint = 0.0
    last_reconcile = 0.0
    last_pc_info = 0.0
    last_beat = 0.0
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
                    push_pc_info(pick_serial(devices))
                last_devices = devices
                if devices and now - last_beat >= HEARTBEAT_SEC:
                    last_beat = now
                    push_state(pick_serial(devices),
                               "usb" if USB_SERIAL in devices else "wifi")
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
            if now - last_pc_info > 60:      # 地址/网段可能被 DHCP 换掉，定期补推
                last_pc_info = now
                push_pc_info(pick_serial(last_devices))

            procs = process_commands()
            watcher = any("copyq_phone_sync.py" in (c or "")
                          for c in procs.values())
            CLIP_WATCHER_OK["value"] = "1" if watcher else "0"
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
                # 刚拉起过可见投屏就等一下：两个 scrcpy 同时启动会抢设备 server，
                # 先起的那个报「Device disconnected / Demuxer connection error」退出
                # （2026-11-14 抓到的插线即弹失败根因）
                if time.monotonic() - LAST_VISIBLE_LAUNCH["t"] < HEADLESS_AFTER_VISIBLE_SEC:
                    continue
                last_headless_try = time.monotonic()
                spawn([SCRCPEX, "--no-window", "--no-video",
                       "--no-audio", "-s", serial])
                headless_serial = serial
                log(f"无窗口 scrcpy（文本通道）未在运行 → 已按 {serial} 拉起")
        except Exception as error:
            log(f"主循环异常（忽略继续）: {error}")


if __name__ == "__main__":
    main()
