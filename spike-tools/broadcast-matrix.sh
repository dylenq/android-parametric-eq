#!/usr/bin/env bash
# SPIKE-03 delivery matrix. Assumes emulator up and both APKs installed.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT"
EV="$ROOT/.planning/phases/01-audio-path-spike/evidence"
OUT="$EV/broadcast-matrix.tsv"
ENVF="$EV/broadcast-env.txt"
APP=com.dylenq.eq
STUB=com.dylenq.playerstub
OPEN=android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION
CLOSE=android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION
SID=5001
RAW="$EV/broadcast-matrix.raw.log"
: > "$RAW"

sh() { "$ADB" shell "$@" | tr -d '\r'; }

printf 'cell\tprocess_state\tservice_cfg\tsender\taction\tmanifest_delivered\truntime_delivered\tnotes\n' > "$OUT"
{
  echo "api_sdk_full=$(sh getprop ro.build.version.sdk_full)"
  echo "release=$(sh getprop ro.build.version.release)"
  echo "abi=$(sh getprop ro.product.cpu.abi)"
  echo "app_dumpsys_targetSdk: $(sh dumpsys package $APP | grep -m1 targetSdk | sed 's/^ *//')"
  echo "stub_dumpsys_targetSdk: $(sh dumpsys package $STUB | grep -m1 targetSdk | sed 's/^ *//')"
} > "$ENVF"

launch_app() { sh am start -W -n $APP/.spike.MainActivity >/dev/null; sleep 1; }
home() { sh input keyevent KEYCODE_HOME; sleep 1; }
start_service() { # exported true|false
  "$ADB" logcat -c
  sh am startservice -n $APP/.spike.SpikeListenerService --ez exported "$1" >/dev/null
  sleep 1.5
  local l; l=$("$ADB" logcat -d -s SPIKE-BCAST | grep -E 'runtime (registered|register_failed)' | tail -1)
  echo "service_exported=$1 -> ${l:-NO_LOG_LINE}" >> "$ENVF"
}
stop_service() { sh am stopservice -n $APP/.spike.SpikeListenerService >/dev/null 2>&1 || true; }

send() { # sender action session
  local sender=$1 action=$2 s=$3 open=1
  [ "$action" = "$CLOSE" ] && open=0
  case $sender in
    shell_implicit) sh am broadcast -a "$action" --ei android.media.extra.AUDIO_SESSION "$s" --es android.media.extra.PACKAGE_NAME com.example.player >/dev/null ;;
    shell_explicit) sh am broadcast -a "$action" -n $APP/.spike.SpikeReceiver --ei android.media.extra.AUDIO_SESSION "$s" --es android.media.extra.PACKAGE_NAME com.example.player >/dev/null ;;
    stub_implicit) sh am broadcast -n $STUB/.TriggerReceiver -a com.dylenq.playerstub.SEND --ei session "$s" --ei open $open >/dev/null ;;
  esac
}

cellno=0
record() { # state cfg sender action  (state already prepared); uses fresh session id
  local state=$1 cfg=$2 sender=$3 action=$4 notes=${5:-}
  local s=$SID; SID=$((SID+1)); cellno=$((cellno+1))
  "$ADB" logcat -c
  send "$sender" "$action" "$s"
  sleep 2
  local log; log=$("$ADB" logcat -d -s SPIKE-BCAST | grep "session=$s " || true)
  echo "== cell $cellno $state/$cfg/$sender/$action session=$s" >> "$RAW"; echo "$log" >> "$RAW"
  local m=no r=no
  echo "$log" | grep -q 'manifest ' && m=yes
  echo "$log" | grep -q 'runtime ' && r=yes
  local short=${action##*.}
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' "$cellno" "$state" "$cfg" "$sender" "$short" "$m" "$r" "session=$s $notes" >> "$OUT"
  echo "cell $cellno $state/$cfg/$sender -> manifest=$m runtime=$r"
}

# warm-up: take the stub out of the stopped state (explicit broadcast)
sh am broadcast -n $STUB/.TriggerReceiver -a com.dylenq.playerstub.SEND --ei session 4999 --ei open 1 >/dev/null
sleep 1
# warm-up for the app: launch once
sh am force-stop $APP; launch_app

SENDERS="shell_implicit shell_explicit stub_implicit"
for state in fg bg; do
  for cfg in none runtime_exported runtime_not_exported; do
    sh am force-stop $APP; launch_app
    case $cfg in
      runtime_exported) start_service true ;;
      runtime_not_exported) start_service false ;;
    esac
    [ $state = bg ] && home
    for snd in $SENDERS; do record $state $cfg $snd $OPEN; done
    if [ $state = bg ] && [ $cfg = runtime_exported ]; then
      record $state $cfg stub_implicit $CLOSE "pair_check_close"
    fi
    stop_service
    [ $state = bg ] && true
  done
done
# extra pair-check open row recorded explicitly (separate session)
sh am force-stop $APP; launch_app; start_service true; home
record bg runtime_exported stub_implicit $OPEN "pair_check_open"
stop_service

# killed: process dead but app not stopped
for snd in $SENDERS; do
  sh am force-stop $APP; launch_app; home
  sh am kill $APP; sleep 1
  alive=$(sh pidof $APP || true)
  record killed none $snd $OPEN "pid_before_send=${alive:-dead}"
done
# force-stopped
for snd in $SENDERS; do
  sh am force-stop $APP; sleep 1
  record force_stopped none $snd $OPEN
done
launch_app
echo "done: $(($(wc -l < "$OUT")-1)) rows"
