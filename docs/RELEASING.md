# Releasing Nepenthe Journal

## Version surfaces (must stay in sync)

The Release workflow fails its version guard when these drift:

| Surface              | File                                                    |
|----------------------|---------------------------------------------------------|
| `versionName`        | `composeApp/build.gradle.kts` (android block), may carry an `-alpha` suffix |
| `packageVersion`     | `composeApp/build.gradle.kts` (nativeDistributions), numeric only |
| About card string    | `composeApp/src/commonMain/.../detail/SettingsInfoCards.kt` |
| macOS `packageVersion` (intentionally different) | `composeApp/build.gradle.kts` (macOS block) |

## Cutting a release

1. Bump all three surfaces together (same version, one commit).
2. Push to master; CI, CodeQL and fdroid must be green.
3. Optional dry run: Actions -> Release -> Run workflow. Builds MSI, DEB and
   DMG as workflow artifacts and publishes nothing.
4. Tag and push the tag. It must equal `v<packageVersion>`, optionally with the
   `-alpha` suffix while the project is in alpha; an `-alpha` tag publishes as a
   GitHub prerelease:

   ```bash
   git tag v0.1.0-alpha
   git push origin v0.1.0-alpha
   ```

5. The Release workflow re-checks the version guard, builds all three
   installers, writes `SHA256SUMS.txt` and creates the GitHub Release with
   generated notes.

## Signing the release checksums

The installers are unsigned until a Windows and a macOS code-signing certificate
exist. Until then the checksums carry the tamper evidence. The Release workflow
signs `SHA256SUMS.txt` with a GPG key when the `RELEASE_SIGNING_KEY` secret is
present, and publishes `SHA256SUMS.txt.asc` next to it.

One time, on a trusted machine:

```bash
gpg --quick-generate-key "Nepenthe Journal releases <you@example>"
gpg --armor --export <fingerprint> > release-key.asc
gpg --armor --export-secret-keys <fingerprint> > release-key.secret.asc
```

Add the secret key as the `RELEASE_SIGNING_KEY` repository secret, its passphrase
as `RELEASE_SIGNING_PASSPHRASE`, and commit the public `release-key.asc` so
downloaders can import it from the repository.

A download verifies with:

```bash
gpg --import release-key.asc
gpg --verify SHA256SUMS.txt.asc SHA256SUMS.txt
sha256sum -c SHA256SUMS.txt
```

## Android baseline profile

The generated profile in `composeApp/src/androidRelease/generated/baselineProfiles`
ships inside the release APK (`assets/dexopt/baseline.prof`). Regenerate it when the
startup path changes, with a connected device or emulator:

```bash
./gradlew :composeApp:generateReleaseBaselineProfile -Pnepenthe.emulatorAbi=true
```

Commit the regenerated `baseline-prof.txt` and `startup-prof.txt`. The `emulatorAbi`
property adds x86_64 to the release ABI list so the generator can install on a
workstation emulator; shipped builds stay arm-only.

## Build notes

- **Windows MSI**: jpackage comes from the Gradle JVM (JDK 17, corretto on
  CI). The Compose Gradle plugin auto-downloads WiX 3.11 into
  `build/wix311` (`downloadWix`/`unzipWix`); no system WiX install needed.
- **Linux DEB**: the jpackage deb bundler needs `fakeroot` (the workflow
  installs it when missing).
- **macOS DMG**: built on `macos-15` (Apple Silicon), bundled runtime is
  arm64. Artifacts are unsigned; right-click Open on first launch. The
  macOS block sets its own `packageVersion = "1.0.0"`: jpackage rejects a
  leading zero for the app bundle (Apple CFBundleShortVersionString rule),
  so the Apple bundlers see 1.0.0 while everything else stays 0.1.0. Bump
  this value together with the other surfaces on a version change (any
  nonzero-first version).
- **Windows signing on alpha tags**: a stable tag refuses to publish an
  unsigned MSI. An `-alpha` tag builds and publishes unsigned when no
  certificate secret is configured, and the release title carries
  `(unsigned)` so the artifact state is not a surprise.
- **Windows upgrade identity**: `upgradeUuid` in `composeApp/build.gradle.kts`
  is pinned (`D07ABD38-858F-464A-ABF6-9405C622BB14`). Never regenerate it:
  changing it breaks in-place upgrades of installed copies.
- **Android**: Play Store uploads go through `play.yml` (needs repository
  secrets); F-Droid builds from source (`fdroid.yml` reproducibility gate).
  The GitHub Ubuntu image ships a licensed Android SDK, so neither workflow
  uses `android-actions/setup-android` (its default package list fails on
  the removed sdkmanager `tools` package).
