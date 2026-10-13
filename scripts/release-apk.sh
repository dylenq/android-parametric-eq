#!/usr/bin/env bash
# Build, sign and verify the release APK in one command.
#   scripts/release-apk.sh          real release (needs keystore.properties or EQ_KEYSTORE_* env)
#   scripts/release-apk.sh --test   throwaway TEST key, output named *-TESTSIGNED.apk (never publish)
# Env: EQ_OFFLINE=1 passes --offline to Gradle, EQ_BUILD_TOOLS overrides the build-tools dir.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TEST=0
case "${1:-}" in
  "") ;;
  --test) TEST=1 ;;
  *) echo "usage: scripts/release-apk.sh [--test]" >&2; exit 2 ;;
esac
[ "$#" -le 1 ] || { echo "usage: scripts/release-apk.sh [--test]" >&2; exit 2; }

fail() { echo "FAIL: $*" >&2; exit 1; }

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  # shellcheck disable=SC1091
  source "$ROOT/spike-tools/env.sh"
fi
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="${EQ_BUILD_TOOLS:-$ANDROID_HOME/build-tools/36.0.0}"
for t in apksigner zipalign aapt2; do
  [ -x "$BT/$t" ] || fail "$t not found in $BT (set EQ_BUILD_TOOLS)"
done
KEYTOOL="$JAVA_HOME/bin/keytool"

TEST_DIR="$ROOT/build/test-signing"
TEST_JKS="$TEST_DIR/EQ-TEST-ONLY.jks"
# Throwaway key password: fixed, non-secret, used for nothing else (must match app/build.gradle.kts).
TEST_PW="eq-test-only-not-a-secret"

GRADLE_ARGS=()
[ "${EQ_OFFLINE:-0}" = "1" ] && GRADLE_ARGS+=(--offline)

SUFFIX=""
if [ "$TEST" = "1" ]; then
  if [ -n "${CI:-}" ] || [ -n "${GITHUB_ACTIONS:-}" ]; then
    fail "CI refuses test signing: --test is for local emulator checks only"
  fi
  if [ ! -f "$TEST_JKS" ]; then
    mkdir -p "$TEST_DIR"
    "$KEYTOOL" -genkeypair -keystore "$TEST_JKS" -storetype PKCS12 -alias eqtest \
      -keyalg RSA -keysize 2048 -validity 3650 \
      -dname "CN=EQ TEST ONLY (do not publish)" \
      -storepass "$TEST_PW" -keypass "$TEST_PW" >/dev/null 2>&1 || fail "could not create the test keystore"
  fi
  GRADLE_ARGS+=(-Peq.testSigning=true)
  SUFFIX="-TESTSIGNED"
fi

# Stale outputs must never pass for a fresh build.
mkdir -p build/release
rm -f build/release/EQ-*.apk app/build/outputs/apk/release/*.apk

./gradlew "${GRADLE_ARGS[@]}" :app:assembleRelease

APK_SRC=""
for f in app/build/outputs/apk/release/*.apk; do
  [ -e "$f" ] || continue
  case "$f" in *unsigned*) continue ;; esac
  APK_SRC="$f"
done
[ -n "$APK_SRC" ] || fail "no signed release APK was produced"

VERSION="$("$BT/aapt2" dump badging "$APK_SRC" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
VCODE="$("$BT/aapt2" dump badging "$APK_SRC" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
[ -n "$VERSION" ] || fail "could not read versionName from the APK"
OUT="build/release/EQ-${VERSION}${SUFFIX}.apk"
cp "$APK_SRC" "$OUT"

SIGN_OUT="$("$BT/apksigner" verify --verbose --print-certs "$OUT" 2>&1)" || { echo "$SIGN_OUT" >&2; fail "apksigner verify failed"; }
echo "$SIGN_OUT"
echo "$SIGN_OUT" | grep -q "Verifies" || fail "apksigner did not report Verifies"
if [ "$TEST" = "0" ] && echo "$SIGN_OUT" | grep -q "EQ TEST ONLY"; then
  rm -f "$OUT"
  fail "the real APK is signed with the TEST key"
fi

ALIGN_OUT="$("$BT/zipalign" -c -P 16 -v 4 "$OUT" 2>&1)" || { echo "$ALIGN_OUT" | tail -5 >&2; fail "zipalign 16 KB check failed"; }
echo "$ALIGN_OUT" | grep -q "Verification successful" || fail "zipalign did not report Verification successful"
echo "zipalign: Verification successful (16 KB)"

MANIFEST="$( { "$BT/aapt2" dump permissions "$OUT"; "$BT/aapt2" dump xmltree --file AndroidManifest.xml "$OUT"; } 2>&1 )"
echo "$MANIFEST" | grep -q "android.permission.RECORD_AUDIO" && fail "manifest holds RECORD_AUDIO"
echo "$MANIFEST" | grep -q "DebugCommandReceiver" && fail "manifest holds DebugCommandReceiver"
echo "manifest: no RECORD_AUDIO, no DebugCommandReceiver"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q -o "$OUT" 'classes*.dex' -d "$TMP"
HITS=0
for d in "$TMP"/classes*.dex; do
  if [ -x "$BT/dexdump" ]; then
    n="$("$BT/dexdump" -d "$d" 2>/dev/null | grep -c "DebugCommandReceiver" || true)"
  else
    n="$(strings "$d" | grep -c "DebugCommandReceiver" || true)"
  fi
  HITS=$((HITS + n))
done
[ "$HITS" = "0" ] || fail "dex holds DebugCommandReceiver ($HITS matches)"
echo "dex: no DebugCommandReceiver"

LIST="$(unzip -l "$OUT")"
for abi in arm64-v8a armeabi-v7a x86_64; do
  echo "$LIST" | grep -q "lib/$abi/" || fail "missing ABI lib/$abi"
done
echo "abis: arm64-v8a armeabi-v7a x86_64"

echo "versionName=$VERSION versionCode=$VCODE"
echo "path: $OUT"
echo "size: $(wc -c < "$OUT" | tr -d ' ') bytes"
echo "apk sha256: $(shasum -a 256 "$OUT" | awk '{print $1}')"
echo "$SIGN_OUT" | grep "Signer #1 certificate SHA-256 digest" || true
echo "OK: $OUT"
