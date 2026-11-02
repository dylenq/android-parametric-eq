#!/usr/bin/env bash
# Drives AutoEq import and export through the real system file picker on the emulator, with the release APK.
# Usage: phase14-picker.sh [apk]. Prints PASS and exits 0, or says what failed and exits 1.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh" >/dev/null 2>&1
APK="${1:-$ROOT/build/release/EQ-0.3.0-TESTSIGNED.apk}"
PKG=com.dylenq.eq
FILE="Test Phones ParametricEQ.txt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"; adb shell rm -f "/sdcard/Download/$FILE" "/sdcard/Download/Test Phones ParametricEQ (1).txt" /sdcard/u.xml >/dev/null 2>&1' EXIT
die() { echo "FAIL: $*"; exit 1; }
find_node() {
  adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1
  adb exec-out cat /sdcard/u.xml | python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
rx = re.compile(sys.argv[1])
for n in ET.fromstring(sys.stdin.read()).iter("node"):
    if rx.search(n.get("text", "")) or rx.search(n.get("content-desc", "")):
        a, b, c, d = map(int, re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds")).groups())
        print((a + c) // 2, (b + d) // 2)
        break
' "$1"
}
tap() { local xy; xy="$(find_node "$1")"; [ -n "$xy" ] || die "no node matches /$1/"; adb shell input tap $xy; sleep 2; }
need() { [ -n "$(find_node "$1")" ] || die "screen does not show /$1/"; }

adb uninstall "$PKG" >/dev/null 2>&1
adb install -r "$APK" >/dev/null || die "install failed"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
cat > "$TMP/$FILE" <<'T'
Preamp: -6.4 dB
Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
Filter 2: ON PK Fc 280 Hz Gain -3.2 dB Q 1.10
Filter 3: ON PK Fc 2400 Hz Gain 2.1 dB Q 2.00
Filter 4: ON HSC Fc 10000 Hz Gain -1.5 dB Q 0.70
T
adb shell rm -f "/sdcard/Download/$FILE"
adb push "$TMP/$FILE" /sdcard/Download/ >/dev/null || die "push failed"

adb shell am start -W -n "$PKG/.MainActivity" >/dev/null; sleep 3
tap '^Preset: '
tap '^Import'
adb shell dumpsys window | grep -q 'mCurrentFocus.*documentsui' || die "the picker did not open"
[ -n "$(find_node '^Test Phones')" ] || { adb shell input tap 72 126; sleep 2; tap '^Downloads$'; sleep 2; }
tap '^Test Phones'; sleep 2
adb shell dumpsys window | grep -q "mCurrentFocus.*$PKG" || die "picking the file did not return to the app (is it greyed out?)"
need '^Preset: Test Phones'
echo "import ok: the preset is active and the app is in front"

tap '^Preset: '
tap '^Export'
adb shell dumpsys window | grep -q 'mCurrentFocus.*documentsui' || die "the save picker did not open"
tap '^SAVE$|^Save$'; sleep 2
if [ -n "$(find_node '^REPLACE$|^Replace$|^OK$')" ]; then tap '^REPLACE$|^Replace$|^OK$'; sleep 2; fi
adb shell dumpsys window | grep -q "mCurrentFocus.*$PKG" || die "saving did not return to the app"
adb shell ls /sdcard/Download/ | tr -d '\r' > "$TMP/list"; cat "$TMP/list"
adb exec-out cat "/sdcard/Download/Test Phones ParametricEQ (1).txt" > "$TMP/out.txt" 2>/dev/null
[ -s "$TMP/out.txt" ] || adb exec-out cat "/sdcard/Download/$FILE" > "$TMP/out.txt"
cat "$TMP/out.txt"
grep -q 'Preamp: -6.4' "$TMP/out.txt" || die "the exported file lacks the preamp"
grep -q 'Fc 2400' "$TMP/out.txt" || die "the exported file lacks a filter"
adb logcat -d | grep -E 'FATAL EXCEPTION' && die "a crash was logged"
echo "PASS: import and export through the real picker, with the release APK"
