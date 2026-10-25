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

# `am force-stop` must not be used here: when the stopped package hosts the current input method,
# InputMethodManagerService asynchronously resets the selection to the system default keyboard.
# Killing the process from inside the app's own uid lets the system simply rebind the service.
kill_app() {
  adb shell "run-as $PACKAGE sh -c 'pid=\$(pidof $PACKAGE) && kill -9 \$pid; true'"
  sleep 1
}

select_ime() {
  for _ in $(seq 1 15); do
    adb shell ime set "$IME" > /dev/null
    sleep 1
    if [[ "$(adb shell settings get secure default_input_method | tr -d '\r')" == "$IME" ]]; then
      return 0
    fi
  done
  echo "could not select $IME" >&2
  return 1
}

write_prefs() {
  local layout="$1" theme="$2" layouts="$3"
  # Stop the running process first so it cannot write its cached preferences over the new file.
  kill_app
  adb shell "run-as $PACKAGE sh -c 'mkdir -p $PREFS_DIR && cat > $PREFS_DIR/uzkey_settings.xml'" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="enabled_layouts">$layouts</string>
    <string name="current_layout">$layout</string>
    <string name="theme">$theme</string>
</map>
XML
  kill_app
  select_ime
}

# Prints "x y" for the centre of the first node matching a Python regex in a fresh UI dump, or nothing.
find_in_ui() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || return 0
  adb pull /sdcard/ui.xml /tmp/ui.xml > /dev/null 2>&1 || return 0
  python3 -c '
import re, sys
xml = open("/tmp/ui.xml", encoding="utf-8").read()
for node in re.finditer(sys.argv[1], xml):
    m = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node.group(0))
    if m:
        x1, y1, x2, y2 = map(int, m.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1"
}

focus_text_field() {
  adb shell am start -W -n "$PACKAGE/.settings.SettingsActivity" > /dev/null
  local center="" wait_button
  # A cold emulator can take a while to draw the screen, or show an "isn't responding" dialog.
  for _ in $(seq 1 15); do
    sleep 2
    center=$(find_in_ui '<node [^>]*class="android.widget.EditText"[^>]*>')
    [[ -n "$center" ]] && break
    wait_button=$(find_in_ui '<node [^>]*resource-id="android:id/aerr_wait"[^>]*>')
    # shellcheck disable=SC2086
    if [[ -n "$wait_button" ]]; then adb shell input tap $wait_button; fi
  done
  if [[ -z "$center" ]]; then
    echo "text field not found; the screen showed:" >&2
    grep -o 'text="[^"]*"' /tmp/ui.xml | head -40 >&2 || true
    return 1
  fi
  # shellcheck disable=SC2086
  adb shell input tap $center
  sleep 2
}

capture() {
  local name="$1" text="${2:-}"
  select_ime
  focus_text_field
  if [[ -n "$text" ]]; then
    adb shell input text "$text"
    sleep 2
  fi
  local current
  current=$(adb shell settings get secure default_input_method | tr -d '\r')
  if [[ "$current" != "$IME" ]]; then
    echo "UzKey is not the active keyboard; recent log:" >&2
    adb logcat -d | grep -iE "uzkey|AndroidRuntime|FATAL|InputMethod" | grep -v AppsFilter | tail -150 >&2 || true
    adb shell dumpsys input_method | head -60 >&2 || true
    return 1
  fi
  adb exec-out screencap -p > "$OUT/android-$name.png"
  echo "captured $OUT/android-$name.png"
}

ALL="uz_latn_2027,uz_latn,uz_cyrl,ru,en"
adb logcat -c

select_ime
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
