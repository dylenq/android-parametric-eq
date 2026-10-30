#!/usr/bin/env bash
# Phase 8 evidence screenshots: the Tone & balance card in Graphic and Parametric mode (light theme, portrait).
# Installs the debug app, sets bass/treble/balance with the debug broadcast commands BEFORE launching, then
# captures the screen and the screen scrolled to the card. Device settings it changes are restored on exit.
# Usage: phase8-screens.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
OUT="${OUT:-$ROOT/.planning/phases/08-tone-and-balance/evidence}"
mkdir -p "$OUT"

bash "$ROOT/spike-tools/emulator-up.sh"
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

launch() { "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 3; }
snap() {
  "$ADB" exec-out screencap -p > "$OUT/$1.png"
  [ -s "$OUT/$1.png" ] || { say "EMPTY screenshot $1"; exit 1; }
  say "wrote $1.png ($(wc -c < "$OUT/$1.png") bytes)"
}
# scroll up by dragging on the limiter text / band-list rows (drags there are not claimed by a slider)
scroll_down() { for _ in 1 2 3; do "$ADB" shell input swipe 300 2100 300 700 400; sleep 1; done; }

"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0
"$ADB" shell cmd uimode night no

prepare() {  # prepare <mode>
  "$ADB" shell am force-stop "$PKG"
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-preamp --ef db -3
  cmd --es cmd set-bass --ef db 4.5
  cmd --es cmd set-treble --ef db -3
  cmd --es cmd set-balance --ef value 0.3
  cmd --es cmd set-mode --es value "$1"
  sleep 1
}

prepare graphic; launch
snap tone-graphic
scroll_down
snap tone-graphic-scrolled

prepare parametric; launch
snap tone-parametric
scroll_down
snap tone-parametric-scrolled

# leave the device flat again
cmd --es cmd set-bass --ef db 0
cmd --es cmd set-treble --ef db 0
cmd --es cmd set-balance --ef value 0
cmd --es cmd set-preamp --ef db 0
cmd --es cmd set-mode --es value graphic
