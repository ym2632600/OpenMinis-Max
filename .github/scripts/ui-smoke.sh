#!/usr/bin/env bash
# UI smoke: install the freshly built debug APK on the emulator, open the
# app's main surfaces (launcher, deep links), and capture screenshots for
# the ui-screenshots artifact. Never fails the build: screenshot capture is
# best-effort so a flaky emulator does not block the APK pipeline.
set -uxo pipefail

mkdir -p shots
APK=$(ls apk/*.apk | head -1)
PKG=com.openminis.app

adb wait-for-device
while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
sleep 5

adb install -r "$APK"

shot() {
  adb shell screencap -p /sdcard/shot.png || return 0
  adb pull /sdcard/shot.png "shots/$1" || return 0
}

launch() {  # $1 = deep link path segment, empty = plain launcher start
  if [ -n "$1" ]; then
    adb shell am start -a android.intent.action.VIEW -d "minis://action/$1" "$PKG" || true
  else
    adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null || true
  fi
}

launch ""
sleep 12
shot 01-launch.png

launch "new"
sleep 8
shot 02-new-chat.png

launch "voice"
sleep 8
shot 03-voice.png

# Arabic locale pass (RTL + values-ar): restart zygote with ar-EG, then relaunch.
adb root || true
if adb shell setprop persist.sys.locale ar-EG; then
  adb shell setprop ctl.restart zygote || true
  adb wait-for-device
  while [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" != "1" ]; do sleep 2; done
  sleep 8
  launch ""
  sleep 12
  shot 04-launch-arabic.png
fi

ls -la shots/
