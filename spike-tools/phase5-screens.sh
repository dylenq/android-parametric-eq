#!/usr/bin/env bash
# Phase 5 evidence screenshots (L-13): parametric with several bands, selected band + overlay, expanded row with
# the keyboard open, long-press menu, Settings with a session row, Settings with global mode on, landscape, 360x800.
# Builds and installs the debug app + playerstub, grants POST_NOTIFICATIONS, builds each state with the debug
# broadcast commands BEFORE launching (the UI holder reads the core when it is created), drives real touches
# with `adb shell input`, finds targets with uiautomator, captures with `adb exec-out screencap`.
# Every device setting it changes is restored by the trap. Exits non-zero if a screenshot file is empty.
# Usage: phase5-screens.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
OUT="${OUT:-$ROOT/.planning/milestones/v0.1-phases/05-parametric-eq-ui/evidence}"
mkdir -p "$OUT"
STUB=com.dylenq.playerstub

bash "$ROOT/spike-tools/emulator-up.sh"
(cd "$ROOT" && ./gradlew --offline :app:installDebug :playerstub:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

ORIG_ACCEL=$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')
ORIG_ROT=$("$ADB" shell settings get system user_rotation | tr -d '\r')
ORIG_IME_SHOW=$("$ADB" shell settings get secure show_ime_with_hard_keyboard | tr -d '\r')
restore() {
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
  "$ADB" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" shell settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null 2>&1 || true
  cmd --es cmd set-global --es value off >/dev/null 2>&1 || true
  "$ADB" shell am broadcast -n "$STUB/.TriggerReceiver" -a "$STUB.SEND" --ei session 4521 --ei open 0 >/dev/null 2>&1 || true
  "$ADB" shell input keyevent HOME >/dev/null 2>&1 || true
}
trap restore EXIT

# reset_parametric : delete every band, then add five of different kinds (flat otherwise), parametric mode
reset_parametric() {
  "$ADB" shell am force-stop "$PKG"
  for _ in $(seq 1 16); do cmd --es cmd remove-band --ei index 0; done
  cmd --es cmd set-preamp --ef db 0
  cmd --es cmd set-limiter --es value on
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-global --es value off
  # name kind freq gain q enabled
  local specs=(
    "low-shelf 90 4 0.7 true"
    "peak 250 -5 1.4 true"
    "peak 1200 6 2.0 false"
    "peak 3100 3.5 1.0 true"
    "high-pass 30 0 0.7 true"
    "high-shelf 9000 -4 0.7 true"
  )
  local i=0 s k f g q e
  for s in "${specs[@]}"; do
    read -r k f g q e <<< "$s"
    cmd --es cmd add-band
    cmd --es cmd set-band --ei index "$i" --es kind "$k" --ef freq "$f" --ef gain "$g" --ef q "$q" --ez enabled "$e"
    i=$((i + 1))
  done
  cmd --es cmd set-mode --es value parametric
  sleep 1
}

launch() { "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3; }
snap() {  # snap <name>
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  [ -s "$OUT/$1.png" ] || { say "EMPTY screenshot $1"; exit 1; }
  say "wrote $1.png ($(wc -c < "$OUT/$1.png") bytes)"
}

# find <regex> : centre "x y" of the first uiautomator node whose text or content-desc matches the regex (empty if none)
find_node() {
  "$ADB" shell uiautomator dump /sdcard/p5ui.xml >/dev/null 2>&1 || true
  "$ADB" exec-out cat /sdcard/p5ui.xml | python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
rx = re.compile(sys.argv[1])
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            a, b, c, d = map(int, m.groups())
            print((a + c) // 2, (b + d) // 2)
            break
' "$1"
}
tap_node() {  # tap_node <regex>
  local xy; xy=$(find_node "$1")
  [ -n "$xy" ] || { say "no node matches /$1/"; return 1; }
  say "tap /$1/ at $xy"
  "$ADB" shell input tap $xy
}

"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0
"$ADB" shell cmd uimode night no

# 1 + 2: several bands, then a selected band with its overlay
reset_parametric; launch
snap parametric-several-bands
cmd --es cmd select-band --ei index 1; sleep 2
snap selected-band-overlay

# 4: long-press menu on a node centre (swipe with identical endpoints held 700 ms)
xy=$(find_node '^Band 4, ')
if [ -n "$xy" ]; then
  say "long-press node at $xy"
  "$ADB" shell input swipe $xy $xy 700; sleep 1
  snap long-press-menu
  "$ADB" shell input keyevent BACK; sleep 1
else
  say "node 'Band 4' not found in uiautomator dump; long-press-menu.png NOT captured"
fi

# 3: expanded row, Frequency field focused, keyboard open
reset_parametric; launch
cmd --es cmd select-band --ei index 1; sleep 2
if tap_node '^Band 2 frequency'; then
  sleep 2
  IME=$("$ADB" shell dumpsys input_method | tr -d '\r' | grep -m1 'mInputShown' || true)
  say "IME state: $IME"
  snap band-row-keyboard
else
  say "frequency field not found: band-row-keyboard.png NOT captured (expand the row first)"
fi
"$ADB" shell input keyevent BACK; sleep 1

# 5: Settings with a session row
reset_parametric; launch
"$ADB" shell am broadcast -n "$STUB/.TriggerReceiver" -a "$STUB.SEND" --ei session 4521 --ei open 1 >/dev/null
sleep 2
tap_node '^More options$'; sleep 1
tap_node '^Settings$'; sleep 2
snap settings-session-row
"$ADB" shell input keyevent BACK; sleep 1

# 6: Settings with global mode on
cmd --es cmd set-global --es value on; sleep 2
tap_node '^More options$'; sleep 1
tap_node '^Settings$'; sleep 2
snap settings-global-notice
"$ADB" shell input keyevent BACK; sleep 1
cmd --es cmd set-global --es value off
"$ADB" shell am broadcast -n "$STUB/.TriggerReceiver" -a "$STUB.SEND" --ei session 4521 --ei open 0 >/dev/null

# 7: landscape
"$ADB" shell settings put system user_rotation 1
reset_parametric; launch
cmd --es cmd select-band --ei index 3; sleep 2
snap landscape
"$ADB" shell settings put system user_rotation 0

# 8: 360 x 800 dp
"$ADB" shell wm size 1080x2400; "$ADB" shell wm density 480; sleep 2
reset_parametric; launch
cmd --es cmd select-band --ei index 1; sleep 2
snap portrait-360x800
"$ADB" shell wm size reset; "$ADB" shell wm density reset

say "done"
