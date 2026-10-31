#!/usr/bin/env bash
# Structural and consistency checks for README.md, CHANGELOG.md and docs/. Prints PASS/FAIL, exits non-zero on FAIL.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
FAILS=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; FAILS=$((FAILS + 1)); }
has() { grep -qF -- "$2" "$1"; }

[ -f README.md ] || { echo "FAIL: README.md missing"; exit 1; }
for w in "Install" "Limits" "Privacy" "Build from source" "License" "GPL-3.0" "never run" "85 ms" "physical phone" "global mode"; do
  if grep -qi -- "$w" README.md; then pass "README mentions '$w'"; else fail "README mentions '$w'"; fi
done

IMGS="$(grep -oE '(src|srcset)="[^"]+"|!\[[^]]*\]\([^)]+\)' README.md | sed -E 's/^(src|srcset)="//; s/"$//; s/^!\[[^]]*\]\(//; s/\)$//')"
for i in $IMGS; do
  case "$i" in http*) continue ;; esac
  if [ -f "$i" ]; then pass "image exists: $i"; else fail "image exists: $i"; fi
done
for n in graphic-light graphic-dark parametric-light parametric-dark settings; do
  has README.md "docs/screenshot-$n.png" && pass "README references screenshot-$n" || fail "README references screenshot-$n"
done
has README.md "docs/icon-512.png" && pass "README references the icon" || fail "README references the icon"

if grep -rIl "0.0.1-spike" README.md docs CHANGELOG.md >/dev/null 2>&1; then fail "no 0.0.1-spike in README/docs/CHANGELOG"; else pass "no 0.0.1-spike in README/docs/CHANGELOG"; fi
if grep -rIlP "\x{2014}" README.md docs CHANGELOG.md >/dev/null 2>&1 || grep -rIl $'\xe2\x80\x94' README.md docs CHANGELOG.md >/dev/null 2>&1; then fail "no em dash in README/docs/CHANGELOG"; else pass "no em dash in README/docs/CHANGELOG"; fi
VERSION="$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts | head -1)"
grep -qE "^## ${VERSION//./\\.} - [0-9]{4}-[0-9]{2}-[0-9]{2}$" CHANGELOG.md && pass "CHANGELOG has $VERSION entry" || fail "CHANGELOG has $VERSION entry"

BT="${EQ_BUILD_TOOLS:-$HOME/Library/Android/sdk/build-tools/36.0.0}"
APK="build/release/EQ-${VERSION}-TESTSIGNED.apk"
if grep -qiE "no network|no network permission" README.md; then
  if [ -f "$APK" ] && [ -x "$BT/aapt2" ]; then
    if "$BT/aapt2" dump permissions "$APK" 2>&1 | grep -q "android.permission.INTERNET"; then fail "README says no network but the APK lists INTERNET"; else pass "APK has no INTERNET permission (README claims none)"; fi
  else fail "cannot check INTERNET: $APK or aapt2 missing"; fi
fi

LINKS="$(grep -oE '\]\(([^)#]+)(#[^)]*)?\)' README.md CHANGELOG.md docs/*.md | sed -E 's/^([^:]+):\]\(([^)#]+).*/\1 \2/')"
while read -r src l; do
  [ -n "${l:-}" ] || continue
  case "$l" in http*|mailto:*) continue ;; esac
  base="$(dirname "$src")"; [ "$base" = "." ] && t="$l" || t="$base/$l"
  if [ -e "$t" ] || [ -e "$l" ]; then pass "link resolves: $src -> $l"; else fail "link resolves: $src -> $l"; fi
done <<< "$LINKS"

[ "$FAILS" = "0" ] || { echo "$FAILS check(s) failed"; exit 1; }
echo "all docs checks passed"
