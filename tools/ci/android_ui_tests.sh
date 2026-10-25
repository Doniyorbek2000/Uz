#!/usr/bin/env bash
# Runs the instrumented keyboard tests (android/app/src/androidTest) on a running emulator.
# Expects the debug APK to be installed already (android_screenshots.sh does that).
set -euo pipefail

TEST_APK="${1:?usage: android_ui_tests.sh path/to/app-debug-androidTest.apk [report-file]}"
REPORT="${2:-build/android-ui-tests.txt}"
mkdir -p "$(dirname "$REPORT")"

adb install -r -t "$TEST_APK"
adb logcat -c
set +e
adb shell am instrument -w -r -e disableAnalytics true \
  uz.uzkey.keyboard.test/androidx.test.runner.AndroidJUnitRunner | tr -d '\r' | tee "$REPORT"
set -e

# `am instrument` exits with 0 even when tests fail; the summary line is the source of truth.
if grep -q "^OK (" "$REPORT"; then
  grep "^OK (" "$REPORT"
  exit 0
fi
echo "Instrumented tests failed" >&2
grep -E "^(Tests run|FAILURES|INSTRUMENTATION_)" "$REPORT" >&2 || true
adb logcat -d | grep -iE "uzkey|AndroidRuntime|TestRunner" | tail -200 > "$(dirname "$REPORT")/android-ui-tests-logcat.txt" || true
exit 1
