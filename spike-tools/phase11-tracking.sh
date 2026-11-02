#!/usr/bin/env bash
# Phase 11 emulator check: advanced player tracking end to end. A silent player (playerstub, no broadcast) gets an
# effect with control once DUMP is granted, loses it after it stops, and a player that does announce still works.
# Also checks that nothing dumps (and no Permission Denial is logged) while tracking is off or DUMP is not granted.
# Emulator only: it observes attach and release, not sound (the emulator has no audio output).
# Usage: phase11-tracking.sh
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
source "$ROOT/spike-tools/phase3-lib.sh"
cd "$ROOT"

STUB=com.dylenq.playerstub
fail() { echo "FAIL: $*" >&2; exit 1; }
rows_of() { echo "$1" | sed -E 's/.* rows=([^ ]*).*/\1/'; }
tracking_of() { echo "$1" | sed -E 's/.* tracking=([^ ]+).*/\1/'; }

# wait_row <timeout_s> <want: present|gone> <pattern> : polls dump-state until the rows match; echoes the last state
wait_row() {
  local timeout="$1" want="$2" pat="$3" t0 s r
  t0=$(now_s)
  while :; do
    s=$(dump_state "p11-$RANDOM") || fail "no dump-state"
    r=$(rows_of "$s")
    if [ "$want" = present ]; then case "$r" in *$pat*) echo "$s"; return 0;; esac
    else case "$r" in *$pat*) ;; *) echo "$s"; return 0;; esac
    fi
    [ $(( $(now_s) - t0 )) -gt "$timeout" ] && { echo "$s"; return 1; }
    sleep 1
  done
}

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" uninstall "$STUB" >/dev/null 2>&1 || true
(./gradlew --offline :app:installDebug :playerstub:installDebug >/dev/null)
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# Part 1: no grant. Tracking on must not dump, so no Permission Denial may be logged for this app.
"$ADB" logcat -c
"$ADB" shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 3
cmd --es cmd set-tracking --es value on
"$ADB" shell am start -n "$STUB/.LauncherActivity" --es play start >/dev/null
sleep 6
s=$(dump_state p11-nogrant) || fail "no dump-state without the grant"
st=$(tracking_of "$s")
say "without DUMP: tracking=$st rows=$(rows_of "$s")"
case "$st" in NeedsPermission|Off) ;; *) fail "expected NeedsPermission or Off without the grant, got $st";; esac
if "$ADB" logcat -d | tr -d '\r' | grep -i "Permission Denial" | grep -q "$PKG"; then fail "a Permission Denial was logged for $PKG while ungranted"; fi
case "$(rows_of "$s")" in *"$STUB"*) fail "the stub was tracked without the grant";; esac
"$ADB" shell am start -n "$STUB/.LauncherActivity" --es play stop >/dev/null
cmd --es cmd set-tracking --es value off
sleep 1
s=$(dump_state p11-off) || fail "no dump-state with tracking off"
[ "$(tracking_of "$s")" = Off ] || fail "expected Off after set-tracking off, got $(tracking_of "$s")"

# Part 2: grant first, then turn tracking on and start the silent player.
"$ADB" shell pm grant "$PKG" android.permission.DUMP
cmd --es cmd set-tracking --es value on
sleep 1
"$ADB" shell am start -n "$STUB/.LauncherActivity" --es play start >/dev/null
t0=$(now_s)
s=$(wait_row 10 present "$STUB/OK/tracked=true") || fail "no controlled tracked row for the stub within 10 s: $(rows_of "$s") tracking=$(tracking_of "$s")"
say "attached after $(( $(now_s) - t0 )) s: rows=$(rows_of "$s") tracking=$(tracking_of "$s")"
case "$(tracking_of "$s")" in Working) ;; *) fail "expected tracking=Working, got $(tracking_of "$s")";; esac

# Part 3: stop it; two missed scans must release it.
"$ADB" shell am start -n "$STUB/.LauncherActivity" --es play stop >/dev/null
t0=$(now_s)
s=$(wait_row 15 gone "$STUB/") || fail "the stub row was still there 15 s after stopping: $(rows_of "$s")"
say "released after $(( $(now_s) - t0 )) s"

# Part 4: a player that announces itself still attaches (the emulator accepts a made-up id as a listed session).
"$ADB" shell am broadcast -n "$STUB/.TriggerReceiver" -a com.dylenq.playerstub.SEND --ei session 7001 --ei open 1 >/dev/null
s=$(wait_row 10 present "$STUB/*/tracked=false") || fail "the announced session did not show up: $(rows_of "$s")"
say "announced: rows=$(rows_of "$s")"
"$ADB" shell am broadcast -n "$STUB/.TriggerReceiver" -a com.dylenq.playerstub.SEND --ei session 7001 --ei open 0 >/dev/null
cmd --es cmd set-tracking --es value off
echo "PASS: tracked session attached with control and released; announced session still works; no dump without the grant"
