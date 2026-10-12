#!/usr/bin/env bash
# Shared helpers for phase3-lifecycle.sh and phase3-reboot.sh. Source only; edits nothing outside the repo and the emulator.
PKG=com.dylenq.eq
RCV="$PKG/com.dylenq.eq.debug.DebugCommandReceiver"
now_s() { date +%s; }
pid_of() { "$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true; }
is_fg() { local o; o=$("$ADB" shell dumpsys activity services "$PKG" 2>/dev/null) || true; case "$o" in *isForeground=true*) return 0;; *) return 1;; esac; }
say() { echo "[$(date +%T)] $*" >&2; }

cmd() {  # cmd <extras...>
  say "adb shell am broadcast -n $RCV $*"
  "$ADB" shell am broadcast -n "$RCV" "$@" >/dev/null
}

# dump_state <nonce> : prints the raw EQ-STATE payload (after the tag) once it shows up
dump_state() {
  local nonce="$1" line="" i
  "$ADB" logcat -c
  cmd --es cmd dump-state --es nonce "$nonce"
  for i in $(seq 1 60); do
    line=$("$ADB" logcat -d -s EQ-STATE:I | grep "nonce=$nonce" | tail -1 || true)
    [ -n "$line" ] && break
    sleep 0.5
  done
  [ -n "$line" ] || { say "no EQ-STATE line for nonce=$nonce" >&2; return 1; }
  echo "${line#*EQ-STATE: }"
}

# fields <line> : the comparable part (drops nonce and sessions)
fields() { echo "$1" | sed -E 's/ nonce=.*$//; s/ sessions=[0-9]+//'; }

build_install() {
  (cd "$ROOT" && ./gradlew --offline :app:installDebug >/dev/null)
  "$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
}

wait_fg() {  # wait_fg <timeout_s> : echoes seconds elapsed, returns 1 on timeout
  local t0 t
  t0=$(now_s)
  while :; do
    if is_fg; then echo $(( $(now_s) - t0 )); return 0; fi
    t=$(( $(now_s) - t0 ))
    [ "$t" -gt "$1" ] && return 1
    sleep 0.5
  done
}

launch_app() {
  say "adb shell am start -n $PKG/.MainActivity"
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  wait_fg 20 >/dev/null || true
  "$ADB" shell input keyevent HOME
  sleep 1
}

set_state() {  # set_state <gain> <preamp> <mode> <global> <boot> <bypass>
  cmd --es cmd set-graphic-gain --ei index 3 --ef db "$1"
  cmd --es cmd set-preamp --ef db "$2"
  cmd --es cmd set-mode --es value "$3"
  cmd --es cmd set-global --es value "$4"
  cmd --es cmd set-start-on-boot --es value "$5"
  cmd --es cmd set-bypass --es value "$6"
}

# capture_restart_evidence <label> : dumps restart-relevant system state for the package (read-only)
capture_restart_evidence() {
  local label="$1"
  say "--- dumpsys evidence ($label): activity services"
  "$ADB" shell dumpsys activity services "$PKG" 2>&1 | tr -d '\r' >&2 || true
  say "--- dumpsys evidence ($label): activity processes (restart lines)"
  "$ADB" shell dumpsys activity processes "$PKG" 2>&1 | tr -d '\r' | grep -iE 'restart|crash|backoff|bad|killed' >&2 || true
  say "--- logcat crash buffer and AndroidRuntime evidence ($label)"
  "$ADB" logcat -d -b crash 2>&1 | tr -d '\r' | grep -v '^---' >&2 || true
  "$ADB" logcat -d -s AndroidRuntime:E 2>&1 | tr -d '\r' | grep -v '^---' | head -60 >&2 || true
  say "--- logcat ActivityManager evidence ($label)"
  "$ADB" logcat -d -s ActivityManager:I 2>&1 | tr -d '\r' | grep "$PKG" | grep -iE 'restart|Scheduling|crash|killed|died|Process .* has died' >&2 || true
}

# classify_restart <old_pid> : prints exactly one class. Reads live state plus the captured text in $RESTART_EVIDENCE_FILE.
classify_restart() {
  local old="$1" new ev
  new=$(pid_of)
  if [ -n "$new" ] && [ "$new" != "$old" ] && is_fg; then echo RESTARTED_STICKY; return 0; fi
  ev=$( { "$ADB" shell dumpsys activity services "$PKG" 2>&1; "$ADB" logcat -d -s ActivityManager:I 2>&1 | grep "$PKG"; } | tr -d '\r' || true)
  if echo "$ev" | grep -qiE 'restartDelay|nextRestartTime|Scheduling restart|scheduled restart|crashCount=[1-9]|restarting|mRestartCount=[1-9]|backoff'; then
    echo NOT_RESTARTED_BACKOFF
  else
    echo NOT_RESTARTED_OTHER
  fi
  return 0
}

# restart_delay_detail : the restart delay lines currently visible in dumpsys, or "none"
restart_delay_detail() {
  local d
  d=$("$ADB" shell dumpsys activity services "$PKG" 2>&1 | tr -d '\r' | grep -iE 'restartDelay|nextRestartTime|restartCount|crashCount|scheduled' | tr -s ' ' | tr '\n' ';' || true)
  echo "${d:-none}"
}
