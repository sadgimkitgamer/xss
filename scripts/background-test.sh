#!/usr/bin/env bash
# Plays a song in Ember on an emulator, sends the app to the background and turns
# the screen off, then checks from the app's logs that the song kept playing.
set -u
APK="$1"
OUT=test-out
mkdir -p "$OUT"
SONG="https://music.youtube.com/watch?v=dQw4w9WgXcQ"

adb install -r "$APK"
adb shell pm grant com.ember.music android.permission.POST_NOTIFICATIONS || true
adb logcat -c

shot() { adb exec-out screencap -p > "$OUT/$1.png" || true; }
pos() { adb logcat -d -s Ember:I | grep 'state {' | tail -1 | sed -E 's/.*"pos":([0-9]+).*/\1/'; }
playing() { adb logcat -d -s Ember:I | grep 'state {' | tail -1 | grep -q '"playing":true'; }

echo "== Foreground: open song"
adb shell am start -W -a android.intent.action.VIEW -d "$SONG" --ez autoplay true com.ember.music/.MainActivity
sleep 60
shot 1-foreground
P1=$(pos); playing && FG=yes || FG=no
echo "foreground: playing=$FG pos=$P1"

echo "== Background: press HOME"
adb shell input keyevent KEYCODE_HOME
sleep 45
shot 2-home
P2=$(pos); playing && BG=yes || BG=no
echo "background: playing=$BG pos=$P2"

echo "== Screen off"
adb shell input keyevent KEYCODE_SLEEP
sleep 45
P3=$(pos); playing && OFF=yes || OFF=no
echo "screen off: playing=$OFF pos=$P3"
adb shell input keyevent KEYCODE_WAKEUP

adb logcat -d -s Ember:I > "$OUT/ember.log"
adb logcat -d > "$OUT/full.log"
echo "================ Ember log (last 120 lines) ================"
tail -120 "$OUT/ember.log"
echo "============================================================"
echo "RESULT foreground=$FG($P1) background=$BG($P2) screenoff=$OFF($P3)"

[ "$FG" = yes ] || { echo "FAIL: did not start playing in the foreground"; exit 1; }
[ "$BG" = yes ] && [ "${P2:-0}" -gt "${P1:-0}" ] || { echo "FAIL: stopped in background"; exit 1; }
[ "$OFF" = yes ] && [ "${P3:-0}" -gt "${P2:-0}" ] || { echo "FAIL: stopped with screen off"; exit 1; }
echo "PASS"
