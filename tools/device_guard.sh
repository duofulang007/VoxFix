#!/usr/bin/env bash
# 真机操作前的守卫：服务被杀或浮层不在时，任何 input tap 都会穿透到桌面/别的 App。
# 用法：bash .android/device-guard.sh [--require-overlay]
#   不带参数：确认 ScreenTextService 未 Crashed，必要时重绑。
#   带 --require-overlay：另外要求 VoxFix 有窗口（圆弧或截图浮层）在场，否则退出码 4。
set -u
ADB="${ADB:-D:/Projects/VoxFix/.android/sdk/platform-tools/adb.exe}"
SERIAL="${SERIAL:-6959dba1}"
REQUIRE_OVERLAY="${1:-}"

# 只看 `Crashed services:` 那一行，别把已启用列表和正常服务行算进去。
crashed_list() { "$ADB" -s "$SERIAL" shell dumpsys accessibility 2>/dev/null | grep -m1 "Crashed services" | grep -c "ScreenTextService"; }
crashed=$(crashed_list)
if [ "$crashed" != "0" ]; then
  echo "service crashed -> rebind"
  bash "$(dirname "$0")/../.android/rebind-voxfix-a11y.sh" >/dev/null 2>&1 || { echo "rebind failed"; exit 2; }
  sleep 3
  if [ "$(crashed_list)" != "0" ]; then
    echo "still crashed after rebind"; exit 3
  fi
fi
echo "service ok: pid=$("$ADB" -s "$SERIAL" shell pidof local.voxfix.debug | tr -d '\r')"

if [ "$REQUIRE_OVERLAY" = "--require-overlay" ]; then
  windows=$("$ADB" -s "$SERIAL" shell dumpsys window windows 2>/dev/null | grep -c "u0 local.voxfix.debug}")
  echo "voxfix windows: $windows"
  if [ "$windows" = "0" ]; then
    echo "no voxfix window: refusing to tap"; exit 4
  fi
fi

resumed=$("$ADB" -s "$SERIAL" shell dumpsys activity activities 2>/dev/null | grep -m1 "mResumedActivity" | tr -d '\r')
echo "resumed: $resumed"
