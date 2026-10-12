#!/usr/bin/env bash
# Real adb reboot proof of start-on-boot on and off. Usage: phase3-reboot.sh [--stop]
# Never force-stops before a reboot (a force-stopped package gets no BOOT_COMPLETED).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"
FAIL=0

uptime_s() { "$ADB" shell cat /proc/uptime | tr -d '\r' | awk '{print int($1)}'; }

reboot_and_wait() {
  local up_before="$1" up_after
  say "adb reboot (uptime before=${up_before}s)"
  "$ADB" reboot
  sleep 5
  "$ADB" wait-for-device
  for i in $(seq 1 200); do
    [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break
    sleep 2
  done
  up_after=$(uptime_s)
  say "boot_completed, uptime after=${up_after}s"
  [ "$up_after" -lt "$up_before" ] || { say "uptime did not drop: not a real reboot"; exit 1; }
}

bash spike-tools/emulator-up.sh
build_install
launch_app

say "== leg A: start-on-boot ON"
set_state 4 -2 graphic off on off
sleep 2
BEFORE=$(dump_state b1)
say "BEFORE: $BEFORE"
reboot_and_wait "$(uptime_s)"
SECS=$(wait_fg 60 || echo TIMEOUT)
say "FINDING seconds from boot_completed to foreground service (no app launch): $SECS"
"$ADB" shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground" | sed 's/^ *//' | cut -c1-140
AFTER=$(dump_state a1)
say "AFTER:  $AFTER"
if [ "$SECS" != "TIMEOUT" ] && [ "$(fields "$BEFORE")" = "$(fields "$AFTER")" ]; then
  say "PASS leg A (foreground in ${SECS}s, state equal)"
else say "FAIL leg A"; FAIL=1; fi

say "== leg B: start-on-boot OFF"
cmd --es cmd set-start-on-boot --es value off
sleep 2
BEFORE2=$(dump_state b2)
say "BEFORE: $BEFORE2"
reboot_and_wait "$(uptime_s)"
sleep 40
SVC=$("$ADB" shell dumpsys activity services "$PKG" | grep -c "ServiceRecord" || true)
PID=$(pid_of)
# The process itself may exist: it is started to deliver BOOT_COMPLETED to BootReceiver, which
# reads the flag and returns. What must be absent is the service.
REQ=$("$ADB" logcat -d -s EQ-SVC:I | grep -c "boot start requested" || true)
say "after 40s: ServiceRecord lines=$SVC, process pid='${PID}' (receiver-only process is expected), 'boot start requested' log lines=$REQ"
AFTER2=$(dump_state a2)
say "AFTER (dump starts the process): $AFTER2"
if [ "$SVC" = "0" ] && [ "$REQ" = "0" ] && [ "$(fields "$BEFORE2")" = "$(fields "$AFTER2")" ]; then
  say "PASS leg B (no service, state restored when opened)"
else say "FAIL leg B"; FAIL=1; fi

say "== cleanup"
cmd --es cmd set-global --es value off
sleep 1
[ "${1:-}" = "--stop" ] && bash spike-tools/emulator-down.sh
exit $FAIL
