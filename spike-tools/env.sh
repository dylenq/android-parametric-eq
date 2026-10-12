#!/usr/bin/env bash
# Source this file; it never edits a shell profile.
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.1.12297006"
export PATH="$HOME/.cargo/bin:$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export ADB="$ANDROID_HOME/platform-tools/adb"
