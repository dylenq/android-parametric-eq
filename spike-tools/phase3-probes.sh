#!/usr/bin/env bash
# Phase 3 Wave 0 platform probes. Records observations only; no pass/fail predicate.
# Output: .planning/phases/03-service-and-sessions/evidence/probes.raw.log
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT"

PKG=com.dylenq.eq
OUT="$ROOT/.planning/phases/03-service-and-sessions/evidence"
RAW="$OUT/probes.raw.log"
mkdir -p "$OUT"
: > "$RAW"

# Always put the battery whitelist back, whatever happens.
restore_whitelist() { "$ADB" shell cmd deviceidle whitelist -com.dylenq.eq >/dev/null 2>&1 || true; }
trap restore_whitelist EXIT

note() { echo "$*" | tee -a "$RAW"; }
now_ms() { perl -MTime::HiRes=time -e 'printf "%d\n", time()*1000'; }
pid_of() { "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true; }
svc_dump() { "$ADB" shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground|startRequested|stopIfKilled" | sed 's/^ *//' | cut -c1-160 || true; }
is_fg() { "$ADB" shell dumpsys activity services "$PKG" | grep -q "isForeground=true"; }

# wait_fg <timeout_s> [old_pid] : prints elapsed ms until a foreground service shows up (in a process
# whose pid differs from old_pid when given), or TIMEOUT
wait_fg() {
  local timeout_s="$1" old="${2:-}" t0 t p
  t0=$(now_ms)
  while :; do
    t=$(now_ms)
    p=$(pid_of)
    if [ -n "$p" ] && [ "$p" != "$old" ] && is_fg; then echo "$((t - t0))ms"; return 0; fi
    if [ $((t - t0)) -gt $((timeout_s * 1000)) ]; then echo "TIMEOUT(${timeout_s}s)"; return 1; fi
    sleep 0.25
  done
}

launch_app() {
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  wait_fg 20 >/dev/null || true
  "$ADB" shell input keyevent HOME
  sleep 1
}

note "== phase3 probes $(date -u +%FT%TZ) device=$("$ADB" shell getprop ro.build.fingerprint | tr -d '\r')"

bash "$ROOT/spike-tools/emulator-up.sh"

note "== build and install"
./gradlew --offline :app:installDebug :app:installDebugAndroidTest :playerstub:installDebug >> "$RAW" 2>&1
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

note "== A1/A4/A8: Phase3ProbeTest via am instrument"
"$ADB" logcat -c
"$ADB" shell am instrument -w -e class com.dylenq.eq.service.Phase3ProbeTest \
  com.dylenq.eq.test/androidx.test.runner.AndroidJUnitRunner 2>&1 | tee -a "$RAW"
"$ADB" logcat -d -s P3-PROBE:I | grep P3-PROBE | tee -a "$RAW"

note "== launch once (fresh install is in the stopped state and gets no broadcasts)"
launch_app
note "pid=$(pid_of)"; svc_dump | tee -a "$RAW"

kill_and_watch() { # <label> <kill-command-run-on-device-with-PID-placeholder>
  local label="$1" how="$2" pid t0 elapsed newpid
  pid=$(pid_of)
  note "-- $label: old pid=$pid"
  t0=$(now_ms)
  "$ADB" shell "${how//PID/$pid}" >> "$RAW" 2>&1 || note "(kill command returned non-zero)"
  elapsed=$(wait_fg 30 "$pid" || true)
  newpid=$(pid_of)
  note "-- $label: new pid=$newpid service_foreground_again_after=$elapsed (kill-to-observe $(( $(now_ms) - t0 ))ms total)"
  svc_dump | tee -a "$RAW"
  sleep 2
}

note "== A2/A9: sticky restart"
kill_and_watch "A9 run-as kill -9" "run-as $PKG kill -9 PID"
# am crash only induces the exception; a headless emulator then waits on the crash dialog, so dismiss it
# with BACK after a short pause, then watch for the sticky restart. Done twice in a row to see the backoff.
crash_and_watch() { # <label>
  local label="$1" pid elapsed
  wait_fg 20 >/dev/null || true
  pid=$(pid_of)
  "$ADB" logcat -c
  note "-- $label: old pid=$pid"
  "$ADB" shell am crash "$PKG" >> "$RAW" 2>&1 || true
  sleep 3
  note "-- $label: pid 3s after am crash (crash dialog showing, BACK not yet sent)=$(pid_of)"
  "$ADB" shell input keyevent KEYCODE_BACK
  elapsed=$(wait_fg 20 "$pid" || true)
  note "-- $label: new pid=$(pid_of) service_foreground_again_after_BACK=$elapsed"
  "$ADB" logcat -d | grep -E "Scheduling restart of crashed service|Showing crash dialog|Killing [0-9]+:$PKG" | cut -c1-220 | tee -a "$RAW" || true
  svc_dump | tee -a "$RAW"
}
note "-- fresh state for crash probe"
"$ADB" shell am force-stop "$PKG"; launch_app
crash_and_watch "A2 am crash #1"
crash_and_watch "A2 am crash #2 (immediately after #1)"

note "-- A2 am force-stop (expected: no restart)"
"$ADB" shell am force-stop "$PKG"
sleep 10
note "pid after 10s=$(pid_of)"; svc_dump | tee -a "$RAW"; note "(empty service dump above means no service record)"

launch_app

wakeup_run() { # <label>
  local label="$1" pid
  wait_fg 20 >/dev/null || true
  pid=$(pid_of)
  "$ADB" logcat -c
  note "-- $label: old pid=$pid, killing and sending OPEN session 7001 from the stub in one shell call"
  "$ADB" shell "run-as $PKG kill -9 $pid; am broadcast -n com.dylenq.playerstub/.TriggerReceiver -a com.dylenq.playerstub.SEND --ei session 7001 --ei open 1" >> "$RAW" 2>&1 || true
  sleep 5
  "$ADB" logcat -d | grep -E "EQ-SVC|Start proc [0-9]+:$PKG|Process $PKG \(pid|ForegroundServiceStartNotAllowed" | cut -c1-220 | tee -a "$RAW" || true
  note "-- $label: pid after=$(pid_of) foreground=$(is_fg && echo yes || echo no)"
  svc_dump | tee -a "$RAW"
  "$ADB" shell am broadcast -n com.dylenq.playerstub/.TriggerReceiver -a com.dylenq.playerstub.SEND --ei session 7001 --ei open 0 >/dev/null 2>&1 || true
  sleep 2
}

note "== OQ1 without battery exemption (3 runs)"
restore_whitelist
for n in 1 2 3; do wakeup_run "OQ1-noexempt-run$n"; done

note "== OQ1 with battery exemption (3 runs)"
"$ADB" shell cmd deviceidle whitelist +com.dylenq.eq | tee -a "$RAW"
for n in 1 2 3; do wakeup_run "OQ1-exempt-run$n"; done
"$ADB" shell cmd deviceidle whitelist -com.dylenq.eq | tee -a "$RAW"

note "== done $(date -u +%FT%TZ)"
