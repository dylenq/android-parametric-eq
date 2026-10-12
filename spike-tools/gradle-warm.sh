#!/usr/bin/env bash
# Resumable first-sync helper: Maven is ~30 KB/s here, the Gradle cache keeps partial downloads.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT"
MAX="${1:-8}"
LOG="${GRADLE_WARM_LOG:-build/gradle-warm.log}"
mkdir -p "$(dirname "$LOG")"
for i in $(seq 1 "$MAX"); do
  echo "=== attempt $i/$MAX $(date -u +%FT%TZ) ===" >> "$LOG"
  if ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest >> "$LOG" 2>&1; then
    echo "gradle-warm: success on attempt $i"
    exit 0
  fi
  echo "gradle-warm: attempt $i failed; gradle cache size (KB): $(du -sk "$HOME/.gradle/caches" | cut -f1)"
done
echo "gradle-warm: all $MAX attempts failed" >&2
exit 1
