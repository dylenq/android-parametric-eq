#!/usr/bin/env bash
# Phase 11 emulator check: the exact command the Shizuku user service runs (GrantCommand.SHELL_LINE, checked against
# this file by GrantCommandTest) grants DUMP when run with `adb shell`. A real Shizuku server is NOT exercised here;
# nobody approved installing it on this machine. Part 2: a grant made after tracking is switched on takes effect with
# no restart (state goes NeedsPermission, then Working within a few seconds).
# Usage: phase11-grant-command.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"

fail() { echo "FAIL: $*" >&2; exit 1; }
tracking_of() { echo "$1" | sed -E 's/.* tracking=([^ ]+).*/\1/'; }

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
(./gradlew --offline :app:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

granted() { "$ADB" shell dumpsys package "$PKG" | grep -E "android.permission.DUMP: granted=true" >/dev/null; }

granted && fail "DUMP is granted on a fresh install"

# Tracking on without the grant: NeedsPermission, and no dump attempt (no Permission Denial).
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
cmd --es cmd set-tracking --es value on
sleep 2
s=$(dump_state "p11g-$RANDOM") || fail "no dump-state"
[ "$(tracking_of "$s")" = NeedsPermission ] || fail "want tracking=NeedsPermission, got: $s"
if "$ADB" logcat -d | grep -q "Permission Denial.*$PKG"; then fail "a Permission Denial was logged before the grant"; fi
echo "ok: NeedsPermission before the grant"

# The exact string, through adb shell.
LINE="/system/bin/cmd package grant --user 0 com.dylenq.eq android.permission.DUMP"
"$ADB" shell "$LINE" || fail "grant command exited non-zero"
granted || fail "dumpsys does not show DUMP granted after the command"
echo "ok: DUMP granted by: $LINE"

# Live recheck: the controller re-reads the permission; the debug path asks for it like Settings does.
cmd --es cmd recheck-tracking
for _ in 1 2 3 4 5 6; do
  sleep 1
  s=$(dump_state "p11g-$RANDOM") || fail "no dump-state"
  case "$(tracking_of "$s")" in Working*) echo "ok: $(tracking_of "$s") after the grant, no restart"; exit 0;; esac
done
fail "tracking did not become Working after the grant: $s"
