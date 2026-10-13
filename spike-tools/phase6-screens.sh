#!/usr/bin/env bash
# Phase 6 README screenshots (L-08): Graphic light/dark, Parametric light/dark, Settings reading Version 0.1.0.
# Replaces any installed build with the debug build (visuals are identical; only the debug build has the
# state-setting broadcast commands), builds each state BEFORE launching, captures with `adb exec-out screencap`.
# Device settings it changes are restored by the trap. Exits non-zero on an empty screenshot or if the
# Settings screen does not show "Version 0.1.0". Usage: phase6-screens.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
OUT="${OUT:-$ROOT/docs}"
mkdir -p "$OUT"
STUB=com.dylenq.playerstub

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
(cd "$ROOT" && ./gradlew --offline :app:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

ORIG_ACCEL=$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')
ORIG_ROT=$("$ADB" shell settings get system user_rotation | tr -d '\r')
restore() {
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
  "$ADB" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" shell settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null 2>&1 || true
  "$ADB" shell input keyevent HOME >/dev/null 2>&1 || true
}
trap restore EXIT

g() { cmd --es cmd set-graphic-gain --ei index "$1" --ef db "$2"; }

# reset_state <bands> <mode> : flat curve, preamp 0, limiter on, EQ on, graphic or parametric
reset_state() {
  "$ADB" shell am force-stop "$PKG"
  cmd --es cmd set-band-count --ei count 31
  for i in $(seq 0 30); do g "$i" 0; done
  cmd --es cmd set-band-count --ei count "$1"
  for i in $(seq 0 $(( $1 - 1 ))); do g "$i" 0; done
  cmd --es cmd set-preamp --ef db 0
  cmd --es cmd set-limiter --es value on
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-global --es value off
  cmd --es cmd set-mode --es value "$2"
  sleep 1
}

# a non-flat curve with a narrow opposite-sign pair so both Target and Actual show
shape10() { g 1 6; g 2 -6; g 4 3; g 6 -2; g 8 4; g 9 -3; cmd --es cmd set-preamp --ef db -6; }

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

# Graphic: 10 bands, non-flat curve, preamp -6, limiter on
"$ADB" shell cmd uimode night no
reset_state 10 graphic; shape10; launch
snap screenshot-graphic-light
"$ADB" shell cmd uimode night yes
reset_state 10 graphic; shape10; launch
snap screenshot-graphic-dark

# Parametric: six bands of mixed kinds, band 2 selected so the overlay shows
"$ADB" shell cmd uimode night no
reset_parametric; launch
cmd --es cmd select-band --ei index 1; sleep 2
snap screenshot-parametric-light
"$ADB" shell cmd uimode night yes
reset_parametric; launch
cmd --es cmd select-band --ei index 1; sleep 2
snap screenshot-parametric-dark

# Settings, light, must show Version 0.1.0
"$ADB" shell cmd uimode night no
reset_state 10 graphic; shape10; launch
tap_node '^More options$'; sleep 1
tap_node '^Settings$'; sleep 2
if [ -z "$(find_node 'Version 0\.1\.0')" ]; then
  "$ADB" shell input swipe 540 1800 540 600 400; sleep 1
fi
[ -n "$(find_node 'Version 0\.1\.0')" ] || { say "Settings does not show Version 0.1.0"; exit 1; }
snap screenshot-settings

say "done"
