#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
serial="${1:?Usage: ./scripts/device-check.sh emulator-SERIAL}"
case "$serial" in emulator-*) ;; *) echo 'This automated runner only accepts a dedicated emulator.' >&2; exit 2 ;; esac
adb_path="${KANDONG_ADB:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb}"
[ "$("$adb_path" -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo 'Not an Android emulator.' >&2; exit 2; }
./scripts/check.sh :app:assembleDebugAndroidTest
"$adb_path" -s "$serial" install -r app/build/outputs/apk/debug/app-debug.apk
"$adb_path" -s "$serial" install -r fixture/build/outputs/apk/debug/fixture-debug.apk
"$adb_path" -s "$serial" install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
configured="$("$adb_path" -s "$serial" shell settings get secure enabled_accessibility_services)"
case "$configured" in *com.kandong.app/*) ;; *) echo 'Apps installed. Manually enable KanDong accessibility on this emulator, then rerun.' >&2; exit 2 ;; esac
mkdir -p app/build/reports/device
result=app/build/reports/device/instrumentation.txt
"$adb_path" -s "$serial" shell am instrument -w -r -e rebindAccessibility true -e captureScreenshot true com.kandong.app.test/androidx.test.runner.AndroidJUnitRunner > "$result" 2>&1
cat "$result"
# adb may exit 0 even when JUnit failed. Require the actual test result.
awk '/^OK \([0-9]+ tests?\)$/ { passed=1 } END { exit !passed }' "$result"
echo 'Device tests passed. Screenshot is in the debug app cache (synthetic fixture only).'
