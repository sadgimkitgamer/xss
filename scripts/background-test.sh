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
adb logcat -G 16M || true
adb logcat -c

shot() {
  adb exec-out screencap -p > "$OUT/$1.png" || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb shell cat /sdcard/ui.xml > "$OUT/$1.xml"
  echo "--- on screen ($1):"
  grep -oE '(text|content-desc)="[^"]+"' "$OUT/$1.xml" 2>/dev/null | sed -E 's/^[a-z-]+="//; s/"$//' | awk 'length>1' | head -40 | tr '\n' '|'
  echo
  echo "--- audio players:"
  adb shell dumpsys audio | grep -iE 'AudioPlaybackConfiguration|state:' | head -6
}
pos() { adb logcat -d -s Ember:I | grep 'state {' | tail -1 | sed -E 's/.*"pos":([0-9]+).*/\1/'; }
playing() { adb logcat -d -s Ember:I | grep 'state {' | tail -1 | grep -q '"playing":true'; }

echo "== Foreground: open song"
adb shell am start -W -a android.intent.action.VIEW -d "$SONG" --ez autoplay true ${EMBER_EXTRA:-} com.ember.music/.MainActivity
sleep 60
shot 1-foreground
P1=$(pos); playing && FG=yes || FG=no
echo "foreground: playing=$FG pos=$P1"

echo "== Swipe down on the player"
# Short swipe starting on the artist name under the song title (lower part of the player)
shot 1a-before-swipe >/dev/null
B=$(grep -o 'text="Rick Astley"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' "$OUT/1a-before-swipe.xml" | head -1 | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]')
read X1 Y1 X2 Y2 <<<"$(echo "$B" | tr -c '0-9' ' ')"
if [ -n "${Y2:-}" ]; then SX=$(( (X1 + X2) / 2 )); SY=$(( (Y1 + Y2) / 2 )); else SX=160; SY=330; fi
echo "swiping from the artist name at $SX,$SY"
adb shell input swipe $SX $SY $SX $((SY + 110)) 250
sleep 4
shot 1b-after-swipe
adb logcat -d -s Ember:I | grep -E 'swipe|heartbeat' | tail -4

echo "== Background: press HOME (with 'Don't keep activities' on, like aggressive phones)"
adb shell settings put global always_finish_activities 1
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

echo "== Incoming phone call (another app takes the audio)"
adb emu gsm call 5551234 || true
sleep 12
playing && CALL=yes || CALL=no
echo "during call: playing=$CALL"
adb emu gsm cancel 5551234 || true
sleep 15
playing && AFTER=yes || AFTER=no
echo "after call: playing=$AFTER"

echo "== Skip (with the app in the background)"
adb shell input keyevent KEYCODE_HOME
sleep 50

echo "== Back to the app"
MARK=$(adb logcat -d -s Ember:I | wc -l)
adb shell am start -n com.ember.music/.MainActivity >/dev/null
sleep 10
adb logcat -d -s Ember:I | tail -n +$((MARK + 1)) > "$OUT/return.log"
adb shell settings put global always_finish_activities 0
shot 4-returned

adb logcat -d -s Ember:I > "$OUT/ember.log"
adb logcat -d > "$OUT/full.log"
echo "================ Ember log (last 120 lines) ================"
grep -v 'console error' "$OUT/ember.log" | grep -v 'state {' | tail -80
echo "============================================================"
echo "RESULT foreground=$FG($P1) background=$BG($P2) screenoff=$OFF($P3)"

echo "--- other audio (phone call):"
grep -E 'paused by the system|call over|resuming' "$OUT/ember.log" || echo "(none)"
[ "$CALL" = no ] || { echo "FAIL: kept playing during the phone call"; exit 1; }
[ "$AFTER" = yes ] || { echo "FAIL: did not resume after the phone call"; exit 1; }

echo "--- coming back to the app:"
grep -E 'activity|page started|destroying player' "$OUT/return.log" || echo "(no screen changes)"
if grep -q 'page started' "$OUT/return.log" || grep -q 'destroying player' "$OUT/return.log"; then
  echo "FAIL: the app reset when coming back"; exit 1
fi

echo "--- song name shown:"
LAST_TITLE=$(grep 'state {' "$OUT/ember.log" | tail -1 | sed -E 's/.*"title":"([^"]*)".*/\1/')
echo "title=\"$LAST_TITLE\""
[ -n "$LAST_TITLE" ] || { echo "FAIL: no song name for the notification"; exit 1; }

echo "--- loop:"
grep -E "loop: |action repeat" "$OUT/ember.log" || echo "(none)"
grep -q 'Looping this playlist' "$OUT/ember.log" || { echo "FAIL: loop didn't go to playlist mode first"; exit 1; }
grep -q 'Looping this song' "$OUT/ember.log" || { echo "FAIL: loop didn't go to song mode second"; exit 1; }
grep -q 'loop: restarted song' "$OUT/ember.log" || { echo "FAIL: loop did not restart the song"; exit 1; }

echo "--- swipe down:"
grep -E 'swipe' "$OUT/ember.log" || echo "(none)"
grep -q 'swipe down -> closed player' "$OUT/ember.log" || { echo "FAIL: swipe down did not close the player"; exit 1; }

echo "--- like / save results:"
grep -E 'action (like|save)|playlists \{' "$OUT/ember.log" || echo "(none)"
grep -E 'page: (next|prev|new song|skip)' "$OUT/ember.log" || true
echo "--- skip timeline:"
grep -E 'TEST skip|page: (next|prev|new song|skip)|activity (stopped|started)' "$OUT/ember.log" || true
# The emulator can't stream a second song (YouTube blocks it there, with or without ad
# blocking), so skip passes once Ember switched to the right next song.
grep -qE 'page: next via' "$OUT/ember.log" || { echo "FAIL: skip to next song did not work"; exit 1; }
grep -qE 'page: (new song playing|skip: playing the new song)' "$OUT/ember.log" || echo "NOTE: the new song didn't stream on the emulator (expected)"
grep -q 'playlists {' "$OUT/ember.log" || { echo "FAIL: playlist picker got no answer"; exit 1; }
grep -qE 'action like ' "$OUT/ember.log" || { echo "FAIL: like button did nothing"; exit 1; }
grep -qE 'action save ' "$OUT/ember.log" || { echo "FAIL: save button did nothing"; exit 1; }

[ "$FG" = yes ] || { echo "FAIL: did not start playing in the foreground"; exit 1; }
# Position must keep changing (it can go back to 0 when the Loop check restarts the song)
[ "$BG" = yes ] && [ "${P2:-0}" != "${P1:-0}" ] || { echo "FAIL: stopped in background"; exit 1; }
[ "$OFF" = yes ] && [ "${P3:-0}" != "${P2:-0}" ] || { echo "FAIL: stopped with screen off"; exit 1; }
echo "PASS"
