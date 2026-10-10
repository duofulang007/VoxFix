$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$taskSdk = Join-Path $taskRoot '.android\sdk'
$taskAvdHome = Join-Path $taskRoot '.android\avd'
$taskAvdName = 'VoxFix_K60_API35'
$taskAvdPath = Join-Path $taskAvdHome ($taskAvdName + '.avd')
$taskAdb = Join-Path $taskSdk 'platform-tools\adb.exe'
$taskEmulator = Join-Path $taskSdk 'emulator\emulator.exe'
$env:ANDROID_HOME = $taskSdk
$env:ANDROID_AVD_HOME = $taskAvdHome
$env:ANDROID_USER_HOME = Join-Path $taskRoot '.android\user'
New-Item -ItemType Directory -Path $taskAvdPath,$env:ANDROID_USER_HOME -Force | Out-Null
if (-not (Test-Path -LiteralPath (Join-Path $taskAvdPath 'config.ini'))) {
    @"
AvdId=$taskAvdName
avd.ini.displayname=VoxFix K60 screen - Android 15
avd.ini.encoding=UTF-8
abi.type=x86_64
tag.id=google_apis
tag.display=Google APIs
image.sysdir.1=system-images\android-35\google_apis\x86_64\
hw.cpu.arch=x86_64
hw.cpu.ncore=4
hw.ramSize=4096
hw.gpu.enabled=yes
hw.gpu.mode=auto
hw.lcd.width=1440
hw.lcd.height=3200
hw.lcd.density=560
hw.lcd.xdpi=526
hw.lcd.ydpi=526
hw.keyboard=yes
hw.audioInput=yes
hw.audioOutput=yes
hw.sdCard=no
hw.initialOrientation=Portrait
disk.dataPartition.size=16G
vm.heapSize=512
showDeviceFrame=no
skin.dynamic=yes
skin.name=1440x3200
skin.path=_no_skin
fastboot.forceColdBoot=no
fastboot.forceFastBoot=yes
"@ | Set-Content -LiteralPath (Join-Path $taskAvdPath 'config.ini') -Encoding UTF8
    @"
avd.ini.encoding=UTF-8
path=$taskAvdPath
target=android-35
"@ | Set-Content -LiteralPath (Join-Path $taskAvdHome ($taskAvdName+'.ini')) -Encoding UTF8
}
if (-not (Test-Path -LiteralPath $taskEmulator)) { throw '请先运行 tools/setup_emulator.py 安装模拟器' }
$taskDevices = & $taskAdb devices
if ($taskDevices -match '^emulator-5554\s+device') {
    $taskRunningName = & $taskAdb -s emulator-5554 emu avd name
    if (-not ($taskRunningName -contains $taskAvdName)) { throw '5554 端口正在运行其他模拟器，请先关闭它；未改动该模拟器' }
} else {
    Start-Process -FilePath $taskEmulator -ArgumentList @('-avd',$taskAvdName,'-port','5554','-netdelay','none','-netspeed','full') -WorkingDirectory $taskRoot -WindowStyle Normal -RedirectStandardOutput (Join-Path $taskRoot '.android\emulator.out.log') -RedirectStandardError (Join-Path $taskRoot '.android\emulator.err.log') | Out-Null
}
$taskBooted = $false
for ($taskAttempt = 0; $taskAttempt -lt 120; $taskAttempt++) {
    $taskBoot = & $taskAdb -s emulator-5554 shell getprop sys.boot_completed 2>$null
    if ($taskBoot -eq '1') { $taskBooted = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $taskBooted) { throw '模拟器尚未启动完成，请查看 .android/emulator.err.log' }
try { Invoke-RestMethod -Uri 'http://127.0.0.1:18743/api/voice/status' -TimeoutSec 2 | Out-Null }
catch {
    Start-Process -FilePath (Join-Path $taskRoot '.venv\Scripts\pythonw.exe') -ArgumentList (Join-Path $taskRoot 'voice\server.py') -WorkingDirectory $taskRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $taskRoot 'voice-server.out.log') -RedirectStandardError (Join-Path $taskRoot 'voice-server.err.log') | Out-Null
}
& $taskAdb -s emulator-5554 reverse tcp:18743 tcp:18743
& $taskAdb -s emulator-5554 shell settings put system system_locales zh-CN
& $taskAdb -s emulator-5554 shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.android.chrome | Out-Null
$taskAlreadyOpen = $false
try {
    & $taskAdb -s emulator-5554 shell uiautomator dump /sdcard/voxfix-launch-ui.xml | Out-Null
    [xml]$taskBrowserUi = ((& $taskAdb -s emulator-5554 shell cat /sdcard/voxfix-launch-ui.xml) -join "`n")
    $taskUrlNode = $taskBrowserUi.SelectSingleNode("//node[@resource-id='com.android.chrome:id/url_bar']")
    $taskAlreadyOpen = ($taskUrlNode -and $taskUrlNode.GetAttribute('text') -match '^127\.0\.0\.1:18743/?(?:#phone)?$') -or (-not $taskUrlNode -and $null -ne $taskBrowserUi.SelectSingleNode("//node[contains(@text,'VoxFix')]"))
} finally { & $taskAdb -s emulator-5554 shell rm -f /sdcard/voxfix-launch-ui.xml | Out-Null }
if (-not $taskAlreadyOpen) {
    & $taskAdb -s emulator-5554 shell am start -a android.intent.action.VIEW -d 'http://127.0.0.1:18743/#phone' --es com.android.browser.application_id voxfix.preview com.android.chrome
}
Write-Output 'ANDROID_PREVIEW_OPENED'
