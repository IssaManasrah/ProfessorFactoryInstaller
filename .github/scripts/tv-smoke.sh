#!/usr/bin/env bash
set -euxo pipefail

APK="app/build/outputs/apk/debug/app-debug.apk"
PACKAGE="com.shaikhalkar.professorinstaller.debug"
COMPONENT="$PACKAGE/com.shaikhalkar.professorinstaller.ProfessorMainActivity"

adb wait-for-device
for i in 1 2 3 4 5; do
  STATE=$(adb get-state | tr -d '\r')
  BOOT=$(adb shell getprop sys.boot_completed | tr -d '\r')
  echo "stability[$i]: state=$STATE boot=$BOOT"
  test "$STATE" = "device"
  test "$BOOT" = "1"
  sleep 2
done

adb logcat -c
set +e
INSTALL_OUTPUT=$(adb install --no-streaming -r "$APK" 2>&1)
INSTALL_RC=$?
set -e
printf '%s\n' "$INSTALL_OUTPUT" | tee install-result.txt
adb logcat -b all -d > package-manager-logcat.txt || true
if [ $INSTALL_RC -ne 0 ] || ! printf '%s\n' "$INSTALL_OUTPUT" | grep -q 'Success'; then
  echo "APK install failed (rc=$INSTALL_RC)"
  adb devices -l || true
  grep -Ei "PackageManager|PackageInstaller|INSTALL_|parse|signature|permission|professor" package-manager-logcat.txt | tail -n 300 || true
  exit 1
fi

adb shell am force-stop "$PACKAGE"
adb logcat -c
START_OUTPUT=$(adb shell am start -W -n "$COMPONENT" 2>&1)
printf '%s\n' "$START_OUTPUT" | tee start-result.txt
sleep 5

PID=$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)
adb logcat -b all -d > emulator-logcat.txt || true
if [ -z "$PID" ]; then
  echo "Professor Installer crashed or failed to launch"
  grep -E "FATAL EXCEPTION|AndroidRuntime|$PACKAGE" emulator-logcat.txt | tail -n 300 || true
  exit 1
fi

adb shell uiautomator dump /sdcard/window.xml
adb pull /sdcard/window.xml window.xml
adb exec-out screencap -p > professor-tv-home.png

grep -q "برمجة الأجهزة" window.xml
grep -q "تصفح التطبيقات" window.xml
grep -q "الدعم" window.xml

adb shell input keyevent KEYCODE_DPAD_RIGHT
adb shell input keyevent KEYCODE_DPAD_LEFT
sleep 1
adb logcat -b all -d > emulator-logcat-after-dpad.txt || true
if grep -E "FATAL EXCEPTION: main|Process: $PACKAGE.*FATAL" emulator-logcat-after-dpad.txt; then
  echo "Crash detected after D-pad smoke test"
  exit 1
fi

# Back from the home screen must leave the app instead of rebuilding home forever.
adb shell input keyevent KEYCODE_BACK
sleep 2
if adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" | grep -q "$PACKAGE"; then
  echo "Back key did not leave Professor Installer home screen"
  exit 1
fi

echo "ANDROID_TV_SMOKE_TEST=PASS"
