# Changelog

All notable changes are listed here, in the style of Keep a Changelog.

## 0.3.0 - 2026-11-03

Checked on an Android 17 arm64 emulator only; this version has not been on a physical phone. The release APK is signed with the project's public test key. Real players, a real Shizuku server, real headphones, Bluetooth and USB outputs, the system file picker and how any of this sounds are not verified. The README has the full list.

### Added

- Advanced player tracking, off by default. With the `DUMP` permission, granted once with an adb command or through Shizuku, EQ reaches players that never announce an audio session, and lets go when they stop. Settings always shows whether it is off, needs the permission, is working, needs one reboot or is not supported. Apps that announce keep working in every state.
- Per-app control in Settings. The new Apps section lists the apps EQ has seen. Each one has a switch to turn EQ off for that app and a picker to give it its own preset instead of the live EQ. Global mode ignores these settings.
- Device presets. The new Output devices section in Settings lists the speakers and headphones EQ has seen. Give a device a preset and it is applied to the live EQ when you switch to that device. A device with no preset keeps the current sound.
- A Quick Settings tile that turns EQ on and off. It works even when the EQ service is not running.
- Import and export of AutoEq files from the preset list. Import reads a ParametricEQ.txt or GraphicEQ.txt as a new preset and tells you how many filters were read and how many were skipped or clamped. Export saves the parametric EQ as a ParametricEQ.txt. Bass, treble and balance are not saved in the file, and Export is only available in parametric mode.

### Fixed

- A notice about a rejected global mode could land above the visible part of a long Settings list. Settings now scrolls up to it.
- A damaged entry in the saved settings now costs only that entry instead of the whole file. The app says so, and a copy of the original file is kept.
- Deleting a band, or undoing a deletion, no longer leaves a filter-type menu open on the band that takes its place.
- A band field's unsaved text could be written to the wrong band after the band above it was deleted.
- The app no longer crashes when Android refuses to start the service in the background, or when the audio core fails to load. It shows an error screen in the second case.
- When saved settings cannot be written (a full disk, for example) the app says so and retries, instead of silently losing changes after a restart.
- Settings are flushed to disk before they replace the old file, so a power cut cannot leave an empty one.
- A snackbar queued behind another could stay on screen forever and dismiss the wrong one.
- Reset all showed "Undo" after a failed reset, and interfered with a drag held at the same time.
- Switching mode with a band field focused needed a second tap.
- The value bubble could appear over the wrong graphic slider.
- A pinch that started as a drag could change the Q of a neighbouring band.
- An invalid output sample rate from the phone made every attach fail. It now falls back to 48 kHz, and the curve follows output changes.
- Ctrl+Z in a band field is left to the field. Typing in any field clears the row's error message.

### Changed

- Graphic curve computation is about 60% faster.
- CI and Dependabot configuration, a Docker build environment and a keystore example. The README now covers setup, dependencies and environment variables.

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
