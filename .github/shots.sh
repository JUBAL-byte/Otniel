#!/bin/bash
# Opens the site in the app on the emulator (through otniel.org links, which also checks
# that the links open in the app) and keeps a screenshot of each step.
set -x
PKG=app.jubal.otniel
adb install -r app.apk
mkdir -p shots
shot() { sleep "${2:-14}"; adb exec-out screencap -p > "shots/$1.png"; }
open_link() { adb shell "am start -a android.intent.action.VIEW -d '$1' -n $PKG/.MainActivity"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb exec-out cat /sdcard/ui.xml > "shots/$1.xml"; }
# Taps the middle of the first element whose text or description matches $1 (in the last dump)
tap_on() {
  local b
  b=$(python3 - "$1" "shots/$2.xml" <<'PY'
import re, sys, xml.etree.ElementTree as ET
pat = re.compile(sys.argv[1], re.I)
try:
    root = ET.parse(sys.argv[2]).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    label = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if pat.search(label) and n.get("bounds"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        if x2 > x1 and y2 > y1:
            print((x1 + x2) // 2, (y1 + y2) // 2)
            break
PY
)
  [ -n "$b" ] && adb shell input tap $b
}

read W H < <(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1 | tr x ' ')
swipe_up() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 4)) 400; }
swipe_down() { adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 3 / 4)) 400; }

adb shell "am start -n $PKG/.MainActivity"; shot 01-home 30
dump 01-home
swipe_up; shot 01b-home-scrolled 5
swipe_down; swipe_down; sleep 2
# The site's own menu button (the teal "three lines" at the top right of the page;
# found in the second picture, as the first one may show Android's notifications question)
pip install -q pillow >/dev/null 2>&1
MENU=$(python3 - <<'PY'
from PIL import Image
im = Image.open("shots/01b-home-scrolled.png").convert("RGB")
w, h = im.size
pts = [(x, y) for y in range(0, h // 6) for x in range(int(w * 0.75), w)
       if (lambda p: abs(p[0] - 0x35) < 30 and abs(p[1] - 0x85) < 30 and abs(p[2] - 0x92) < 30)(im.getpixel((x, y)))]
if pts:
    print(sum(p[0] for p in pts) // len(pts), sum(p[1] for p in pts) // len(pts))
PY
)
[ -n "$MENU" ] && adb shell input tap $MENU; shot 01c-menu 4
adb shell input keyevent KEYCODE_BACK; sleep 1

open_link "https://otniel.org/all-lessons/"; shot 02-lessons
open_link "https://otniel.org/all-vid-series/"; shot 03-series
open_link "https://otniel.org/all-podcasts/"; shot 04-podcasts
LESSON=$(curl -s 'https://otniel.org/wp-json/wp/v2/shiurim?per_page=1&_fields=link' | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["link"])')
open_link "$LESSON"; shot 05-lesson 20
swipe_up; shot 05b-lesson-scrolled 6
swipe_up; shot 05c-lesson-scrolled 6

# The bookstore opens only in the phone's browser: the app asks first
open_link "https://otniel.org/bookstore/"; shot 06-store-asks 8
adb shell input keyevent KEYCODE_BACK; sleep 1

# A link shared as text (from WhatsApp, say)
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'שווה לשמוע: otniel.org/all-podcasts/' -n $PKG/.MainActivity"; shot 07-shared-link

# A PDF from the site opens page by page in the app
PDF=$(curl -s 'https://otniel.org/wp-json/wp/v2/media?mime_type=application/pdf&per_page=1&_fields=source_url' | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["source_url"] if d else "")')
if [ -n "$PDF" ]; then open_link "$PDF"; shot 08-pdf 20; adb shell input keyevent KEYCODE_BACK; sleep 1; fi

# Back goes back through the pages, as in a browser
adb shell input keyevent KEYCODE_BACK; shot 09-after-back 6

# Without internet
adb shell svc wifi disable; adb shell svc data disable; sleep 3
open_link "https://otniel.org/contact/"; shot 10-offline 10
adb shell svc wifi enable; adb shell svc data enable; shot 10b-back-online 20

# A small screen, like a keypad phone
adb shell wm size 480x640
adb shell wm density 160
open_link "https://otniel.org/"; shot 11-small-home 20
open_link "$LESSON"; shot 12-small-lesson 20
adb shell wm size reset
adb shell wm density reset

adb shell dumpsys webviewupdate | grep -i -E "current webview package|versionName" | head -3 > shots/webview.txt
adb logcat -d -s AndroidRuntime:E > shots/crash.txt
adb logcat -d | grep -i -E "otniel|chromium|cr_|webview" | tail -400 > shots/log.txt
# Empty files cannot be uploaded
find shots -type f -empty -delete
exit 0
