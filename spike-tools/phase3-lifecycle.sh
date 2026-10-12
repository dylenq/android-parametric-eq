#!/usr/bin/env bash
# Process death and force-stop persistence proof. Usage: phase3-lifecycle.sh [--stop]
# Exit status: 0 = all legs pass and the sticky restart happened; 2 = state legs pass but the
# sticky restart missed (a reported finding, classified in the STICKY_OUTCOME= line);
# 1 = any state-equality leg failed (or setup failed).
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
ELAPSED=$(( $(now_s) - t0 ))
say "FINDING sticky restart wait: $BACK"
capture_restart_evidence leg1
CLASS=$(classify_restart "$OLD")
DETAIL="${ELAPSED}s waited; $(restart_delay_detail)"
STICKY_MISS=0
[ "$CLASS" = "RESTARTED_STICKY" ] || STICKY_MISS=1
say "STICKY_OUTCOME=$CLASS detail=$DETAIL"
if [ "$STICKY_MISS" = 1 ]; then
  say "recovery launch (after classification)"
  launch_app
fi
AFTER=$(dump_state a1)
say "AFTER:  $AFTER"
if [ "$(fields "$BEFORE")" = "$(fields "$AFTER")" ]; then say "PASS leg 1 (kill -9)"; else say "FAIL leg 1 (kill -9)"; FAIL=1; fi

say "== leg 1b: repeated kills, 5 s apart (back-off probe)"
for n in 1 2 3; do
  sleep 5
  P=$(pid_of)
  if [ -z "$P" ]; then say "BACKOFF_PROBE kill=$n outcome=NO_PROCESS_TO_KILL detail=$(restart_delay_detail)"; continue; fi
  "$ADB" shell run-as "$PKG" kill -9 "$P" || true
  for i in $(seq 1 20); do
    N=$(pid_of)
    if [ -n "$N" ] && [ "$N" != "$P" ] && is_fg; then break; fi
    sleep 0.5
  done
  capture_restart_evidence "probe$n"
  say "BACKOFF_PROBE kill=$n outcome=$(classify_restart "$P") detail=$(restart_delay_detail)"
done
if [ -z "$(pid_of)" ] || ! is_fg; then launch_app; fi

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
if [ "$FAIL" != 0 ]; then exit 1; fi
if [ "$STICKY_MISS" = 1 ]; then exit 2; fi
exit 0
