# Build environment for people without the Android toolchain. Compiles the Rust core and the debug
# APK, and runs the tests that need no device. It has no use at runtime: the app is an APK installed
# on a phone, so there is nothing to host (and no vercel.json or netlify.toml to go with it).
#
#   docker build -t eq-android-build .
#   docker run --rm -v "$PWD:/src" eq-android-build
#
# The APK lands in app/build/outputs/apk/debug/. Versions match README.md and .github/workflows/.
FROM eclipse-temurin:25-jdk-noble

ENV ANDROID_HOME=/opt/android-sdk \
    CARGO_HOME=/root/.cargo \
    RUSTUP_HOME=/root/.rustup
ENV PATH=/root/.cargo/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH

RUN apt-get update \
    && apt-get install -y --no-install-recommends build-essential curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip \
    && unzip -q /tmp/tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/tools.zip \
    && (yes | sdkmanager --licenses > /dev/null || true) \
    && sdkmanager "ndk;27.1.12297006" "build-tools;36.0.0" "platforms;android-37.0"

RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --profile minimal \
    && rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android \
    && cargo install cargo-ndk --locked --version 4.1.2

WORKDIR /src
CMD ["sh", "-c", "cargo test --workspace && ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug"]
