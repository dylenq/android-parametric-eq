# Releasing EQ for Android

Release 0.2.0 was published by hand with a test-signed APK, from github.com/dylenq/android-parametric-eq (the git remote is named `github`). No private release keystore exists yet and the three secrets below are not set. These are the steps for a first properly signed release. `<version>` is the `versionName` in `app/build.gradle.kts`.

The GitHub workflow in `.github/workflows/release.yml` has never completed a run. It was checked statically only (the YAML parses, and `scripts/check-workflow.sh` confirms pinning, permissions and that no secret appears inside a `run:` block). Its first real run may fail for reasons nobody could see in advance, such as the Android command line tools path on the runner image, or how long `cargo install cargo-ndk` takes. It creates a draft release, so nothing becomes public until you press publish.

Pinning: `actions/checkout`, `actions/setup-java`, `gradle/actions/setup-gradle` and `softprops/action-gh-release` are pinned to full commit SHAs, each with a comment naming the release tag. The two annotated tags (gradle/actions v6.4.0 and softprops/action-gh-release v3.0.3) are pinned to the commit the tag points at. `Swatinem/rust-cache` is not used.

## Secrets the workflow needs

Set these three as repository secrets (Settings, Secrets and variables, Actions):

- `EQ_KEYSTORE_BASE64`: your release keystore, base64 encoded (step 7 below).
- `EQ_KEYSTORE_PASSWORD`: the keystore password you typed when creating it.
- `EQ_KEY_ALIAS`: the key alias. `scripts/create-keystore.sh` uses `eq` unless you pass `--alias`.

The workflow reads them only through `env:`, never echoes them, and deletes the decoded keystore in a final step that always runs.

## Steps

Run each command in your own terminal, from the repository root. Pick the keystore password yourself and keep it in a password manager.

1. Create the release keystore. keytool asks for the password and your details itself.

```bash
bash scripts/create-keystore.sh
```

2. Back the keystore up now, in a password manager and an offline copy. If you lose it, no update can ever install over an app signed with it. The default location is `~/.eq-android/release.jks`.

3. Check `keystore.properties` in the repository root (git-ignored; step 1 wrote it). It needs these fields and nothing else is required:

```
storeFile=<absolute path to the keystore>
keyAlias=<the alias>
storePassword=<add this line yourself in an editor>
```

   The script writes `storeFile` and `keyAlias` for you and leaves `storePassword` for you to add. If you would rather not put the password in a file, export it for the next command instead:

```bash
export EQ_KEYSTORE_PASSWORD
```

4. Build, sign and verify the release APK:

```bash
bash scripts/release-apk.sh
```

5. Read the certificate line the script prints (`Signer #1 certificate SHA-256 digest`). It must not mention `EQ TEST ONLY`. Write the fingerprint down: people who sideload the app can compare it, and it must be identical on every future release. The APK is at `build/release/EQ-<version>.apk`.

6. Install it on a real phone and check these things first. None of them has ever been checked on a physical phone; every test so far ran on an Android 17 arm64 emulator.

```bash
adb install -r build/release/EQ-<version>.apk
```

   - Play audio from a real player (music or a video app). Raise a low band by a lot and confirm you hear the change. Turn the EQ off with the bypass switch and confirm it goes back.
   - If the app is not picked up automatically, turn on global mode in Settings and confirm the audio is equalized. Global mode may not work on every phone.
   - Play a video with sound and see whether the roughly 85 ms of added latency is noticeable against the picture.
   - Restart the phone, then check the app and the notification come back as you set them in Settings.
   - Open Settings and confirm it reads the current version.

7. Encode the keystore for the secret (macOS), copying it to the clipboard:

```bash
base64 -i ~/.eq-android/release.jks | pbcopy
```

8. Run a secrets scan before you push. The private key check must print nothing:

```bash
git grep -nEi 'BEGIN [A-Z ]*PRIVATE KEY' -- . ':!.planning'
```

   Then look at what the password search finds. Matches are expected where the property name `storePassword` appears (`app/build.gradle.kts`, `scripts/`, planning notes), including the fixed non-secret test key password used only for the throwaway test keystore. A real password is not expected anywhere:

```bash
git grep -nEi 'storePassword'
```

9. Add the three secrets. Each command prompts for the value (paste from the clipboard for the first):

```bash
gh secret set EQ_KEYSTORE_BASE64
```

```bash
gh secret set EQ_KEYSTORE_PASSWORD
```

```bash
gh secret set EQ_KEY_ALIAS
```

10. Publish from the local `public` branch, not `main`. It tracks `github/main` and leaves out `.planning/`, `.claude/` and `docs/research/`, so pushing `main` would publish all of those. Bring the new commits onto `public` without those paths (the gsd `pr-branch` skill filters them), then:

```bash
git push github public:main
```

11. Tag the release. The tag must equal `v` plus the `versionName` in `app/build.gradle.kts`; the workflow fails otherwise.

```bash
git tag v<version>
```

```bash
git push github v<version>
```

12. Watch the workflow run in the Actions tab. If it fails, fix the cause and delete and re-push the tag; remember it has never completed a run. When it succeeds it leaves a draft release with `EQ-<version>.apk`. Download that APK, run `apksigner verify --print-certs` on it (`apksigner` is in the Android SDK under `build-tools/36.0.0/`) and compare the fingerprint with step 5, then publish the draft.

## Other notes

- A release signed with the private key will not install over 0.2.0, which used the test key. People have to uninstall first, which deletes their settings and presets, so say so in the release notes.

- `core/tests/golden/macos_response.json` (about 2 MB) is needed by the Rust tests and stays in the repository.
- `spike-tools/` was not renamed on purpose. About 75 files and the project history refer to it by that name.
- The APK is not minified (about 25 MB). See `docs/MINIFICATION.md` for why and what it would take.
