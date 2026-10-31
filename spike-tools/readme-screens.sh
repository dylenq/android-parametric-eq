#!/usr/bin/env bash
# README screenshots: Graphic light/dark, Parametric light/dark, the preset list, the Tone & balance card,
# and Settings showing the current versionName and the measured engine band count.
# Replaces any installed build with the debug build (visuals are identical; only the debug build has the
# state-setting broadcast commands), builds each state BEFORE launching, captures with `adb exec-out screencap`.
# Device settings it changes are restored by the trap. Exits non-zero on an empty screenshot or a missing
# screen element. Usage: readme-screens.sh   (SHOTS="graphic parametric presets tone settings" picks a subset)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
OUT="${OUT:-$ROOT/docs}"
mkdir -p "$OUT"
VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
SHOTS="${SHOTS:-graphic parametric presets tone settings}"
want() { case " $SHOTS " in *" $1 "*) return 0 ;; *) return 1 ;; esac; }

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
(cd "$ROOT" && ./gradlew --offline :app:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

ORIG_ACCEL=$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')
ORIG_ROT=$("$ADB" shell settings get system user_rotation | tr -d '\r')
restore() {
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
  "$ADB" shell settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null 2>&1 || true
  "$ADB" shell input keyevent HOME >/dev/null 2>&1 || true
}
trap restore EXIT

g() { cmd --es cmd set-graphic-gain --ei index "$1" --ef db "$2"; }
tone() { cmd --es cmd set-bass --ef db "$1"; cmd --es cmd set-treble --ef db "$2"; cmd --es cmd set-balance --ef value "$3"; }

# reset_graphic : 10 flat bands, preamp 0, tone and balance centred, limiter on, EQ on, graphic mode
reset_graphic() {
  "$ADB" shell am force-stop "$PKG"
  cmd --es cmd set-mode --es value graphic
  cmd --es cmd set-band-count --ei count 10
  for i in $(seq 0 9); do g "$i" 0; done
  cmd --es cmd set-preamp --ef db 0
  tone 0 0 0
  cmd --es cmd set-limiter --es value on
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-global --es value off
  sleep 1
}

# a non-flat curve with a narrow opposite-sign pair so both Target and Actual show, saved as a user preset
shape10() {
  g 1 6; g 2 -6; g 4 3; g 6 -2; g 8 4; g 9 -3
  tone 2 0 0
  cmd --es cmd set-preamp --ef db -8.5
  cmd --es cmd apply-preset --es key "'user:My headphones'" 2>/dev/null || true
}

# reset_parametric : six bands of mixed kinds, parametric mode, saved as a user preset
reset_parametric() {
  "$ADB" shell am force-stop "$PKG"
  cmd --es cmd set-mode --es value parametric
  for _ in $(seq 1 16); do cmd --es cmd remove-band --ei index 0; done
  # preamp -8 dB leaves positive headroom, so no red "Over by" chip shows in the README shots
  cmd --es cmd set-preamp --ef db -8
  tone 0 0 0
  cmd --es cmd set-limiter --es value on
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-global --es value off
  # kind freq gain q enabled
  local specs=(
    "low-shelf 90 4 0.7 true"
    "peak 250 -5 1.4 true"
    "peak 1200 6 2.0 false"
    "peak 3100 3.5 1.0 true"
    "high-pass 30 0 0.7 true"
    "high-shelf 9000 -4 0.7 true"
  )
  local i=0 s k f gn q e
  for s in "${specs[@]}"; do
    read -r k f gn q e <<< "$s"
    cmd --es cmd add-band
    cmd --es cmd set-band --ei index "$i" --es kind "$k" --ef freq "$f" --ef gain "$gn" --ef q "$q" --ez enabled "$e"
    i=$((i + 1))
  done
  sleep 1
}

launch() { "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3; }
snap() {  # snap <name>
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  [ -s "$OUT/$1.png" ] || { say "EMPTY screenshot $1"; exit 1; }
  say "wrote $1.png ($(wc -c < "$OUT/$1.png") bytes)"
}

# find_node <regex> : centre "x y" of the first uiautomator node whose text or content-desc matches (empty if none)
find_node() {
  "$ADB" shell uiautomator dump /sdcard/readme-ui.xml >/dev/null 2>&1 || true
  "$ADB" exec-out cat /sdcard/readme-ui.xml | python3 -I -c '
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
  "$ADB" shell input tap $xy
}
need_node() { [ -n "$(find_node "$1")" ] || { say "screen does not show /$1/"; exit 1; }; }
# scroll by dragging on the limiter text / band-list rows (drags there are not claimed by a slider)
scroll_to() {  # scroll_to <regex> : swipe up until the node is on screen, at most 5 times
  for _ in 1 2 3 4 5; do
    [ -n "$(find_node "$1")" ] && return 0
    "$ADB" shell input swipe 300 1900 300 1100 400; sleep 1
  done
  need_node "$1"
}

"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0

# The user presets the shots show. Saving happens with the matching sound in place.
"$ADB" shell cmd uimode night no
reset_graphic; g 1 6; g 2 -6; g 4 3; g 6 -2; g 8 4; g 9 -3; tone 2 0 0; cmd --es cmd set-preamp --ef db -8.5
cmd --es cmd save-preset --es name "'My headphones'"; cmd --es cmd flush; sleep 1
reset_parametric
cmd --es cmd save-preset --es name "'Desk speakers'"; cmd --es cmd flush; sleep 1
reset_graphic; tone 4 1.5 0; cmd --es cmd set-preamp --ef db -5
cmd --es cmd save-preset --es name "'Late night'"; cmd --es cmd flush; sleep 1

if want graphic; then
  for theme in light dark; do
    [ "$theme" = dark ] && "$ADB" shell cmd uimode night yes || "$ADB" shell cmd uimode night no
    reset_graphic; shape10; launch
    need_node '^Preset: My headphones'
    snap "screenshot-graphic-$theme"
  done
fi

if want parametric; then
  for theme in light dark; do
    [ "$theme" = dark ] && "$ADB" shell cmd uimode night yes || "$ADB" shell cmd uimode night no
    reset_parametric; cmd --es cmd apply-preset --es key "'user:Desk speakers'"; launch
    cmd --es cmd select-band --ei index 1; sleep 2
    need_node '^Preset: Desk speakers'
    snap "screenshot-parametric-$theme"
  done
fi

"$ADB" shell cmd uimode night no

if want presets; then
  reset_graphic; shape10; launch
  tap_node '^Preset: '; sleep 2
  need_node 'Save as new'
  snap screenshot-presets
  "$ADB" shell input keyevent BACK; sleep 1
fi

if want tone; then
  reset_graphic; g 1 3; g 3 -2; g 7 2
  tone 4.5 -3 0.3
  cmd --es cmd set-preamp --ef db -8
  launch
  scroll_to '^Balance'
  sleep 1
  snap screenshot-tone
fi

if want settings; then
  reset_graphic; shape10; launch
  tap_node '^More options$'; sleep 1
  tap_node '^Settings$'; sleep 2
  scroll_to "Version ${VERSION//./\\.}"
  need_node 'Engine bands: [0-9]+ \(measured\)'
  snap screenshot-settings
fi

say "done"
