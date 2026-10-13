#!/usr/bin/env bash
# Creates YOUR release keystore. Run this yourself in your own terminal; keytool asks for the
# password interactively. This script never accepts, reads or prints a password.
#   scripts/create-keystore.sh [--keystore PATH] [--alias NAME]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

KEYSTORE="$HOME/.eq-android/release.jks"
ALIAS="eq"
while [ "$#" -gt 0 ]; do
  case "$1" in
    --keystore) [ "$#" -ge 2 ] || { echo "--keystore needs a path" >&2; exit 2; }; KEYSTORE="$2"; shift 2 ;;
    --alias) [ "$#" -ge 2 ] || { echo "--alias needs a name" >&2; exit 2; }; ALIAS="$2"; shift 2 ;;
    *password*|*storepass*|*keypass*)
      echo "This script never takes a password on the command line; keytool prompts for it." >&2; exit 2 ;;
    *) echo "usage: scripts/create-keystore.sh [--keystore PATH] [--alias NAME]" >&2; exit 2 ;;
  esac
done

# Checked first, before anything else touches keytool.
if [ -e "$KEYSTORE" ]; then
  echo "ERROR: $KEYSTORE already exists; refusing to overwrite it." >&2
  exit 1
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$JAVA_HOME/bin/java" ]; then
  # shellcheck disable=SC1091
  source "$ROOT/spike-tools/env.sh"
fi

DIR="$(dirname "$KEYSTORE")"
mkdir -p "$DIR"
chmod 700 "$DIR"

"$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000
chmod 600 "$KEYSTORE"

ABS="$(cd "$DIR" && pwd)/$(basename "$KEYSTORE")"
if [ ! -e "$ROOT/keystore.properties" ]; then
  {
    echo "# Git-ignored. Add storePassword=... yourself in an editor, or export EQ_KEYSTORE_PASSWORD."
    echo "# One password is used for the store and the key (PKCS12)."
    echo "storeFile=$ABS"
    echo "keyAlias=$ALIAS"
  } > "$ROOT/keystore.properties"
  chmod 600 "$ROOT/keystore.properties"
  echo "Wrote keystore.properties (no password in it)."
fi

cat <<MSG

Keystore created: $ABS
BACK IT UP NOW (password manager plus an offline copy). If you lose it, no update can ever
install over an app signed with it.
scripts/release-apk.sh prints the signing certificate fingerprint after a build.
MSG
