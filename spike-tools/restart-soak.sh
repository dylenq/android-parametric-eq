#!/usr/bin/env bash
# Sticky-restart crash soak. Usage: restart-soak.sh [cycles=3*variants] [--keep] [--list]
#   --list   print the cycle -> variant plan and exit 0 without touching adb (no emulator needed)
#   ONLY=<name>  environment filter: run only variants equal to <name> or starting with "<name>_"
#                (e.g. ONLY=session_in_window, ONLY=edit_now) for quick smoke runs
# Each cycle: force-stop (resets the platform restart back-off), launch the app, vary what happens
# right before `run-as ... kill -9` (see VARIANTS), wait for the START_STICKY restart, let the new
# process live a few seconds, then capture the crash buffer, AndroidRuntime and ActivityManager.
# A cycle FAILS if there is no restart within the wait, the restarted process dies, or the crash
# buffer / AndroidRuntime has anything in it. Raw logs of failing cycles go to build/restart-soak/ and a
# copy to .planning/debug/restart-soak-fail-<cycle>-<variant>-<UTC>.log with a FAILING CYCLE banner.
# A failing cycle is counted and the soak moves on: there is NO retry of a cycle until it is green.
#
# session_* variants (hypothesis "session broadcast during the sticky-restart window", see
# .planning/debug/sticky-restart-crash.md): the playerstub app (com.dylenq.playerstub/.TriggerReceiver)
# sends the implicit OPEN/CLOSE audio-effect session broadcast. The kill -9 and the broadcast are issued
# from ONE device-side shell command, so host adb latency does not blur the offset after the kill.
# Extra pass criteria for these: exactly one new app process start (the broadcast-started process and
# the service restart converge on one runtime; the app has no worker-start log line, so the ActivityManager
# "Start proc" count for the package is the proxy) and dump-state sessions=1 after OPEN-only variants
# (1..10 for session_burst), 0 after session_open_close. Every session variant ends with CLOSE broadcasts.
# Exit 0 = every cycle clean, 1 = at least one failing cycle. Needs a running emulator (emulator-up.sh).
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
LIST=no; N=""
for a in "$@"; do
  case "$a" in
    --list) LIST=yes ;;
    --keep) ;;
    *) N="$a" ;;
  esac
done
OUT="$ROOT/build/restart-soak"
DEBUG_DIR="$ROOT/.planning/debug"

ALL_VARIANTS=(idle edit_now edit_100ms edit_300ms edit_700ms kill_at_launch global_on edit_stream corrupt_state tmp_leftover global_edit cpu_load reinstall
  session_at_kill session_in_window_150 session_in_window_400 session_in_window_800 session_in_window_1200 session_in_window_2000
  session_open_close session_burst session_global session_edit)
VARIANTS=()
for v in "${ALL_VARIANTS[@]}"; do
  if [ -z "${ONLY:-}" ] || [ "$v" = "$ONLY" ] || [[ "$v" == "${ONLY}_"* ]]; then VARIANTS+=("$v"); fi
done
[ "${#VARIANTS[@]}" -gt 0 ] || { echo "ONLY=${ONLY:-} matches no variant" >&2; exit 2; }
# every variant (and every in-window offset) at least 3 times by default
N="${N:-$(( 3 * ${#VARIANTS[@]} ))}"

if [ "$LIST" = yes ]; then
  echo "restart-soak plan: $N cycles over ${#VARIANTS[@]} variants (no adb used)"
  for c in $(seq 1 "$N"); do echo "cycle $c: ${VARIANTS[$(( (c - 1) % ${#VARIANTS[@]} ))]}"; done
  exit 0
fi
mkdir -p "$OUT" "$DEBUG_DIR"
STUB=com.dylenq.playerstub
STUB_RCV="$STUB/.TriggerReceiver"
STUB_ACT="$STUB.SEND"
is_session_variant() { case "$1" in session_*) return 0;; *) return 1;; esac; }

