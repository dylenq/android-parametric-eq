#!/usr/bin/env bash
# Phase 7 emulator check: the real engine probe, then a forced engine band cap (set-max-bands 64) through the
# debug receiver, then clearing it. Asserts via the EQ-STATE maxBands= field and the EQ-SVC log that effects
# are recreated on the smaller grid without create errors. Installs the debug build fresh (clears the cached probe).
# Physical-phone behaviour (devices that accept fewer bands than 512) is out of scope. Usage: phase7-probe.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"

fail() { echo "FAIL: $*" >&2; exit 1; }
maxbands_of() { echo "$1" | sed -E 's/.* maxBands=([^ ]+).*/\1/'; }

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
(./gradlew --offline :app:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
# Global mode attaches the real DynamicsProcessing to the output mix (session 0); that apply runs the real probe.
# (A made-up player session id cannot attach an effect on the emulator, so the playerstub is not used here.)
cmd --es cmd set-global --es value on
sleep 4

s0=$(dump_state p7-base) || fail "no dump-state"
mb0=$(maxbands_of "$s0")
say "unforced: maxBands=$mb0"
[ "$mb0" = "512" ] || fail "expected maxBands=512 on the unforced emulator, got $mb0"

cmd --es cmd set-max-bands --ei value 64
sleep 2
# Re-attach so a brand-new DynamicsProcessing is created on the 64-band grid (not only reconfigured).
"$ADB" logcat -c
cmd --es cmd set-global --es value off
cmd --es cmd set-global --es value on
sleep 3
svc_log=$("$ADB" logcat -d -s EQ-SVC:I | tr -d '\r')
echo "$svc_log" | grep -q "global mode on" || fail "global re-attach on the 64-band grid did not log 'global mode on'"
if echo "$svc_log" | grep -iE "attach failed|create retry|rejected|failed"; then
  fail "EQ-SVC reported errors on the 64-band grid"
fi
s1=$(dump_state p7-forced) || fail "no dump-state after force"
mb1=$(maxbands_of "$s1")
say "forced: maxBands=$mb1"
[ "$mb1" = "64" ] || fail "expected maxBands=64 after set-max-bands 64, got $mb1"
case "$s1" in *"globalMode=true"*) ;; *) fail "global mode was rejected on the 64-band grid: $s1";; esac
case "$s1" in *"bypass=true"*) fail "EQ bypassed after forcing the cap";; esac

cmd --es cmd set-max-bands
sleep 2
s2=$(dump_state p7-cleared) || fail "no dump-state after clear"
mb2=$(maxbands_of "$s2")
say "cleared: maxBands=$mb2"
[ "$mb2" = "$mb0" ] || fail "expected the measured cap ($mb0) after clear, got $mb2"

cmd --es cmd set-global --es value off
echo "PASS: maxBands $mb0 -> $mb1 (forced) -> $mb2 (cleared), global re-attach OK on the 64-band grid"
