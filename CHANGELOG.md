# Changelog

All notable changes are listed here, in the style of Keep a Changelog.

## 0.2.0 - 2026-10-31

Checked on an Android 17 arm64 emulator and installed on one physical phone by the author. The release APK is signed with the project's public test key, not a private release key, so a later build signed with a real key will need an uninstall first (which deletes saved settings and presets).

### Added

- Named presets: save the whole sound (mode, bands or sliders, preamp, bass, treble, balance), apply, rename and delete your own, with four built-in presets (Flat, Bass, Treble, Vocal) that cannot be edited. The title bar shows the active preset and an Edited marker. Applying a preset is one undo step.
- Bass and treble shelf controls and a left/right balance control in a Tone & balance card.
- Engine band probe: at start the app measures how many bands the phone's audio effect accepts and fits the curve to that grid. Settings shows the measured count.
- Attaching the effect to an audio session retries briefly before it is reported as failed.

### Known limitations

- A state file written with presets and then opened by an older build drops the presets on that build's next save.
- Preset behaviour was verified on an emulator only. The probe accepted the largest grid on the emulator, so a smaller grid was only exercised with a forced test setting.

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
