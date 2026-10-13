# Changelog

All notable changes are listed here, in the style of Keep a Changelog.

## 0.1.0 - 2026-10-13

First version. It has not been tested on a physical phone; every check ran on an Android 17 arm64 emulator.

### Added

- System-wide equalizer for other apps' audio using Android's DynamicsProcessing effect, no root needed.
- Graphic mode with 5, 10, 15 or 31 bands (plus or minus 12 dB per slider) and parametric mode with up to 16 bands (peak, low shelf, high shelf, low pass, high pass; gain up to plus or minus 24 dB).
- Preamp with a suggested value, a limiter on by default, undo and redo, and a bypass switch in the app and the notification.
- Settings: global mode (legacy), start when the phone turns on, and a list of detected audio sessions.
- Light and dark themes with dynamic colour, an adaptive launcher icon, and a signed release build script (`scripts/release-apk.sh`).

### Known limitations

- About 85 ms of added delay.
- Narrow low-frequency filters are approximate (about 5.7 dB off for a Q 8 peak at 100 Hz).
- Apps that do not announce an audio session need global mode, which may not work on some phones.
- One undo history is shared by both modes; the frequency keyboard has no "k", so type Hz; the node menu closes on rotation.
- One unexplained service crash after a restart was seen once and not reproduced.
- Not verified: physical phones, real players, global mode audibility, start on boot after a real restart, the themed icon, TalkBack, dynamic colour with wallpapers.
- The APK is not minified (about 25 MB). The release workflow has never run.
