#!/usr/bin/env bash
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT"
if "$ADB" devices | grep -E '^emulator-' >/dev/null; then
  "$ADB" emu kill || true
fi
for i in $(seq 1 30); do
  pgrep -f 'qemu-system-[a]' >/dev/null || break
  sleep 2
done
./gradlew --stop || true
exit 0