# Pre-step: playerstub must be installed when any session variant is in the plan.
for v in "${VARIANTS[@]}"; do
  if is_session_variant "$v"; then
    ( cd "$ROOT" && ./gradlew --offline :playerstub:installDebug >/dev/null ) || { echo "playerstub installDebug failed" >&2; exit 2; }
    "$ADB" shell pm list packages "$STUB" | tr -d '\r' | grep -q "package:$STUB\$" || { echo "playerstub ($STUB) is not installed on the device; session variants need it" >&2; exit 2; }
    break
  fi
done

# Warm the stub: the first broadcast to a just-installed playerstub cold-starts it and was seen delivered about
# 6 s late (harness artefact, plan 05-12 smoke: session_at_kill cycle 1 read sessions=0 because OPEN reached the
# app after dump-state; warm cycles were fine). Send a CLOSE for an unused session and wait for the stub process.
warm_stub() {
  "$ADB" shell am broadcast -n "$STUB_RCV" -a "$STUB_ACT" --ei session 7999 --ei open 0 >/dev/null 2>&1 || true
  for _ in $(seq 1 30); do
    "$ADB" shell pidof "$STUB" 2>/dev/null | tr -d '\r' | grep -q '[0-9]' && break
    sleep 0.5
  done
  sleep 3
}
for v in "${VARIANTS[@]}"; do is_session_variant "$v" && { warm_stub; break; }; done

