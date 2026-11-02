#!/usr/bin/env bash
# Runs the JVM unit tests and the full instrumented suite under a time limit, so a stuck test fails the
# run instead of holding it open forever. Usage: spike-tools/full-suite.sh [limit-seconds, default 1500]
set -u
cd "$(dirname "$0")/.."
source spike-tools/env.sh >/dev/null 2>&1
limit="${1:-1500}"
perl -e 'alarm shift; exec @ARGV' "$limit" ./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest --console=plain
rc=$?
if [ "$rc" = 142 ]; then
  echo "TIMED OUT after ${limit}s; the app is being stopped" >&2
  ./gradlew --stop >/dev/null 2>&1
  adb shell am force-stop com.dylenq.eq
  adb shell am force-stop com.dylenq.eq.test
fi
exit "$rc"
