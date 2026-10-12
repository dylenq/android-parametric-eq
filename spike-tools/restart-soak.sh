#!/usr/bin/env bash
# Sticky-restart crash soak. Usage: restart-soak.sh [cycles=36] [--keep]
# Each cycle: force-stop (resets the platform restart back-off), launch the app, vary what happens
# right before `run-as ... kill -9` (see VARIANTS), wait for the START_STICKY restart, let the new
# process live a few seconds, then capture the crash buffer, AndroidRuntime and ActivityManager.
# A cycle FAILS if there is no restart within the wait, the restarted process dies, or the crash
# buffer / AndroidRuntime has anything in it. Raw logs of failing cycles go to build/restart-soak/.
# Exit 0 = every cycle clean, 1 = at least one failing cycle. Needs a running emulator (emulator-up.sh).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
N="${1:-36}"
OUT="$ROOT/build/restart-soak"
mkdir -p "$OUT"

VARIANTS=(idle edit_now edit_100ms edit_300ms edit_700ms kill_at_launch global_on edit_stream corrupt_state tmp_leftover global_edit cpu_load reinstall)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
FILES="files"  # run-as relative path to filesDir

edit() { "$ADB" shell am broadcast -n "$RCV" --es cmd set-graphic-gain --ei index 3 --ef db "$1" >/dev/null; }

pass=0; failn=0
declare -a SUMMARY
for c in $(seq 1 "$N"); do
  v="${VARIANTS[$(( (c - 1) % ${#VARIANTS[@]} ))]}"
  "$ADB" shell am force-stop "$PKG"
  "$ADB" logcat -c
  "$ADB" logcat -b crash -c 2>/dev/null || true
  sleep 1
  case "$v" in
    corrupt_state) "$ADB" shell run-as "$PKG" sh -c "'printf \"{\\\"version\\\":1,\\\"mode\" > $FILES/eq-state.json'" ;;
    tmp_leftover)  "$ADB" shell run-as "$PKG" sh -c "'printf \"{partial\" > $FILES/eq-state.json.tmp'" ;;
  esac
  if [ "$v" = reinstall ]; then "$ADB" install -r "$APK" >/dev/null; "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null; fi
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  if [ "$v" = kill_at_launch ]; then
    # kill as soon as the service exists (before that there is nothing for the platform to restart)
    for i in $(seq 1 100); do
      "$ADB" shell dumpsys activity services "$PKG" 2>/dev/null | grep -q 'ServiceRecord.*EqService' && break
    done
  else
    wait_fg 20 >/dev/null || say "cycle $c: no fg before kill"
    sleep 1
  fi
  case "$v" in
    edit_now)   edit $(( c % 7 - 3 )) ;;
    edit_100ms) edit $(( c % 7 - 3 )); sleep 0.1 ;;
    edit_300ms) edit $(( c % 7 - 3 )); sleep 0.3 ;;
    edit_700ms) edit $(( c % 7 - 3 )); sleep 0.7 ;;
    global_on)  "$ADB" shell am broadcast -n "$RCV" --es cmd set-global --es value on >/dev/null; sleep 0.5 ;;
    global_edit) "$ADB" shell am broadcast -n "$RCV" --es cmd set-global --es value on >/dev/null; edit $(( c % 7 - 3 )) ;;
    cpu_load)   edit $(( c % 7 - 3 ));  # four busy loops for 12 s, in a subshell so `wait` below ignores them
                ( "$ADB" shell 'for i in 1 2 3 4; do timeout 12 sh -c "while :; do :; done" & done; wait' >/dev/null 2>&1 & ); sleep 0.5 ;;
    edit_stream)
      ( for k in 1 2 3 4 5 6 7 8; do edit $(( k % 5 - 2 )); done ) &
      sleep 1.2 ;;
  esac
  OLD=$(pid_of)
  [ "$v" = kill_at_launch ] && [ -z "$OLD" ] && { sleep 0.3; OLD=$(pid_of); }
  [ -n "$OLD" ] && "$ADB" shell run-as "$PKG" kill -9 "$OLD" || say "cycle $c: nothing to kill"
  wait
  T0=$(now_s); NEW=""; BACK=no
  for i in $(seq 1 40); do
    NEW=$(pid_of)
    if [ -n "$NEW" ] && [ "$NEW" != "$OLD" ] && is_fg; then BACK=yes; break; fi
    sleep 0.5
  done
  W=$(( $(now_s) - T0 ))
  sleep 4  # a restart that dies ~1 s after start shows here
  LIVE=$(pid_of)
  CR=$("$ADB" logcat -d -b crash 2>&1 | tr -d '\r' | grep -v '^---' || true)
  AR=$("$ADB" logcat -d -s AndroidRuntime:E 2>&1 | tr -d '\r' | grep -v '^---' || true)
  AM=$("$ADB" logcat -d -s ActivityManager:I 2>&1 | tr -d '\r' | grep "$PKG" | grep -iE 'died|Scheduling restart|crash dialog' || true)
  status=OK
  [ "$BACK" = yes ] || status=NO_RESTART
  [ -n "$LIVE" ] && [ "$LIVE" = "$NEW" ] || status=DIED_AFTER_RESTART
  [ -z "$CR$AR" ] || status=CRASH
  if [ "$status" = OK ]; then pass=$((pass + 1)); else
    failn=$((failn + 1))
    {
      echo "== cycle $c variant=$v status=$status old=$OLD new=$NEW live=$LIVE waited=${W}s"
      echo "-- crash buffer"; echo "$CR"; echo "-- AndroidRuntime"; echo "$AR"; echo "-- ActivityManager"; echo "$AM"
      echo "-- EQ-SVC/EQ-DEBUG"; "$ADB" logcat -d -s EQ-SVC EQ-DEBUG 2>&1 | tr -d '\r'
      echo "-- full logcat tail"; "$ADB" logcat -d 2>&1 | tr -d '\r' | tail -150
    } > "$OUT/fail-cycle-$c-$v.log"
  fi
  say "cycle $c/$N variant=$v $status old=$OLD new=$NEW waited=${W}s"
  SUMMARY+=("$c:$v:$status")
done
say "SOAK cycles=$N ok=$pass failed=$failn"
printf '%s\n' "${SUMMARY[@]}" > "$OUT/summary.txt"
[ "$failn" = 0 ]
