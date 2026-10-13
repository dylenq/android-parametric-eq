#!/usr/bin/env bash
# Install a release APK on the emulator and check it runs. Uses only the launcher intent, pm grant,
# adb input and uiautomator (a release build has no debug command receiver).
#   scripts/verify-release-on-emulator.sh <apk-path> [screenshot-out]
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck disable=SC1091
source "$ROOT/spike-tools/env.sh"
# shellcheck disable=SC1091
source "$ROOT/spike-tools/phase3-lib.sh"

APK="${1:-}"
SHOT="${2:-build/release/verify-settings.png}"
[ -n "$APK" ] && [ -f "$APK" ] || { echo "usage: $0 <apk-path> [screenshot-out]" >&2; exit 2; }
die() { echo "FAIL: $*" >&2; exit 1; }

find_node() {
  "$ADB" shell uiautomator dump /sdcard/relui.xml >/dev/null 2>&1 || true
  "$ADB" exec-out cat /sdcard/relui.xml | python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
rx = re.compile(sys.argv[1])
try:
    root = ET.fromstring(sys.stdin.read())
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            a, b, c, d = map(int, m.groups())
            print((a + c) // 2, (b + d) // 2)
            break
' "$1"
}
tap_node() {
  local xy; xy=$(find_node "$1")
  [ -n "$xy" ] || { say "no node matches /$1/"; return 1; }
  say "tap /$1/ at $xy"
  "$ADB" shell input tap $xy
}

bash "$ROOT/spike-tools/emulator-up.sh"
"$ADB" uninstall "$PKG" >/dev/null 2>&1 || true
"$ADB" install -r "$APK" >/dev/null || die "adb install rejected the APK"
"$ADB" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

PKGINFO="$("$ADB" shell dumpsys package "$PKG" | tr -d '\r')"
echo "$PKGINFO" | grep -m1 'versionName=' || die "no versionName in dumpsys"
echo "$PKGINFO" | grep -q 'versionName=0.1.0' || die "versionName is not 0.1.0"
if echo "$PKGINFO" | grep -m1 -E '^ *(pkgFlags|flags)=' | grep -q DEBUGGABLE; then die "app is DEBUGGABLE"; fi
echo "not debuggable"

"$ADB" logcat -b crash -c
"$ADB" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || die "launch failed"
secs=$(wait_fg 20) || die "service never went foreground"
echo "service foreground after ${secs}s"
sleep 2
if "$ADB" logcat -b crash -d | grep -q "$PKG"; then die "crash buffer mentions $PKG"; fi

tap_node '^More options$' || die "More options not found"; sleep 1
tap_node '^Settings$' || die "Settings entry not found"; sleep 2
if [ -z "$(find_node 'Version 0\.1\.0')" ]; then
  "$ADB" shell input swipe 540 1800 540 600 300; sleep 1
fi
[ -n "$(find_node 'Version 0\.1\.0')" ] || die "Settings does not show 'Version 0.1.0'"
mkdir -p "$(dirname "$SHOT")"
"$ADB" exec-out screencap -p > "$SHOT"
[ -s "$SHOT" ] || die "screenshot is empty"
"$ADB" shell input keyevent BACK; sleep 1
"$ADB" shell input keyevent HOME
echo "OK: installed, service foreground, About shows 0.1.0"
