# Releasing Nepenthe Journal

## Version surfaces (must stay in sync)

The Release workflow fails its version guard when these drift:

| Surface              | File                                                    |
|----------------------|---------------------------------------------------------|
| `versionName`        | `composeApp/build.gradle.kts` (android block)           |
| `packageVersion`     | `composeApp/build.gradle.kts` (nativeDistributions)     |
| About card string    | `composeApp/src/commonMain/.../detail/SettingsInfoCards.kt` |
| macOS `packageVersion` (intentionally different) | `composeApp/build.gradle.kts` (macOS block) |

## Cutting a release

1. Bump all three surfaces together (same version, one commit).
2. Push to master; CI, CodeQL and fdroid must be green.
3. Optional dry run: Actions -> Release -> Run workflow. Builds MSI, DEB and
   DMG as workflow artifacts and publishes nothing.
4. Tag and push the tag (must equal `packageVersion`):

   ```bash
   git tag v0.1.0
   git push origin v0.1.0
   ```

5. The Release workflow re-checks the version guard, builds all three
   installers, writes `SHA256SUMS.txt` and creates the GitHub Release with
   generated notes.

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
- **Windows upgrade identity**: `upgradeUuid` in `composeApp/build.gradle.kts`
  is pinned (`D07ABD38-858F-464A-ABF6-9405C622BB14`). Never regenerate it:
  changing it breaks in-place upgrades of installed copies.
- **Android**: Play Store uploads go through `play.yml` (needs repository
  secrets); F-Droid builds from source (`fdroid.yml` reproducibility gate).
  The GitHub Ubuntu image ships a licensed Android SDK, so neither workflow
  uses `android-actions/setup-android` (its default package list fails on
  the removed sdkmanager `tools` package).
