#!/bin/bash
# Opens the app's main screens on the emulator (through otniel.org links, which also
# checks that the links open in the app) and keeps a screenshot of each.
set -x
PKG=app.jubal.otniel
adb install -r app.apk
mkdir -p shots
shot() { sleep "${2:-14}"; adb exec-out screencap -p > "shots/$1.png"; }
open_link() { adb shell "am start -a android.intent.action.VIEW -d '$1' -n $PKG/.MainActivity"; }

adb shell "am start -n $PKG/.MainActivity"; shot 01-home 25
# Scroll the home page, and open the side menu (top right, in a right-to-left screen)
adb shell input swipe 160 500 160 150 400; shot 01b-home-scrolled 4
DENSITY=$(adb shell wm density | grep -o '[0-9]*' | tail -1); W=$(adb shell wm size | grep -o '[0-9]*x' | tail -1 | tr -d x)
X=$(( W - 26 * DENSITY / 160 )); Y=$(( (24 + 32) * DENSITY / 160 ))
adb shell input tap $X $Y; shot 01c-menu 3
adb shell input keyevent KEYCODE_BACK; sleep 1
open_link "https://otniel.org/all-lessons/"; shot 02-lessons
open_link "https://otniel.org/all-vid-series/"; shot 03-series
open_link "https://otniel.org/all-podcasts/"; shot 04-podcasts
LESSON=$(curl -s 'https://otniel.org/wp-json/wp/v2/shiurim?per_page=1&_fields=link' | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["link"])')
open_link "$LESSON"; shot 05-lesson 18
adb shell "am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'https://www.youtube.com/watch?v=JBoALZVdu4E' -n $PKG/.MainActivity"; shot 06-player 30
open_link "https://otniel.org/bookstore/"; shot 07-store
open_link "https://otniel.org/%d7%a9%d7%95%d7%aa/"; shot 08-faq
open_link "https://otniel.org/topics/tanach/"; shot 09-topic

# A small screen, like a keypad phone
adb shell wm size 480x640
adb shell wm density 160
open_link "https://otniel.org/"; shot 10-small-home 18
open_link "$LESSON"; shot 11-small-lesson 18
adb shell wm size reset
adb shell wm density reset

adb logcat -d -s AndroidRuntime:E > shots/crash.txt
adb logcat -d | grep -i -E "otniel|exoplayer|newpipe|media3" | tail -400 > shots/log.txt
exit 0
