@echo off
rem 手机剪贴板 -> CopyQ 单向搬运，一键常驻：
rem   1. 无窗口 scrcpy（文本同步通道，不弹投屏画面；要看画面另跑一次 scrcpy 即可）
rem   2. 同步守护（文本监听 + 图片轮询，写 CopyQ「手机文本」「手机图片」标签）
rem 要求：手机已无线配对（adb connect）或插着 USB 线；CopyQ 在运行。

start "scrcpy-sync" /b "C:\Users\23221\AppData\Local\Microsoft\WinGet\Packages\Genymobile.scrcpy_Microsoft.Winget.Source_8wekyb3d8bbwe\scrcpy-win64-v5.0.1\scrcpy.exe" --no-window --no-video --no-audio -e
start "copyq-phone-sync" /b "C:\Users\23221\.workbuddy\binaries\python\versions\3.13.12\pythonw.exe" "D:\Projects\VoxFix\tools\copyq_phone_sync.py"
