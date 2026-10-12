#!/usr/bin/env bash
# Process death and force-stop persistence proof. Usage: phase3-lifecycle.sh [--stop]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
FAIL=0

bash spike-tools/emulator-up.sh
build_install
launch_app
wait_fg 20 >/dev/null || { say "service not foreground after launch"; exit 1; }

say "== set distinctive state"
set_state 4 -2 graphic on off off
sleep 2
BEFORE=$(dump_state b1)
say "BEFORE: $BEFORE"

say "== leg 1: kill -9 (run-as)"
OLD=$(pid_of)
say "adb shell run-as $PKG kill -9 $OLD"
"$ADB" shell run-as "$PKG" kill -9 "$OLD"
t0=$(now_s); BACK="no"
for i in $(seq 1 60); do
  NEW=$(pid_of)
  if [ -n "$NEW" ] && [ "$NEW" != "$OLD" ] && is_fg; then BACK="yes after $(( $(now_s) - t0 ))s (pid $OLD -> $NEW)"; break; fi
  sleep 0.5
done
say "FINDING sticky restart: $BACK"
[ "$BACK" = "no" ] && launch_app
AFTER=$(dump_state a1)
say "AFTER:  $AFTER"
if [ "$(fields "$BEFORE")" = "$(fields "$AFTER")" ]; then say "PASS leg 1 (kill -9)"; else say "FAIL leg 1 (kill -9)"; FAIL=1; fi

say "== leg 2: force-stop plus relaunch"
set_state -3 1.5 parametric on off on
sleep 2
BEFORE2=$(dump_state b2)
say "BEFORE: $BEFORE2"
say "adb shell am force-stop $PKG"
"$ADB" shell am force-stop "$PKG"
sleep 1
launch_app
AFTER2=$(dump_state a2)
say "AFTER:  $AFTER2"
if [ "$(fields "$BEFORE2")" = "$(fields "$AFTER2")" ]; then say "PASS leg 2 (force-stop)"; else say "FAIL leg 2 (force-stop)"; FAIL=1; fi

say "== cleanup"
cmd --es cmd set-global --es value off
cmd --es cmd set-bypass --es value off
sleep 1
[ "${1:-}" = "--stop" ] && bash spike-tools/emulator-down.sh
exit $FAIL
