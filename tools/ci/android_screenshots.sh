#!/usr/bin/env bash
# Installs the debug APK on a running emulator, makes UzKey the active keyboard and captures
# screenshots of the keyboard in several languages and themes into docs/screenshots/.
set -euo pipefail

APK="${1:?usage: android_screenshots.sh path/to/app-debug.apk}"
OUT="${2:-docs/screenshots}"
PACKAGE="uz.uzkey.keyboard"
IME="$PACKAGE/.ime.UzKeyService"
PREFS_DIR="/data/user_de/0/$PACKAGE/shared_prefs"
mkdir -p "$OUT"

adb wait-for-device
adb shell 'while [[ -z "$(getprop sys.boot_completed)" ]]; do sleep 1; done'
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb install -r "$APK"
# The input method service becomes visible to the system a few seconds after installation.
for _ in $(seq 1 30); do
  if adb shell ime list -a -s | grep -q "$IME"; then break; fi
  sleep 2
done
adb shell ime enable "$IME"
adb shell ime set "$IME"

write_prefs() {
  local layout="$1" theme="$2" layouts="$3"
  adb shell am force-stop "$PACKAGE"
  adb shell "run-as $PACKAGE sh -c 'mkdir -p $PREFS_DIR && cat > $PREFS_DIR/uzkey_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="enabled_layouts">$layouts</string>
    <string name="current_layout">$layout</string>
    <string name="theme">$theme</string>
</map>
XML
  adb shell ime set "$IME"
}

focus_text_field() {
  adb shell am start -W -n "$PACKAGE/.settings.SettingsActivity" > /dev/null
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null
  local center
  center=$(python3 - <<'PY'
import re
xml = open("/tmp/ui.xml", encoding="utf-8").read()
for node in re.finditer(r'<node [^>]*class="android.widget.EditText"[^>]*>', xml):
    m = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node.group(0))
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
  if [[ -z "$center" ]]; then
    echo "text field not found" >&2
    return 1
  fi
  # shellcheck disable=SC2086
  adb shell input tap $center
  sleep 2
}

capture() {
  local name="$1" text="${2:-}"
  focus_text_field
  if [[ -n "$text" ]]; then
    adb shell input text "$text"
    sleep 2
  fi
  adb exec-out screencap -p > "$OUT/android-$name.png"
  echo "captured $OUT/android-$name.png"
}

ALL="uz_latn_2027,uz_latn,uz_cyrl,ru,en"

adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/.settings.SettingsActivity" > /dev/null
sleep 3
adb exec-out screencap -p > "$OUT/android-settings.png"

write_prefs uz_latn_2027 light "$ALL"
capture uz-2027-light "Salom%sdunyo%sYax"
write_prefs uz_latn dark "$ALL"
capture uz-latn-dark
write_prefs uz_cyrl blue "$ALL"
capture uz-cyrl-blue
write_prefs ru light "$ALL"
capture ru-light
write_prefs en black "$ALL"
capture en-black "Hel"
