# Releasing EQ for Android

Nothing has been published. No keystore exists, no GitHub repository exists, no remote is configured and no tag has been created. These are the steps for you to publish.

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

5. Read the certificate line the script prints (`Signer #1 certificate SHA-256 digest`). It must not mention `EQ TEST ONLY`. Write the fingerprint down: people who sideload the app can compare it, and it must be identical on every future release. The APK is at `build/release/EQ-0.1.0.apk`.

6. Install it on a real phone and check these things first. None of them has ever been checked on a physical phone; every test so far ran on an Android 17 arm64 emulator.

```bash
adb install -r build/release/EQ-0.1.0.apk
```

   - Play audio from a real player (music or a video app). Raise a low band by a lot and confirm you hear the change. Turn the EQ off with the bypass switch and confirm it goes back.
   - If the app is not picked up automatically, turn on global mode in Settings and confirm the audio is equalized. Global mode may not work on every phone.
   - Play a video with sound and see whether the roughly 85 ms of added latency is noticeable against the picture.
   - Restart the phone, then check the app and the notification come back as you set them in Settings.
   - Open Settings and confirm it reads Version 0.1.0.

7. Encode the keystore for the secret (macOS), copying it to the clipboard:

```bash
base64 -i ~/.eq-android/release.jks | pbcopy
```

8. Create the GitHub repository yourself and choose its visibility. With the GitHub CLI:

```bash
gh repo create dylenq/eq-android --public --source . --remote origin
```

9. Run a secrets scan before the first push. The private key check must print nothing:

```bash
git grep -nEi 'BEGIN [A-Z ]*PRIVATE KEY' -- . ':!.planning'
```

   Then look at what the password search finds. Matches are expected where the property name `storePassword` appears (`app/build.gradle.kts`, `scripts/`, planning notes), including the fixed non-secret test key password used only for the throwaway test keystore. A real password is not expected anywhere:

```bash
git grep -nEi 'storePassword'
```

10. Add the three secrets. Each command prompts for the value (paste from the clipboard for the first):

```bash
gh secret set EQ_KEYSTORE_BASE64
```

```bash
gh secret set EQ_KEYSTORE_PASSWORD
```

```bash
gh secret set EQ_KEY_ALIAS
```

11. Push the branch:

```bash
git push -u origin main
```

12. Tag the release. The tag must equal `v` plus the `versionName` in `app/build.gradle.kts`; the workflow fails otherwise.

```bash
git tag v0.1.0
```

```bash
git push origin v0.1.0
```

13. Watch the workflow run in the Actions tab. If it fails, fix the cause and delete and re-push the tag; remember it has never completed a run. When it succeeds it leaves a draft release with `EQ-0.1.0.apk`. Download that APK, run `apksigner verify --print-certs` on it (`apksigner` is in the Android SDK under `build-tools/36.0.0/`) and compare the fingerprint with step 5, then publish the draft.

## Decision for you at publish time

Whether to publish `.planning/` and `.claude/CLAUDE.md`. They are the development history: plans, research, evidence and debugging notes, plus the assistant guidance file. Publishing them shows how the app was built and where each claim comes from. Keeping them out gives a cleaner repository. If you want them out, push a branch without them (the gsd `pr-branch` skill filters `.planning` commits) instead of `main` as it is. This was left for you to decide; nothing was removed.

## Other notes

- `core/tests/golden/macos_response.json` (about 2 MB) is needed by the Rust tests and stays in the repository.
- `spike-tools/` was not renamed on purpose. About 75 files and the project history refer to it by that name.
- The APK is not minified (about 25 MB). See `docs/MINIFICATION.md` for why and what it would take.
