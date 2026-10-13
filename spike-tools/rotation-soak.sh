#!/usr/bin/env bash
# Rotation CPU soak. Usage: rotation-soak.sh [cycles=60]
# Looks for the unexplained "100% CPU after a rotation" seen once in LayoutTest.
# Installs the debug app, then rotates the emulator (settings put system user_rotation 0/1 with
# accelerometer_rotation 0; both are restored by the trap). The app state changes every
# CYCLES_PER_CONFIG rotations through CONFIGS (10/15/31 bands x graphic/parametric), built with the
# debug broadcasts BEFORE the activity is launched (the UI holder reads the core when it is created).
# After each rotation: settle 1.5 s, then sample the app's CPU three times, 1 s apart, from
# /proc/<pid>/stat (utime+stime ticks over a measured interval; no dependency on toybox top flags).
# A cycle is FLAGGED when all three samples are above 25 percent (SPIN_PCT) (sustained spin) or the process died.
# On a flag: per-thread CPU (top -H), a SIGQUIT thread dump (run-as + kill -3, read back from logcat),
# and the cycle's logcat go to build/rotation-soak/. Exit 0 = no flagged cycle, 1 = at least one.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
N="${1:-60}"
CYCLES_PER_CONFIG="${SOAK_CYCLES_PER_CONFIG:-10}"
# One busy loop reads only ~35% on this single-core emulator (see the positive control below), so the
# default is 25, not 50; override with SPIN_PCT. Idle app samples are 0-1%.
THRESHOLD="${SPIN_PCT:-25}"
OUT="$ROOT/build/rotation-soak"
mkdir -p "$OUT"

# bands:mode
CONFIGS=(10:graphic 15:graphic 31:graphic 10:parametric 15:parametric 31:parametric)
# SOAK_CONFIGS="10:parametric" restricts the run (e.g. Parametric only, with several bands).
[ -z "${SOAK_CONFIGS:-}" ] || read -ra CONFIGS <<< "$SOAK_CONFIGS"

bash "$ROOT/spike-tools/emulator-up.sh"
build_install

ORIG_ACCEL=$("$ADB" shell settings get system accelerometer_rotation | tr -d '\r')
ORIG_ROT=$("$ADB" shell settings get system user_rotation | tr -d '\r')
restore() {
  "$ADB" shell settings put system user_rotation "${ORIG_ROT:-0}" >/dev/null 2>&1 || true
  "$ADB" shell settings put system accelerometer_rotation "${ORIG_ACCEL:-1}" >/dev/null 2>&1 || true
}
trap restore EXIT
"$ADB" shell settings put system accelerometer_rotation 0
"$ADB" shell settings put system user_rotation 0

g() { "$ADB" shell am broadcast -n "$RCV" --es cmd set-graphic-gain --ei index "$1" --ef db "$2" >/dev/null; }

# prepare <bands> <mode> : known state with a few non-zero bands, then a fresh process
prepare() {
  "$ADB" shell am force-stop "$PKG"
  cmd --es cmd set-band-count --ei count 31
  for i in $(seq 0 30); do g "$i" 0; done
  cmd --es cmd set-band-count --ei count "$1"
  g 1 6; g 2 -6; g 4 3; g 6 -2
  cmd --es cmd set-preamp --ef db -3
  if [ "$2" = parametric ]; then
    # Several parametric bands (the core starts with at most a few): add up to 8 and shape them.
    for i in 0 1 2 3 4 5 6 7; do
      cmd --es cmd add-band
      cmd --es cmd set-band --ei index "$i" --ef hz "$((100 * (i + 1) * (i + 1)))" --ef db "$(( (i % 2) * 6 - 3 ))" --ef q 1.2
    done
  fi
  cmd --es cmd set-bypass --es value off
  cmd --es cmd set-mode --es value "$2"
  sleep 1
  "$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
  sleep 3
}

# ticks <pid> : utime+stime of the process in clock ticks, empty if it is gone
ticks() { "$ADB" shell "cat /proc/$1/stat 2>/dev/null" | tr -d '\r' | sed -E 's/^[0-9]+ \(.*\) //' | awk '{print $12 + $13}'; }

