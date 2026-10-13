#!/usr/bin/env bash
# Rotation CPU soak. Usage: rotation-soak.sh [cycles=60]
# Runtime: each cycle adds a 10 s CPU window (plus ~3 s of rotation, settle and logging), so the default
# 60 cycles take roughly 12 minutes; plan 12 (the phase gate) runs it in the background.
# Looks for the unexplained "100% CPU after a rotation" seen once in LayoutTest.
# Installs the debug app, then rotates the emulator (settings put system user_rotation 0/1 with
# accelerometer_rotation 0; both are restored by the trap). The app state changes every
# CYCLES_PER_CONFIG rotations through CONFIGS (10/15/31 bands x graphic/parametric), built with the
# debug broadcasts BEFORE the activity is launched (the UI holder reads the core when it is created).
# After each rotation: settle 1.5 s, then take ONE CPU reading over a WINDOW_S (default 10) second window from
# /proc/<pid>/stat: utime+stime ticks over a measured interval, percent = ticks / CLK_TCK / elapsed * 100
# (CLK_TCK read once from the device with getconf, fallback 100; no dependency on toybox top flags).
# A cycle is FLAGGED when that window average is above 10 percent (SPIN_PCT) or the process died.
# Idle app readings were 0-1% in 349 of 360 earlier samples, so 10% is well above noise.
# On a flag: per-thread CPU (top -H), a SIGQUIT thread dump (run-as + kill -3, read back from logcat),
# and the cycle's logcat go to build/rotation-soak/. Exit 0 = no flagged cycle, 1 = at least one.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
N="${1:-60}"
CYCLES_PER_CONFIG="${SOAK_CYCLES_PER_CONFIG:-10}"
# One busy loop reads only ~29% on this single-core emulator (measured positive control, Phase 4 plan 07),
# so the threshold is low; override with SPIN_PCT. The control below uses the same WINDOW_S window.
THRESHOLD="${SPIN_PCT:-10}"
WINDOW_S="${WINDOW_S:-10}"
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

CLK_TCK=$("$ADB" shell getconf CLK_TCK 2>/dev/null | tr -d '\r' | grep -E '^[0-9]+$' || true)
CLK_TCK="${CLK_TCK:-100}"
say "CLK_TCK=$CLK_TCK WINDOW_S=${WINDOW_S}s THRESHOLD=${THRESHOLD}%"

# sample_cpu <pid> [window_s] : CPU percent over the window (one core = 100); -1 if the process is gone
sample_cpu() {
  local w="${2:-$WINDOW_S}" a b t0 t1
  a=$(ticks "$1"); t0=$(python3 -c 'import time;print(time.time())')
  sleep "$w"
  b=$(ticks "$1"); t1=$(python3 -c 'import time;print(time.time())')
  [ -n "$a" ] && [ -n "$b" ] || { echo -1; return; }
  python3 -c "print(int(round(($b - $a) / float($CLK_TCK) / ($t1 - $t0) * 100)))"
}

# Positive control: a known busy loop must read above the threshold, or the sampler cannot see a spin.
BUSY=$("$ADB" shell '(sh -c "while :; do :; done" >/dev/null 2>&1 & echo $!)' | tr -d '\r')
sleep 1
ctl=$(sample_cpu "$BUSY" "$WINDOW_S")
"$ADB" shell kill "$BUSY" >/dev/null 2>&1 || true
say "positive control: busy loop pid=$BUSY reads ${ctl}% over ${WINDOW_S}s (threshold $THRESHOLD%)"
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
  w=-1; status=OK
  if [ -z "$pid" ]; then
    status=PROCESS_GONE
  else
    w=$(sample_cpu "$pid" "$WINDOW_S")
    [ "$pid" = "$PID_START" ] || say "cycle $c: pid changed $PID_START -> $pid"
    if [ "$w" -lt 0 ]; then status=PROCESS_GONE
    elif [ "$w" -gt "$THRESHOLD" ]; then status=SPIN; fi
  fi
  if [ "$status" != OK ]; then
    flagged=$((flagged + 1))
    f="$OUT/flag-cycle-$c-$cfg-rot$rot"
    {
      echo "== cycle $c config=$cfg rotation=$rot status=$status pid=$pid cpu=${w}%"
      echo "-- top -H (threads)"; "$ADB" shell top -H -b -n 1 -d 1 -p "$pid" 2>&1 | tr -d '\r' | head -40
    } > "$f.txt"
    if [ -n "$pid" ]; then
      "$ADB" logcat -c
      "$ADB" shell run-as "$PKG" kill -3 "$pid" 2>&1 || true
      sleep 3
      { echo "-- SIGQUIT thread dump (logcat)"; "$ADB" logcat -d 2>&1 | tr -d '\r' | tail -600; } >> "$f.txt"
    fi
    "$ADB" logcat -d 2>&1 | tr -d '\r' > "$f.logcat"
    say "cycle $c FLAGGED ($status, cpu ${w}) -> $f.txt"
  else
    ok=$((ok + 1))
  fi
  say "cycle $c/$N $cfg rot=$rot $status cpu=${w}% pid=$pid"
  SUMMARY+=("$c:$cfg:rot$rot:$status:${w}")
done
say "ROTATION SOAK cycles=$N clean=$ok flagged=$flagged (flag = window average >$THRESHOLD% over ${WINDOW_S}s; positive control read ${ctl}%)"
printf '%s\n' "${SUMMARY[@]}" > "$OUT/summary.txt"
[ "$flagged" = 0 ]
