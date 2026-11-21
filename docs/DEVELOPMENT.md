# Development

## Build and test

Build with JDK 21. The desktop bundle and bytecode target Java 17. On Windows use `gradlew.bat`; the commands below assume a POSIX shell.

```bash
./gradlew :composeApp:run
./gradlew :composeApp:desktopTest
./gradlew :composeApp:testDebugUnitTest
./gradlew :composeApp:compileDebugKotlinAndroid
bash ./scripts/run-tests.sh
```

The full gate is `scripts/run-tests.sh`; the desktop test task covers the shared and desktop classes. The Android instrumented smoke tests need a running emulator:

```bash
./gradlew :composeApp:connectedDebugAndroidTest
```

The iOS targets need macOS with Xcode 26:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosArm64
./gradlew :composeApp:iosSimulatorArm64Test
```

The `ios-visual` workflow boots a simulator, launches the app, and uploads screenshots and the app log. The same workflow screenshots the macOS desktop build:

```powershell
./scripts/ios-visual.ps1 -Appearance dark
```

It watches the run and writes the PNGs to `%TEMP%\nepenthe-ios-visual`. It does not tap through screens; an XCUITest target is the next step for scripted journeys.

## Tech stack

| Layer | Choice |
|---|---|
| UI | Compose Multiplatform 1.12.1 |
| Language | Kotlin 2.4.20 |
| Build | Gradle 9.8.0, AGP 9.4.1 |
| Reference data | 1,675-substance merged catalog; SQLite via SQLDelight 2.4.1 |
| Persistence | JSON via kotlinx.serialization 1.11.0; AES-256-GCM at rest; atomic writes and backup rotation |
| Networking | Ktor 3.6.0; JmDNS 3.6.3 |
| Charts | Vico 3.3.1 |
| Images | Coil 3.6.3 |
| Settings | multiplatform-settings 1.3.0 |
| Tests | kotlin.test, JUnit, Compose UI tests |

## CI and release workflows

| Workflow | Trigger | Purpose |
|---|---|---|
| CI | Push/PR to master | Desktop compile, shared tests, Android compile and unit tests |
| Android instrumented | Push/PR to master | Emulator smoke tests |
| Android macrobenchmark | Push/PR to master | Baseline profile refresh, scroll and journey benchmarks |
| iOS | Push/PR to master | Both iOS frameworks, simulator tests, Xcode build |
| iOS visual | Manual | Simulator and macOS desktop screenshots |
| CodeQL | Push/PR and weekly | Java security analysis |
| Dependabot | Weekly | Gradle and GitHub Actions updates |

Release, Play, and F-Droid workflows live in `.github/workflows`. Release steps are in [RELEASING.md](RELEASING.md). Benchmarks are documented in [BENCHMARKS.md](BENCHMARKS.md).

Dependency verification and the strict versus lenient choice are in [DEPENDENCY-VERIFICATION.md](DEPENDENCY-VERIFICATION.md).

## Reference catalog build

The app ships a read-only SQLite catalog instead of the two large JSON files at runtime (iOS still reads the JSON seed). Rebuild it with:

```bash
python scripts/build_substance_db.py
```

The script reads the bundled JSON snapshots and the cached source extracts under `scripts/cache/` (TripSit API, drug.community navigation and dose bands, PsychonautWiki SMW dump, Wikidata medicines), merges them with fixed precedence, writes `build/substance-db/substances.sqlite` plus `manifest.json`, and stamps `user_version = 1` for SQLDelight. When `scripts/cache/mneme_affinity.json` is present, its payloads replace the seed pharmacology blocks and attach to every catalog compound the MNEME database matched by PubChem CID or InChIKey. The build is deterministic: two runs produce byte-identical output. Every field records the source that supplied it in `field_source`, which the substance detail screen shows.

## iPhone and macOS from a Windows workstation

Three things are true on Windows:

- **No local iOS compile.** Kotlin/Native disables the iOS targets here: the `securityEx` and `ccGcm` cinterops include Apple SDK headers (`Security/SecKey.h`, `CommonCrypto/CommonCryptor.h`), and cinterop cannot run on a non-Apple host. `sdkman` and WSL do not change this.
- **No local iOS simulator.** Simulators ship with Xcode only.
- **The macOS runner is the iPhone.** The `iOS visual` workflow boots a real simulator, installs the app, captures screenshots while the first launch builds the library, and uploads them.

## Cross-platform

Desktop, Android, and iOS share one codebase, with platform-specific handling for file I/O, clipboard, back navigation, and windows. See [CROSS_PLATFORM.md](CROSS_PLATFORM.md) for the full guide. Sync transport and TLS scope are in [TLS-SYNC.md](TLS-SYNC.md); conflict rules are in [CONFLICT-RESOLUTION.md](CONFLICT-RESOLUTION.md).