# sample_cpu <pid> : CPU percent over ~1 s (one core = 100)
sample_cpu() {
  local a b t0 t1
  a=$(ticks "$1"); t0=$(python3 -c 'import time;print(time.time())')
  sleep 1
  b=$(ticks "$1"); t1=$(python3 -c 'import time;print(time.time())')
  [ -n "$a" ] && [ -n "$b" ] || { echo -1; return; }
  python3 -c "print(int(round(($b - $a) / 100.0 / ($t1 - $t0) * 100)))"
}

# Positive control: a known busy loop must read above the threshold, or the sampler cannot see a spin.
BUSY=$("$ADB" shell '(sh -c "while :; do :; done" >/dev/null 2>&1 & echo $!)' | tr -d '\r')
sleep 1
ctl=$(sample_cpu "$BUSY")
"$ADB" shell kill "$BUSY" >/dev/null 2>&1 || true
say "positive control: busy loop pid=$BUSY reads ${ctl}% (threshold $THRESHOLD%)"
if [ "$ctl" -le "$THRESHOLD" ]; then say "sampler cannot detect a busy loop at this threshold; lower SPIN_PCT"; exit 2; fi

flagged=0; ok=0
declare -a SUMMARY
PID_START=""
for c in $(seq 1 "$N"); do
  idx=$(( ((c - 1) / CYCLES_PER_CONFIG) % ${#CONFIGS[@]} ))
  cfg="${CONFIGS[$idx]}"; bands="${cfg%%:*}"; mode="${cfg##*:}"
  if [ $(( (c - 1) % CYCLES_PER_CONFIG )) = 0 ]; then
    say "config $cfg (cycle $c)"
    prepare "$bands" "$mode"
    PID_START=$(pid_of)
  fi
  rot=$(( c % 2 ))   # 1 = landscape, 0 = portrait
  "$ADB" logcat -c
  "$ADB" shell settings put system user_rotation "$rot"
  sleep 1.5
  pid=$(pid_of)
  s1=-1; s2=-1; s3=-1; status=OK
  if [ -z "$pid" ]; then
    status=PROCESS_GONE
  else
    s1=$(sample_cpu "$pid"); s2=$(sample_cpu "$pid"); s3=$(sample_cpu "$pid")
    [ "$pid" = "$PID_START" ] || say "cycle $c: pid changed $PID_START -> $pid"
    if [ "$s1" -gt "$THRESHOLD" ] && [ "$s2" -gt "$THRESHOLD" ] && [ "$s3" -gt "$THRESHOLD" ]; then status=SPIN; fi
  fi
  if [ "$status" != OK ]; then
    flagged=$((flagged + 1))
    f="$OUT/flag-cycle-$c-$cfg-rot$rot"
    {
      echo "== cycle $c config=$cfg rotation=$rot status=$status pid=$pid cpu=$s1,$s2,$s3"
      echo "-- top -H (threads)"; "$ADB" shell top -H -b -n 1 -d 1 -p "$pid" 2>&1 | tr -d '\r' | head -40
    } > "$f.txt"
    if [ -n "$pid" ]; then
      "$ADB" logcat -c
      "$ADB" shell run-as "$PKG" kill -3 "$pid" 2>&1 || true
      sleep 3
      { echo "-- SIGQUIT thread dump (logcat)"; "$ADB" logcat -d 2>&1 | tr -d '\r' | tail -600; } >> "$f.txt"
    fi
    "$ADB" logcat -d 2>&1 | tr -d '\r' > "$f.logcat"
    say "cycle $c FLAGGED ($status, cpu $s1,$s2,$s3) -> $f.txt"
  else
    ok=$((ok + 1))
  fi
  say "cycle $c/$N $cfg rot=$rot $status cpu=$s1,$s2,$s3 pid=$pid"
  SUMMARY+=("$c:$cfg:rot$rot:$status:$s1,$s2,$s3")
done
say "ROTATION SOAK cycles=$N clean=$ok flagged=$flagged (flag = >$THRESHOLD% in three samples 1 s apart; positive control read ${ctl}%)"
printf '%s\n' "${SUMMARY[@]}" > "$OUT/summary.txt"
[ "$flagged" = 0 ]
