# F-Droid Publishing Guide

Nepenthe Journal targets F-Droid as the primary Android distribution channel.
F-Droid builds the app itself from source in isolated containers: it never
ships developer-provided binaries. This repo must therefore be buildable and
**reproducible** from source alone.

Status: pipeline ready. Not yet submitted to fdroiddata.

## Why F-Droid first

The Google Play review policy contains an "Unapproved Substances" clause that
is a real risk for any app touching this domain (see
`docs/app-store-compliance-research.md` if present; the analysis concluded
Play approval depends on the "journal with harm reduction context" framing).
F-Droid has no such restriction and is the safest channel for this app's
purpose. The store listing metadata (fastlane) is still provided so a Play
submission remains possible later.

## Reproducibility check (CI)

`.github/workflows/fdroid.yml` builds `:composeApp:assembleRelease` twice in
two independent clean containers and compares SHA-256 hashes of the APKs.
This is a smoke check: F-Droid's own build server is the authoritative gate.

Run the check locally (needs a full Android SDK):

```bash
./gradlew :composeApp:assembleRelease
sha256sum composeApp/build/outputs/apk/release/*.apk
# Build again from a clean checkout and compare.
```

## Requirements checklist (current state)

| Requirement | State |
|-------------|-------|
| Gradle wrapper committed | yes (`gradlew`, `gradlew.bat`, `gradle/wrapper/`) |
| No `local.properties` in repo | yes (gitignored) |
| No machine-specific paths in build files | yes |
| JDK: F-Droid uses JDK 17 | yes (compileOptions VERSION_17) |
| AGP/SDK resolved from `ANDROID_HOME` | yes (no sdk.dir needed) |
| Version code / name in `defaultConfig` | yes (`versionCode 1`, `versionName 0.1.0`) |
| Release build reproducible | verified by CI workflow |
| License metadata | GPL-3.0, LICENSE file at repo root |
| Anti-features | none (no ads, no tracking, no non-free deps) |

## Submitting to fdroiddata

When ready to publish:

1. Create a GitHub release tagged `v<versionName>` (e.g. `v0.1.0`). F-Droid
   tracks release tags via the `CurrentVersion` field.
2. Fork `fdroid/fdroiddata` and add a metadata file:
   `metadata/app.journal.nepenthe.yml`:

```yaml
Categories:
  - Science & Education
License: GPL-3.0-or-later
AuthorName: Wolren
AuthorEmail: <your-email>
SourceCode: https://github.com/Wolren/NepentheJournal
IssueTracker: https://github.com/Wolren/NepentheJournal/issues
Changelog: https://github.com/Wolren/NepentheJournal/releases

AutoName: Nepenthe Journal
Description: |-
  Offline-first harm reduction journal for substance sessions. Tracks
  doses, routes, effects, and tolerance; includes a 325+ substance
  library with dosage and pharmacology data, an interaction checker,
  safer use guides, and optional encrypted LAN-only P2P sync. No
  accounts, no cloud, no ads, no telemetry.

Repo: https://github.com/Wolren/NepentheJournal.git
Binaries: https://github.com/Wolren/NepentheJournal/releases/download/v%v/app-journal-nepenthe-%v.apk

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

   Adjust `subdir`, `prebuild`, and `Binaries` to the actual layout.
   The reference for every field: https://f-droid.org/en/docs/Build_Metadata_Reference/

3. Run the local metadata check (optional):

```bash
pip install fdroidserver
fdroid lint metadata/app.journal.nepenthe.yml
```

4. Open the fdroiddata merge request. F-Droid CI will build the app in a
   clean container; the `fdroid.yml` workflow above should have caught
   reproducibility problems already.

## Versioning policy

- `versionCode`: monotonically increasing integer (F-Droid requires it)
- `versionName`: semver, prefixed with `v` in git tags
- Every release: bump both in `composeApp/build.gradle.kts`, tag `v<name>`,
  let the CI reproducibility check pass, then update `CurrentVersion*` in
  the fdroiddata metadata (AutoUpdateMode: Version does this automatically
  for tagged releases).

## Known build quirks on CI

- `android.disableAarMetadataCheck=true` in `gradle.properties` is required
  by the AGP 9.x + Compose Multiplatform combination; it does not affect
  reproducibility.
- `org.gradle.daemon=false` keeps builds deterministic and memory-bounded on
  constrained runners.
- `kotlin.native.ignoreDisabledTargets=true` lets CI hosts without macOS
  toolchains skip iOS tasks; the Android build is unaffected.

## Distribution decision log

- 2026-07-31: F-Droid chosen as primary channel (unapproved-substances risk
  on Google Play, no-restriction policy on F-Droid, GPLv3 compatible).
- Google Play remains possible later via the existing fastlane metadata if
  the "harm reduction journal" framing is deemed acceptable by review.
