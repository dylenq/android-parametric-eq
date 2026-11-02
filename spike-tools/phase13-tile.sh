#!/usr/bin/env bash
# Phase 13 emulator check: the Quick Settings tile. A real tile click (cmd statusbar click-tile) flips bypass,
# a second click flips it back, a click with the app process stopped still flips it and the state is saved,
# and the emulator's tile list is restored afterwards. Emulator only: it checks state, not how the tile looks
# or feels on a phone.
# Usage: phase13-tile.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"

TILE="$PKG/com.dylenq.eq.tile.EqTileService"
fail() { echo "FAIL: $*" >&2; exit 1; }
bypass_of() { echo "$1" | sed -E 's/.* bypass=([a-z]+) .*/\1/'; }
bypass_now() { local s; s=$(dump_state "p13-$RANDOM") || fail "no dump-state"; bypass_of "$s"; }

bash "$ROOT/spike-tools/emulator-up.sh"

# Remember the tile list so the emulator is left as found.
SAVED_TILES=$("$ADB" shell settings get secure sysui_qs_tiles | tr -d '\r')
restore() {
  "$ADB" shell cmd statusbar collapse >/dev/null 2>&1 || true
  "$ADB" shell cmd statusbar remove-tile "$TILE" >/dev/null 2>&1 || true
  if [ "$SAVED_TILES" = null ] || [ -z "$SAVED_TILES" ]; then
    "$ADB" shell settings delete secure sysui_qs_tiles >/dev/null 2>&1 || true
  else
    "$ADB" shell settings put secure sysui_qs_tiles "$SAVED_TILES" >/dev/null 2>&1 || true
  fi
}
trap restore EXIT

"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
(./gradlew --offline :app:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
"$ADB" shell input keyevent HOME
cmd --es cmd set-bypass --es value off
sleep 1
[ "$(bypass_now)" = false ] || fail "could not start with bypass off"

say "adding the tile"
"$ADB" shell cmd statusbar add-tile "$TILE" >/dev/null
sleep 2
case "$("$ADB" shell settings get secure sysui_qs_tiles | tr -d '\r')" in *EqTileService*) ;; *) fail "the tile was not added";; esac

# The system only binds a tile that the open Quick Settings panel shows, so open it first.
click() {
  "$ADB" shell cmd statusbar expand-settings
  sleep 2
  "$ADB" shell cmd statusbar click-tile "$TILE" >/dev/null
  sleep 2
}

say "click 1: expect bypass on"
click
[ "$(bypass_now)" = true ] || fail "bypass did not turn on after the first click"
say "click 2: expect bypass off again"
click
[ "$(bypass_now)" = false ] || fail "bypass did not turn off after the second click"

say "stopping the app process, then click: expect bypass on, saved"
"$ADB" shell am force-stop "$PKG"
sleep 1
[ -z "$(pid_of)" ] || fail "the app process was still running"
is_fg && fail "the service was still in the foreground"
click
sleep 2  # the save is debounced
"$ADB" shell am force-stop "$PKG"
sleep 1
[ "$(bypass_now)" = true ] || fail "the click with no service did not flip bypass, or it was not saved"
is_fg && fail "the tile started the foreground service"

cmd --es cmd set-bypass --es value off
echo "PASS: tile clicks flip bypass on and off, also with the app stopped, and the state persists"
