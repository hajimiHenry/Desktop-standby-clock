# Release Guide

GitHub releases use semantic-version tags such as `v0.1.0`. The Android
`versionName` in `app/build.gradle` must match the tag without the leading `v`,
and `versionCode` must increase for every published APK.

## Signing key

The release keystore is intentionally stored outside the repository at
`~/.android/standby-clock-release.jks`. Its password is stored in the macOS
Keychain under service `com.henry.standbyclock.release-signing` and account
`standby-clock`.

Back up the keystore and its password securely. Every future update to the app
must be signed with this same key. Neither the keystore nor its credentials may
be committed to Git.

The build also supports CI or non-macOS environments through these variables:

- `STANDBY_CLOCK_KEYSTORE`
- `STANDBY_CLOCK_STORE_PASSWORD`
- `STANDBY_CLOCK_KEY_ALIAS`
- `STANDBY_CLOCK_KEY_PASSWORD`

## Build and verify

Run:

```bash
./scripts/build-release.sh
```

The script runs unit tests, Debug Lint, and the signed Release build. It then
verifies the APK signature and writes the APK plus its SHA-256 checksum to
`dist/`.

## Publish

After reviewing the changes and artifacts:

```bash
git tag -a v0.1.0 -m "Desktop Standby Clock v0.1.0"
git push origin main
git push origin v0.1.0
gh release create v0.1.0 \
  dist/Desktop-Standby-Clock-v0.1.0.apk \
  dist/Desktop-Standby-Clock-v0.1.0.apk.sha256 \
  --title "Desktop Standby Clock v0.1.0" \
  --notes-file docs/releases/v0.1.0.md
```