# stub_bc <session> <open 1|0> : the device-side broadcast command text
# pattern: am broadcast -n com.dylenq.playerstub/.TriggerReceiver -a com.dylenq.playerstub.SEND --ei session N --ei open 1
stub_bc() { echo "am broadcast -n $STUB_RCV -a $STUB_ACT --ei session $1 --ei open $2 >/dev/null 2>&1"; }
# ms_to_sleep <ms> : fractional seconds for toybox sleep
ms_to_sleep() { printf '%d.%03d' $(( $1 / 1000 )) $(( $1 % 1000 )); }
# start_procs : count of ActivityManager "Start proc" lines for the app package in the current logcat
start_procs() { "$ADB" logcat -d -s ActivityManager:I 2>/dev/null | tr -d '\r' | grep -c "Start proc [0-9]*:$PKG/" || true; }
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
  EXPECT_SESS=""; OPENED=""
  if is_session_variant "$v"; then
    if [ "$v" = session_global ]; then
      "$ADB" shell am broadcast -n "$RCV" --es cmd set-global --es value on >/dev/null; sleep 0.5
    fi
    case "$v" in
      session_at_kill)       OFFSET_MS=0 ;;
      session_in_window_*)   OFFSET_MS="${v##*_}" ;;
      *)                     OFFSET_MS=400 ;;
    esac
    BASE_STARTS=$(start_procs)
    PRE="run-as $PKG kill -9 $OLD;"
    [ "$OFFSET_MS" -gt 0 ] && PRE="$PRE sleep $(ms_to_sleep "$OFFSET_MS");"
    case "$v" in
      session_open_close)
        BODY="$(stub_bc 7001 1); sleep 0.2; $(stub_bc 7001 0)"; EXPECT_SESS=0; OPENED="7001" ;;
      session_burst)
        BODY=""; for sid in 7001 7002 7003 7004 7005 7006 7007 7008 7009 7010; do BODY="$BODY $(stub_bc $sid 1); sleep 0.05;"; done
        EXPECT_SESS="1-10"; OPENED="7001 7002 7003 7004 7005 7006 7007 7008 7009 7010" ;;
      session_edit)
        BODY="$(stub_bc 7001 1); am broadcast -n $RCV --es cmd set-graphic-gain --ei index 3 --ef db $(( c % 7 - 3 )) >/dev/null 2>&1"
        EXPECT_SESS=1; OPENED="7001" ;;
      *)
        BODY="$(stub_bc 7001 1)"; EXPECT_SESS=1; OPENED="7001" ;;
    esac
    # ONE device-side command: kill, optional offset sleep, broadcast(s). No host adb call in between.
    if [ -n "$OLD" ]; then "$ADB" shell "$PRE $BODY" >/dev/null 2>&1 || true; else say "cycle $c: nothing to kill"; fi
  else
    [ -n "$OLD" ] && "$ADB" shell run-as "$PKG" kill -9 "$OLD" || say "cycle $c: nothing to kill"
  fi
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
  LOGSNAP=$("$ADB" logcat -d 2>&1 | tr -d '\r' | tail -150)
  SESS_NOTE=""
  status=OK
  [ "$BACK" = yes ] || status=NO_RESTART
  [ -n "$LIVE" ] && [ "$LIVE" = "$NEW" ] || status=DIED_AFTER_RESTART
  if [ "$status" = OK ] && [ -n "$EXPECT_SESS" ]; then
    # converge on one runtime: exactly one process start since the kill
    STARTS=$(( $(start_procs) - BASE_STARTS ))
    SESS_NOTE="starts=$STARTS"
    [ "$STARTS" = 1 ] || status=RUNTIME_SPLIT
    DS=$(dump_state "soak$c") || DS=""
    SESS=$(echo "$DS" | sed -nE 's/.* sessions=([0-9]+).*/\1/p')
    SESS_NOTE="$SESS_NOTE sessions=${SESS:-?}"
    if [ -z "$SESS" ]; then [ "$status" != OK ] || status=DUMP_FAILED
    elif [ "$EXPECT_SESS" = "1-10" ]; then { [ "$SESS" -ge 1 ] && [ "$SESS" -le 10 ]; } || { [ "$status" != OK ] || status=SESSION_MISMATCH; }
    elif [ "$SESS" != "$EXPECT_SESS" ]; then [ "$status" != OK ] || status=SESSION_MISMATCH; fi
    # explicit CLOSE cleanup for every session this variant opened
    for sid in $OPENED; do "$ADB" shell "$(stub_bc "$sid" 0)" >/dev/null 2>&1 || true; done
    sleep 1
  fi
  [ -z "$CR$AR" ] || status=CRASH
  if [ "$status" = OK ]; then pass=$((pass + 1)); else
    failn=$((failn + 1))
    {
      echo "== cycle $c variant=$v status=$status old=$OLD new=$NEW live=$LIVE waited=${W}s"
      echo "-- crash buffer"; echo "$CR"; echo "-- AndroidRuntime"; echo "$AR"; echo "-- ActivityManager"; echo "$AM"
      echo "-- EQ-SVC/EQ-DEBUG"; "$ADB" logcat -d -s EQ-SVC EQ-DEBUG 2>&1 | tr -d '\r'
      echo "-- full logcat tail"; "$ADB" logcat -d 2>&1 | tr -d '\r' | tail -150
      echo "-- session checks: expect_sessions=${EXPECT_SESS:-n/a} $SESS_NOTE"
      echo "-- logcat tail captured before dump-state"; echo "$LOGSNAP"
    } > "$OUT/fail-cycle-$c-$v.log"
    DEBUG_LOG="$DEBUG_DIR/restart-soak-fail-$c-$v-$(date -u +%Y%m%dT%H%M%SZ).log"
    cp "$OUT/fail-cycle-$c-$v.log" "$DEBUG_LOG"
    say "!!!!!!!! FAILING CYCLE $c variant=$v status=$status (log committed under .planning/debug: ${DEBUG_LOG#$ROOT/}) !!!!!!!!"
  fi
  say "cycle $c/$N variant=$v $status old=$OLD new=$NEW waited=${W}s $SESS_NOTE"
  SUMMARY+=("$c:$v:$status")
done
say "SOAK cycles=$N ok=$pass failed=$failn"
printf '%s\n' "${SUMMARY[@]}" > "$OUT/summary.txt"
[ "$failn" = 0 ]
