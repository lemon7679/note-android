#!/usr/bin/env bash
# CI only (inside the Android emulator runner): installs the app and its instrumentation
# tests, runs SmokeTest (engine through JNI, library, editor with injected stylus strokes,
# PDF notebook and viewer, NAS screens, WebDAV sync), pulls the screenshots and prints
# downscaled copies as base64 between "@@SHOT <name>" / "@@END" markers.
set -uo pipefail
cd "$(dirname "$0")/.."
TAG="${1:-device}"
adb logcat -G 16M > /dev/null 2>&1 || true
adb logcat -c || true
./gradlew --no-daemon -q :app:installDebug :app:installDebugAndroidTest
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell am instrument -w -r -e class com.lemon7679.note.SmokeTest \
  com.lemon7679.note.test/androidx.test.runner.AndroidJUnitRunner | tee instrument.log
mkdir -p "shots-$TAG"
adb pull /sdcard/Android/data/com.lemon7679.note/files/shots/. "shots-$TAG/" > /dev/null 2>&1 || true
for f in "shots-$TAG"/*.png; do
  [ -f "$f" ] || continue
  name="$TAG-$(basename "$f" .png)"
  if command -v convert > /dev/null; then
    convert "$f" -resize 1100x1100 -quality 55 "${f%.png}.jpg"
    echo "@@SHOT $name"; base64 -w 120 "${f%.png}.jpg"; echo "@@END"
  else
    echo "@@SHOT $name"; base64 -w 120 "$f"; echo "@@END"
  fi
done
# Minified release build (R8): install over the debug build, open a notebook through the UI.
echo "== release build"
./gradlew --no-daemon -q :app:assembleRelease -PnoteVersion=0.0.0-ci
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop com.lemon7679.note
adb shell am start -W -n com.lemon7679.note/.MainActivity
sleep 6
adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb pull /sdcard/ui.xml ui.xml > /dev/null
python3 - <<'PY' > tap.txt || true
import re
xml = open("ui.xml", encoding="utf-8").read()
m = re.search(r'text="선형대수 필기"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    x1, y1, x2, y2 = map(int, m.groups())
    print((x1 + x2) // 2, (y1 + y2) // 2)
PY
if [ -s tap.txt ]; then adb shell input tap $(cat tap.txt); sleep 6; fi
adb exec-out screencap -p > "shots-$TAG/10-release-editor.png"
if adb shell pidof com.lemon7679.note > /dev/null; then echo "release app running"; else echo "RELEASE APP NOT RUNNING (crash?)"; RELEASE_FAIL=1; fi
for f in "shots-$TAG"/10-*.png; do
  name="$TAG-$(basename "$f" .png)"
  convert "$f" -resize 1100x1100 -quality 55 "${f%.png}.jpg" 2>/dev/null && { echo "@@SHOT $name"; base64 -w 120 "${f%.png}.jpg"; echo "@@END"; }
done

echo "== app log (errors)"
adb logcat -d -v brief '*:E' | grep -iE "lemon7679|AndroidRuntime|FATAL|noteink" | tail -80 || true
echo "== instrumentation result"
tail -5 instrument.log
grep -q "FAILURES!!!" instrument.log && exit 1
[ -n "${RELEASE_FAIL:-}" ] && exit 1
grep -q "OK (" instrument.log
