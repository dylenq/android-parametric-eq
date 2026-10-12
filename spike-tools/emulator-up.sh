#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT"
mkdir -p build
if "$ADB" devices | grep -E '^emulator-[0-9]+[[:space:]]+device' >/dev/null; then
  echo "emulator already up"; exit 0
fi
ARGS=(-avd Medium_Phone_API_37.0 -no-window -no-boot-anim -no-snapshot-save)
[ "${EMU_AUDIO:-0}" = "1" ] || ARGS+=(-no-audio)
nohup emulator "${ARGS[@]}" > build/emulator.log 2>&1 &
"$ADB" wait-for-device
for i in $(seq 1 150); do
  if [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    echo "emulator booted"; exit 0
  fi
  sleep 2
done
echo "emulator boot timeout" >&2
exit 1
