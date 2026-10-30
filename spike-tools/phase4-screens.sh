#!/usr/bin/env bash
# Phase 4 evidence screenshots (UI-SPEC acceptance checks 1-5, 9, 10, 13, 14, light/dark).
# Builds and installs the debug app, grants POST_NOTIFICATIONS (so the normal screen shows, not the
# rationale banner), builds each screen state with the debug broadcast commands BEFORE launching the
# activity (the UI holder reads the core when it is created), and captures with `adb exec-out screencap`.
# Every device setting it changes is restored by the trap. Usage: phase4-screens.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
OUT="$ROOT/.planning/milestones/v0.1-phases/04-graphic-eq-ui/evidence"
mkdir -p "$OUT"

bash "$ROOT/spike-tools/emulator-up.sh"
build_install

ORIG_ACCEL=$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')
ORIG_ROT=$("$ADB" shell settings get system user_rotation | tr -d '\r')
restore() {
  "$ADB" shell cmd uimode night auto >/dev/null 2>&1 || true
  "$ADB" shell settings put system font_scale 1.0 >/dev/null 2>&1 || true
  "$ADB" shell wm size reset >/dev/null 2>&1 || true
  "$ADB" shell wm density reset >/dev/null 2>&1 || true
  "$ADB" shell settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null 2>&1 || true
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

shot() {  # shot <name>
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 3
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  say "wrote $1.png ($(wc -c < "$OUT/$1.png") bytes)"
}

"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0

# portrait light and dark
"$ADB" shell cmd uimode night no
reset_state 10 graphic; shape10; shot portrait-light
"$ADB" shell cmd uimode night yes
reset_state 10 graphic; shape10; shot portrait-dark
"$ADB" shell cmd uimode night no

# 31 bands, row swiped to the middle (the swipe starts on the frequency labels so no slider moves)
reset_state 31 graphic; g 2 5; g 8 -4; g 14 3; g 20 -3; g 26 4; cmd --es cmd set-preamp --ef db -6
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3
"$ADB" shell input swipe 900 1800 200 1800 500; sleep 1
"$ADB" exec-out screencap -p > "$OUT/graphic-31-scrolled.png"; say "wrote graphic-31-scrolled.png"

# parametric placeholder, bypass, clipping
reset_state 10 parametric; shape10; shot parametric
reset_state 10 graphic; shape10; cmd --es cmd set-bypass --es value on; shot bypass
reset_state 10 graphic; g 4 12; shot clipping

# landscape (800 x 360 dp class: the emulator window rotated)
"$ADB" shell settings put system user_rotation 1
reset_state 10 graphic; shape10; shot landscape
"$ADB" shell settings put system user_rotation 0

# 360 x 800 dp
"$ADB" shell wm size 1080x2400; "$ADB" shell wm density 480; sleep 2
reset_state 10 graphic; shape10; shot portrait-360x800-light
"$ADB" shell wm size reset; "$ADB" shell wm density reset

# 200% font
"$ADB" shell settings put system font_scale 2.0; sleep 1
reset_state 10 graphic; shape10; shot font-200
"$ADB" shell settings put system font_scale 1.0

# leave a flat default state behind
reset_state 10 graphic
say "done; notice.png is not produced (a runtime notice cannot be provoked cheaply)"
