<div align="center">

![Nepenthe Journal](docs/logo.png)

# Nepenthe Journal

Offline-first journal for tracking psychoactive substance sessions, monitoring tolerance, and browsing a DoseWiki-powered substance reference. Built with Compose Multiplatform.

No cloud, no accounts, no surveillance. Data lives on your device. Optional P2P sync between your own devices over LAN.

[![License: GPL v3](https://img.shields.io/github/license/Wolren/NepentheJournal)](LICENSE)
[![Last commit](https://img.shields.io/github/last-commit/Wolren/NepentheJournal)](https://github.com/Wolren/NepentheJournal/commits)
[![Issues](https://img.shields.io/github/issues/Wolren/NepentheJournal)](https://github.com/Wolren/NepentheJournal/issues)
[![Code size](https://img.shields.io/github/languages/code-size/Wolren/NepentheJournal)]()
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](gradle/libs.versions.toml)
[![Desktop](https://img.shields.io/badge/Target-Desktop-6DB33F?logo=openjdk&logoColor=white)]()
[![Android](https://img.shields.io/badge/Target-Android-3DDC84?logo=android&logoColor=white)]()
[![CI](https://img.shields.io/badge/CI-GitHub%20Actions-2088FF?logo=githubactions&logoColor=white)](.github/workflows)
[![Ko-fi](https://img.shields.io/badge/Ko--fi-Support%20Wolren-FF5E5B?logo=ko-fi&logoColor=white)](https://ko-fi.com/wolren)

</div>

---

## Problem

Existing substance tracking tools require accounts, upload data to servers, or lack structured session logging with tolerance tracking. Nepenthe Journal is a private, offline-first alternative: your data never leaves your devices. P2P sync connects your own devices directly over LAN with no cloud relay.

---

## Features

- [x] **DoseWiki reference catalog:** 577 substances with dosage by route, duration stages, interaction charts, pharmacology, and subjective-effect profiles, bundled offline as CC0 open data
- [x] **One merged offline catalog:** DoseWiki records plus a PsychonautWiki-derived seed (325 substances) are reconciled into a single catalog at startup. No reference API is ever called at runtime
- [x] Session tracking with substances, doses, ROAs, check-ins, and timeline events
- [x] Tolerance dashboard per substance based on last ingestion time and frequency
- [x] Activity heatmap showing session frequency over time
- [x] Dose duration curves (intensity-over-time bezier) for every substance route, built from DoseWiki duration profiles
- [x] Full-text search across sessions, substances, doses, notes, and effects
- [x] P2P sync between desktop and phone over LAN (no cloud relay)
- [x] Custom theme editor with hex color pickers, card styles, background images, and opacity
- [x] Harm reduction guidance plus curated external resources (Safer Use tab)
- [x] Trip-report export in the [DoseWiki trip-report format](https://josiekins.xyz/html-craft/dosewiki-trip-report-format.html)
- [x] Import/export (JSON, CSV) and auto-backup rotation
- [x] Offline-first: journaling, bundled reference data, and P2P sync work without internet (the FDA drug-label card needs network, see below)

---

## Reference data

Everything the app knows about substances ships with the app. The only network call at runtime is the FDA drug-label card, which queries api.fda.gov on demand.

### DoseWiki (primary)

DoseWiki ([dose.wiki](https://dose.wiki/)) is the primary reference source: 577 substances, each with:

- **Dosage:** dose ranges per route of administration
- **Duration:** onset, peak, after-effects, and total per route
- **Subjective effects:** notes, sensory, physical, and cognitive facets with attribution
- **Interactions:** with reasons (interaction risk data retains TripSit's non-commercial attribution)
- **Pharmacology:** pharmacodynamics, pharmacokinetics, and metabolites
- Plus summary, identification, classification, harm potential, legality, tolerance, and citations

The substance prose is released under CC0 1.0 (see [DoseWiki's license](https://dose.wiki/docs/license)). The catalog feeds the substance browser, the dose-duration curves, the session effect timeline, and trip-report export.

### PsychonautWiki-derived seed

Alongside DoseWiki, a 325-substance seed pre-processed from public sources adds substance classes, interactions, and identifiers. The two are merged into one catalog at startup. Sources:

- **PsychonautWiki:** substance classes, effects, interactions, dose ranges
- **PubChem:** CIDs, molecular properties, synonyms
- **TripSit:** combination interactions and risk assessments
- **ChEMBL** and **IUPHAR/BPS Guide to Pharmacology:** binding and bioactivity data
- **PDSP Ki Database** and **BindingDB:** binding affinity measurements
- **Wikidata:** DrugBank, ChEBI, UNII, and ATC identifiers

---

## Using the app

- **Dashboard:** greeting, stats cards, activity heatmap, sessions trend chart, tolerance overview
- **Sessions:** session list with tag filters, favorites toggle, search, calendar access
- **Substances:** DoseWiki-powered catalog with search, category chips, dosage and duration detail, duration curves, pharmacology data, custom substance creation
- **Safer Use:** harm reduction guidance and curated external resources (DoseWiki, PsychonautWiki, Erowid, TripSit, DanceSafe, RollSafe)
- **Settings:** theme editor, sync controls, data import/export, backup management, substance library

Editors, detail views, and the calendar open as full-screen overlays over the current tab.

---

## Sync between your devices

P2P sync copies your journal between your own devices on the same local network:

- The desktop app hosts and advertises itself; other devices connect to it directly. No cloud relay, no server you do not control.
- Changes move in both directions, deletes propagate, and conflicting edits to the same note surface as a pending conflict instead of silently overwriting.
- Requests are authenticated (HMAC-SHA256), so other devices on the network cannot write into your journal.
- Every control lives in Settings, under the "Device Sync" section.

---

## Themes

- Six presets to start from: Forest, Ocean, Sunset, Ember, Midnight, Mono
- Custom colors with hex pickers, card styles, background images, and opacity
- Text contrast adjusts automatically so labels stay readable on any background
- Your theme is stored with the rest of your journal and comes back on the next launch

---

## Cross-platform

Desktop (the primary target), Android, and iOS share one codebase, with platform-specific handling for file I/O, clipboard, back navigation, and windows. See [docs/CROSS_PLATFORM.md](docs/CROSS_PLATFORM.md) for the full guide.

---

## Tech stack

| Layer | Choice |
|-------|--------|
| UI Framework | Compose Multiplatform (JetBrains) 1.12.1 |
| Language | Kotlin 2.4.20 |
| Build System | Gradle 9.7.1 + AGP 9.3.1 |
| Reference Data | DoseWiki (CC0, 577 substances) + PsychonautWiki-derived seed (325 substances) |
| Persistence | JSON file via kotlinx.serialization, atomic writes + backup rotation |
| Networking | Ktor 3.6.0 (client + server) |
| Charts | Vico 3.3.1 |
| Image Loading | Coil 3.6.3 |
| Service Discovery | JmDNS 3.6.3 |
| Settings | multiplatform-settings 1.3.0 |
| Thread Safety | PlatformLock (expect/actual: synchronized on JVM, NSLock on iOS) |
| Test | kotlin.test (69 test files, 668 test methods) |

## CI/CD

| Workflow | Trigger | Purpose | Status |
|----------|---------|---------|--------|
| CI | Push/PR to master | Compile Desktop + Android, run tests, build APKs | Desktop + Android |
| CI (iOS) | Push/PR to master | Build the iOS frameworks, run tests on a simulator, build the Xcode project | macOS |
| CodeQL | Push/PR + weekly (Mon) | Security analysis for Java only | Pass |
| Dependabot | Weekly | Auto-update Gradle + GitHub Actions dependencies | Pass |

> iOS runs its test suite in CI: both framework builds (simulator and device) pass, and `iosSimulatorArm64Test` runs the shared test classes on the simulator, currently **446 tests, 0 failures**.

---

## Limitations

- **Android is the only target without runtime coverage.** Desktop runs its full suite in CI and iOS runs the shared suite on a simulator (446 tests, green as of run 37292403525), but Android has no instrumentation tests and no emulator configured, so its unit tests run on the JVM only. Android also requires manual side-loading. The two end-to-end suites (first launch and sync fidelity) run on desktop only.
- **LAN-only sync.** P2P sync works between devices on the same local network. No remote relay or cloud tunnel.
- **Single-user.** The app has no multi-account or profile system. All data belongs to one user per install.
- **No encryption at rest.** Journal data is stored as a JSON file on disk. No built-in encryption layer.
- **Reference data is bundled.** DoseWiki and the seed are pre-processed at build time, not live-fetched. Updates ship with releases.
- **Compose Multiplatform still has platform-specific quirks.** Desktop and Android share most code, but edge cases (file pickers, clipboard, window management, scrollbars) require platform-specific implementations.

---

## Contributing

Contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

---

## Privacy

Nepenthe Journal does not collect, transmit, or sell any personal information. No accounts, no analytics, no telemetry, no servers. Read the full [privacy policy](https://wolren.github.io/NepentheJournal/privacy.html).

---

## License

Code: GNU General Public License v3.0. See [LICENSE](LICENSE).

Bundled reference data has its own terms (see [NOTICE](NOTICE)): DoseWiki substance prose is [CC0 1.0](https://dose.wiki/docs/license); interaction risk data derived from TripSit retains TripSit's non-commercial attribution; PsychonautWiki-derived seed data is sourced from the public Semantic MediaWiki API and used under fair use / public data principles, with attribution below.

## Acknowledgments

- **DoseWiki** ([dose.wiki](https://dose.wiki/)): the primary reference source: dosage, duration, interactions, pharmacology, and subjective effects for 577 substances, released as CC0 open data.
- **PsychonautWiki Journal** by Isaak Hanimann ([GitHub](https://github.com/isaakhanimann/psychonautwiki-journal-android)): this project's feature reference and conceptual predecessor, licensed under GPL-3.0-or-later. Nepenthe Journal is a derivative work ported to Compose Multiplatform with a redesigned architecture and new features.
- **PsychonautWiki** ([psychonautwiki.org](https://psychonautwiki.org)): public substance reference data accessed via their Semantic MediaWiki API and bundled as seed data.
- **TripSit** ([tripsit.me](https://tripsit.me)): combination interaction and risk-assessment data.
- **IUPHAR/BPS Guide to Pharmacology**: ligand-target interaction data.
- **PDSP Ki Database** (NIMH Psychoactive Drug Screening Program): Ki binding data.
- **BindingDB**: public affinity measurements.