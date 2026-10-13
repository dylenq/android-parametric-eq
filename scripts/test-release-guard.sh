#!/usr/bin/env bash
# Automated test of the release failure paths and repo hygiene. Prints PASS:/FAIL: lines.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [ -f keystore.properties ] || [ -n "${EQ_KEYSTORE_FILE:-}" ]; then
  echo "SKIP: keystore.properties or EQ_KEYSTORE_FILE is present; the no-key condition cannot be reproduced (nothing was touched)."
  exit 0
fi

RC=0
pass() { echo "PASS: $*"; }
fail() { echo "FAIL: $*"; RC=1; }

mkdir -p build/guard-test
KEEP="$(mktemp -d)"
trap 'rm -rf "$KEEP"' EXIT
# release-apk.sh clears stale APKs; set aside any existing build output and put it back afterwards.
for f in build/release/EQ-*.apk; do [ -e "$f" ] && cp -p "$f" "$KEEP/"; done
restore() { mkdir -p build/release; for f in "$KEEP"/EQ-*.apk; do [ -e "$f" ] && cp -p "$f" build/release/; done; }

# Case A: no key, no flag
OUT_A="$(env -u EQ_KEYSTORE_FILE -u EQ_KEYSTORE_PASSWORD -u EQ_KEY_ALIAS -u EQ_KEY_PASSWORD -u CI -u GITHUB_ACTIONS EQ_OFFLINE=1 bash scripts/release-apk.sh 2>&1)"
RC_A=$?
[ "$RC_A" -ne 0 ] && pass "A: no-key release exits non-zero ($RC_A)" || fail "A: no-key release exited 0"
echo "$OUT_A" | grep -q "Release signing is not configured" && pass "A: message 'Release signing is not configured' shown" || fail "A: message missing"
left="$(ls build/release/EQ-*.apk app/build/outputs/apk/release/*.apk 2>/dev/null || true)"
[ -z "$left" ] && pass "A: no release APK left behind" || fail "A: APK left behind: $left"
echo "$OUT_A" | grep -qiE 'debug key|unsigned apk' && fail "A: output mentions debug key or unsigned APK" || pass "A: no debug-key or unsigned APK fallback"

# Case B: CI refuses test signing
OUT_B="$(CI=true bash scripts/release-apk.sh --test 2>&1)"
RC_B=$?
[ "$RC_B" -ne 0 ] && echo "$OUT_B" | grep -q "CI refuses test signing" && pass "B: CI refuses --test" || fail "B: CI did not refuse --test (rc=$RC_B)"
ls build/release/*TESTSIGNED* >/dev/null 2>&1 && fail "B: a TESTSIGNED file exists after the CI call" || pass "B: no TESTSIGNED file produced"

# Case C: keystore helper refuses to overwrite
: > build/guard-test/exists.jks
OUT_C="$(bash scripts/create-keystore.sh --keystore build/guard-test/exists.jks </dev/null 2>&1)"
RC_C=$?
[ "$RC_C" -ne 0 ] && echo "$OUT_C" | grep -q "already exists" && pass "C: create-keystore refuses to overwrite" || fail "C: no refusal (rc=$RC_C)"
[ ! -s build/guard-test/exists.jks ] && [ ! -f keystore.properties ] && pass "C: file unchanged, no keystore.properties created" || fail "C: keytool seems to have run"
rm -f build/guard-test/exists.jks

# Case D: hygiene
git check-ignore -q keystore.properties && pass "D: keystore.properties is git-ignored" || fail "D: keystore.properties not ignored"
git check-ignore -q build/test-signing/EQ-TEST-ONLY.jks && pass "D: test keystore is git-ignored" || fail "D: test keystore not ignored"
tracked="$(git ls-files | grep -Ei '\.(jks|keystore)$|^keystore\.properties$' || true)"
[ -z "$tracked" ] && pass "D: no keystore file tracked" || fail "D: tracked: $tracked"
hits="$(git grep -nEi 'BEGIN [A-Z ]*PRIVATE KEY|storePassword *= *[^ ]' -- . ':!.planning' ':!app/build.gradle.kts' ':!scripts' ':!docs' || true)"
[ -z "$hits" ] && pass "D: no key block or storePassword value in tracked files" || fail "D: $hits"
pw="$(git grep -l "eq-test-only-not-a""-secret" -- . ':!.planning' | sort | tr '\n' ' ')"
[ "$pw" = "app/build.gradle.kts scripts/release-apk.sh " ] && pass "D: test password only in build.gradle.kts and release-apk.sh" || fail "D: test password found in: $pw"

restore
exit $RC
