#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT/spike-tools/env.sh"
cd "$ROOT/rust-probe"
cargo ndk -t arm64-v8a -t x86_64 -t armeabi-v7a -P 28 -o "$ROOT/app/src/main/jniLibs" build --release
