# Dependency verification

## Decision

Gradle dependency verification runs in **lenient** mode. The checksum file is
`gradle/verification-metadata.xml`; `gradle.properties` sets
`org.gradle.dependency.verification=lenient`.

The file records SHA-256 checksums for every artifact resolved by the desktop test
classpath, including plugins and AndroidX modules that resolution touched. Gradle
re-checks every listed artifact on every build.

Lenient means an artifact that is missing from the file logs a warning and the build
continues. Strict would fail instead.

## Why lenient

- The file is generated on Windows. Linux and macOS CI runs resolve platform artifacts
  (Skiko natives, packaging tools) that this host cannot resolve, so a strict file would
  reject them and break CI.
- The iOS target is not built on this host. Its Kotlin/Native artifacts cannot be listed
  here.
- Dependabot version bumps introduce artifacts without entries. Strict would close every
  bump PR until someone regenerates the file; lenient keeps the build green and leaves the
  warning in the log.

Lenient still detects a changed artifact for anything already listed, which covers the
dependencies this workstation actually compiles and runs.

## Regenerate

After a deliberate dependency change, run:

```powershell
.\gradlew.bat --write-verification-metadata sha256 :composeApp:desktopTest
```

Review the diff in `gradle/verification-metadata.xml` and commit it with the dependency
change. On Linux or macOS, run the same command for that platform's tasks and merge the
new components into the file. Do not remove entries for platforms you cannot resolve.

## Dependabot

A version bump that changes checksums will log verification warnings. The PR is still
buildable. Before merging, run the regeneration command on a machine that can resolve the
new artifacts, and commit the metadata file in the same PR.

## Tighten to strict

Change the property to `org.gradle.dependency.verification=strict` once:

1. The checksum file covers every artifact resolved by every CI job.
2. The Dependabot workflow regenerates and commits the metadata file.

Until then, strict trades a real CI outage for partial coverage.
