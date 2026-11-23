# Changelog

All notable changes to Nepenthe Journal are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
Nepenthe Journal is free software under GPL-3.0-or-later.

## [Unreleased]

## [0.3.0] - 2026-11-23

The first stable release. Every packaged build now carries the reference
catalog, a freeze can name itself in the log, and the Settings card fits a
phone.

### Added

- A main-thread stall recorder: a task that blocks the UI thread for 100 ms
  logs its sampled stack into the rolling log, so a freeze reported as "lag"
  names the function that caused it.
- Crash dumps and log bundles name the app version, device and Android build.
  Releases publish their R8 mapping so an obfuscated stack can be retraced.
- A test pins the bundled catalog's `user_version` to the generated schema, and
  a Gradle check fails on a missing or drifted copy of any bundled resource.

### Changed

- CI and the release workflow build the substance catalog in every packaging
  job. The release APK job fails when the built APK lacks the catalog.
- The catalog builder refuses to write a library below the 1675-substance
  target, so an upstream data regression cannot ship as an update.
- Settings reads the theme presets and the trusted-device list off the UI
  thread, and the medications list stops re-sorting on every recomposition.

### Fixed

- Packaged builds carried no reference catalog and silently fell back to 613
  substances. The APK and the MSI, DEB and DMG images now ship all 1675.
- A catalog copy interrupted by a kill left a short file that every later
  launch trusted, pinning the app to the JSON-seed fallback. A short copy is
  now detected and replaced, and old copies are pruned.
- The Data card showed two buttons labelled "Import" on a narrow screen, and
  content overran the button so it stopped being centered. The rows now read
  Export / Import, Backup / Restore and Seal backup / Restore sealed, with
  labels that fit.
- iOS framework and screenshot workflows built without the catalog, so they
  exercised the 613-substance fallback.

## [0.2.0-alpha] - 2026-11-22

The first hardening release after the public alpha. Sync, storage and imports
got stricter, and the journal now survives crashes, planted key files and
malformed backups.

### Added

- Hybrid logical clocks on every synced entity, with per-field clock registers
  for sessions, doses and notes, so a pull merges field by field.
- A pinned TLS connector for LAN sync on desktop, Android and iOS.
- DPAPI-wrapped journal keys on Windows; the POSIX key file stays 0600.
- Atomic journal saves on every platform, covered by a repeated-kill fuzz test.
- Macrobenchmark suites for startup and scrolling, a generated Android baseline
  profile, and R8 with resource shrinking on release builds.
- A TLA+ model of the tombstone join and merge-law property tests.
- Optional GPG signatures for the release checksums.

### Changed

- The library gate opens before the catalog payload decodes, and Android builds
  the catalog after the first frame, so the first frame is not blocked by
  pharmacology data.
- Sync retries sign each attempt fresh; the pull and push cursors are separate.
- Deletions no longer resurrect on pull, equal-timestamp conflicts break by
  device origin, and session conflicts merge field by field.
- Text conflicts keep both siblings instead of merging into one text.
- Vault filenames are capped in UTF-8 bytes, and note matching prefers the id
  and compares names without case.
- Theme writes are coalesced across a slider drag and flushed on exit.
- Imported text files and user settings accept a UTF-8 BOM.

### Fixed

- CSV export names a dose whose substance is missing, keeps thousands
  separators and quotes, and formats dates consistently.
- The vault export and import protect hand-written notes, rewrite children in
  place, escape a pipe in an event label, and reject paths outside the vault.
- Full-journal import validates notes and timeline events, caps substances and
  interactions, and refuses orphan rows.
- The DoseWiki trip report warns about doses it cannot name and elides blank
  event labels.
- Tolerance levels follow the clock instead of a stale cache, and the dashboard
  refreshes them at day boundaries.
- Interaction rows become clickable when the substance library loads late.
- The export preview recovers when the library arrives while the dialog is open.
- The search hint appears for a single character, and the live dose dialog
  rejects an unparseable amount and links a typed name to an existing substance.
- Sync bounds peer pull cursors, shares the WebSocket apply guard, caps the sync
  log, and reports a lost peer by device id.
- Light saves stop rewriting the recovery copy, startup no longer rewrites an
  intact library, and a planted key file is quarantined.
- Autosave honours the configured delay, the dashboard empty state waits for
  loading, and the back guard no longer discards a draft.

### Security

- The desktop key file is adopted only when it opens the journal, and unproven
  material is quarantined.
- Vault writes stay inside the vault, imports carry row caps and time windows,
  and sync uses pinned TLS on every platform.
- A single-instance lock keeps two desktop processes off one journal.

## [0.1.0-alpha] - 2026-11-19

First public alpha: desktop on Windows, macOS and Linux, and Android, from one
Kotlin Multiplatform codebase. The iOS app builds and passes its simulator test
suite, but ships through no store channel in this release.

### Added

- Session journaling: title, set and setting, intention, outcome, rating,
  consumer, tags, favourites and archive.
- Doses with route of administration, amount and unit, redosing, stomach
  fullness, estimate flag and per-dose notes.
- Check-ins and timeline events, including harm reduction and observation
  entries.
- Live session mode: run timer with pause and resume, quick dose and mood
  dialogs, crisis resources, and free-form Markdown writing that autosaves while
  the session runs.
- Tolerance tracking per substance from recency and frequency, with a dashboard
  overview and a tolerance view on the session timeline.
- Dashboard with activity heatmap, sessions trend, top substances and tolerance
  cards, each of which can be hidden.
- Session list with substance, favourite and consumer filters, a calendar view,
  and a full timeline per session.
- Full-text search across sessions, substances, doses, notes, effects and
  timeline events, with ranking that puts the named compound above its analogs
  and then prefers well known substances.
- Bundled reference catalog: over 1,600 substances shipped as a read-only SQLite
  database, with dosage bands per route, duration stages, subjective effects,
  interactions, identifiers, pharmacology, and the source that supplied every
  field.
- Effects library with hundreds of bundled articles, category filters, and a
  formatted reader with interlinks.
- Substance detail: dose duration curves, receptor pharmacology with binding
  data, interaction charts, dose equivalence, prodrug half-lives, source
  comparison, row citations and legal status.
- PsychonautWiki-derived seed as the fallback library, merged with the catalog at
  startup, plus curated taxonomy tags and interaction classes.
- Safer Use tab with harm reduction guidance, testing notes, the recovery
  position, and curated external resources.
- Import from PsychonautWiki Journal, PsyLog, DrugsPRO and any CSV or TSV table
  with substance, dose and time columns.
- Export to JSON, CSV, an Obsidian vault and the DoseWiki trip report format.
- LAN sync between your own devices: HTTP bulk exchange, WebSocket continuous
  sync, mDNS discovery, manual IP pairing, ECDH pairing, HMAC-authenticated
  requests, tombstones for deletes, and conflicts surfaced instead of overwritten.
- Storage safety: an encrypted journal at rest, versioned backup rotation,
  timestamped auto-backups on close, and restores from the recovery copy.
- Sealed backups: zstd-compressed, passphrase-protected `.nepbak` files that can
  be stored anywhere and restored on another install.
- `nepenthe://quicklog` links, so a shortcut, a script or another app can log a
  dose without opening the editor.
- Appearance: theme presets and a full editor (colours, card style, corner
  radius, density, font family and scale, text colour, background image with
  opacity, window opacity, animation scale), wide two-pane layouts for sessions
  and settings, a command palette, desktop keybinds, and borderless full screen.
- Preferences: rating scale, time display mode, opening tab, free-form autosave
  delay, dashboard card visibility, delete confirmation, and auto-backup on close.
- A bundled companion that reacts to events, off until it is switched on.

### Changed

- The bundled SQLite catalog is the reference source on every launch; the
  PsychonautWiki seed is the fallback, and iOS reads the JSON seed.
- Settings are grouped into Appearance, Preferences, Data, Journal, Sync and
  About, in a two-pane layout at window widths above the rail breakpoint.
- Sync carries user data only; reference rows never travel between devices.
- The journal and its library live in two files, so a library refresh does not
  rewrite the session data.

### Fixed

- Journal recovery on every platform: a missing main file is recovered from the
  `.bak` or `.tmp` copy, and an unreadable main file falls back to per-field
  salvage instead of starting empty.
- The save path replaces files atomically, rotates backups, cleans up stale temp
  files, and keeps a light save on the sync path so an acknowledgement is not
  followed by tens of megabytes of disk writes.
- Sync protocol limits, validation and cursors are shared between hosts and
  clients, and one device id is kept per install.
- Substance classes are normalised and curated, with the known label collisions
  (antidepressant versus depressant, diazepines, beta-keto phenethylamines,
  fentanyl scaffolds, synthetic cannabinoid indoles) guarded by tests.
- Light theme contrast, selectable text on desktop, timeline curve rendering, and
  a long list of layout overflows found in the UI audits.
- Settings no longer ship the developer action that wiped the journal and filled
  it with synthetic sample sessions. The Diagnostics card keeps the log export.

### Security

- The journal is sealed at rest with AES-256-GCM under a device-held key. No
  accounts, no cloud, no telemetry.
- Sealed backups fail the authentication tag check before any data is touched, so
  a wrong passphrase cannot corrupt an install.
- Sync requests are authenticated with HMAC-SHA256, devices pair over ECDH, and
  every incoming payload passes a validation gate before it is applied.
- The sync, pairing, storage and UI paths were audited before release, with the
  results pinned in `docs/SYNC-PAIRING-AUDIT-2026-07-31.md`,
  `docs/STORE-SYNC-DURABILITY-AUDIT-2026-07-31.md`,
  `docs/UI-AUDIT-2026-07-31.md` and `docs/HARDENING-CONTRACTS-2026-09.md`.

### Performance

- Cold start: AWT and Skiko warm up off the main thread, the bundled seed
  fingerprint is computed at build time, and an AppCDS archive halves class
  loading on the packaged desktop build.
- Tab switches reuse cached data instead of rebuilding screens.
- Substance search keeps a prebuilt lowercase haystack per row; the journal index
  tokenises without a regex and publishes copy-free snapshots; the catalog decode
  runs single threaded because every threaded shape measured slower.

### Build and release

- CI runs the unit suite, the Android unit tests, Android instrumented tests on an
  emulator, desktop launch checks on Linux and macOS, a Windows MSI
  install/launch/uninstall pass, the portable Windows image, iOS framework builds
  and simulator tests, and CodeQL.
- The release workflow builds the MSI, the portable Windows image, the DEB and the
  DMG per architecture, writes `SHA256SUMS.txt`, and creates the GitHub release.
- Store pipelines: fastlane metadata for Android, an F-Droid source-reproducibility
  gate, and a dispatch-only Google Play internal track upload.

[Unreleased]: https://github.com/Wolren/NepentheJournal/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/Wolren/NepentheJournal/releases/tag/v0.3.0
[0.2.0-alpha]: https://github.com/Wolren/NepentheJournal/releases/tag/v0.2.0-alpha
[0.1.0-alpha]: https://github.com/Wolren/NepentheJournal/releases/tag/v0.1.0-alpha
