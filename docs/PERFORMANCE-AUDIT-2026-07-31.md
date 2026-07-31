# Nepenthe Journal - Performance Audit

Date: 2026-07-31
Scope: Compose recomposition hotspots, N+1 repo lookups in composition, Canvas text measurement, save-path IO (4.3MB journal), startup cost, concurrency corruption paths
Files audited: all UI screens (Dashboard, SessionList, SessionTimeline, TimelineBar, TimelineComponents, SessionCard, Calendar, ActivityHeatmap, DashboardCharts, SearchOverlay, SubstanceScreen, DoseEditDialog, editor sections), JournalStore (3 actuals), JournalRepository.autoSave, EntityStore, sync persist path

---

## Verdict

The UI layer was already in good shape: the O(N*M) hotspots flagged in the 2026-07-15 audit (TimelineBar dose→substance lookups, SessionList inline repo reads) have been fixed with `remember`/ViewModel flows. No unremembered derivations, no Canvas text measurement per frame on static content, no collectAsState-in-LazyColumn violations. The real costs found were in the persistence/sync path (26MB of file IO per save) and one load-flaky corruption path in EntityStore. All fixed.

## Findings & fixes

### P1. FIXED: Sync persist did a full save per frame: 26MB IO per ack
**Files:** `SyncEngineFactoryDesktop.kt`, `SyncEngineFactoryAndroid.kt`, `JournalStore*.kt`

**Detail:** the persist-before-ack fix from the durability audit made every sync apply (push, pull, WS delta) run a FULL save. With the 4.3MB journal, each save writes the tmp file AND rotates 6 backup copies (~26MB IO). Under continuous sync that is per-frame disk churn.

**Fix:** `JournalStore.save(fullBackup: Boolean = true)`; light saves (sync path) skip the .bak rotation and only update the main file atomically; the debounced autosave (≤2s later) performs the full backup. Durability is preserved (main file holds the data before the ack; only backup-chain freshness lags). Light save cost: one 4.3MB write instead of 26MB. Regression test: `lightSaveSkipsBackupRotation`.

### P2. FIXED: EntityStore HashMap corruption under concurrent writes
**File:** `EntityStore.kt`

**Detail:** EntityStore was documented "not thread-safe" with raw HashMap access. `EntityStoreTest.concurrentPutAndReadIsConsistent` (4 threads × 100 puts) failed intermittently under full-suite load: a HashMap resize race can lose entries or corrupt the bucket structure entirely (observed: 0 keys after 400 writes). The test raced undefined behavior; the production code had the same latent corruption path if any caller bypassed repo.lock.

**Fix:** single operations (put/get/remove/putAll/removeAll/clear/batch/all/size/keys) are now internally locked via the project's `PlatformLock` (reentrant on JVM, NSLock on iOS). Production access is already serialized by repo.lock, so the added lock is uncontended. Compound multi-store operations still require the external repo lock (documented). Lock order: repo → store, no cycle.

### P3. FIXED: Stale .tmp left behind by the direct-write fallback
**File:** `JournalStoreDesktop.kt`, `JournalStoreAndroid.kt`, `JournalStoreIos.kt`

**Detail:** when `tmp.renameTo(target)` fails (Windows: target exists / AV lock) and the direct-write fallback succeeds, the 4.3MB `.tmp` file was never deleted. Observed on disk: `journal-data.json.tmp` (4.3MB) persisted until the next load cleaned it.

**Fix:** delete tmp after a successful fallback write on all three platforms.

### P4. VERIFIED CLEAN: Compose hotspots from prior audits
- TimelineBar: doses→substance name maps, row building, and label pre-measurement all in `remember`; drag-scrub text measure runs only during drag (inherent).
- SessionListScreen/SessionCard: ViewModel flows + per-card `remember(session.id)`; no inline repo scans.
- DashboardScreen: tolerance calc keyed on toleranceVersion (cached), substance pairs and date maps remembered.
- SessionTimelineScreen/CalendarScreen/SubstanceScreen/DoseEditDialog: all derivations remembered or O(1) map lookups.
- `now` in TimelineBar is a plain read, not ticking state; no per-second redraw.
- AnimatedListItem is a passthrough (no animation stutter in lists).

### P5. OBSERVED: startup cost acceptable
Journal load (4.3MB) + seed (325 substances) + DoseWiki (3124 effects) + migration completes in ~2s on this machine (log-verified), before the window shows. Not worth a splash-screen refactor at this data size.

## Light theme fixes (same session, user report)

- **ActivityHeatmap**: inactive cells were hardcoded `0xFF3A3A3A` (black slab on light cards). Now theme-aware: light mode uses `0xFFE4E4E4` inactive + a lighter green ramp; today outline switches to dark green on light.
- **Tolerance MED badge**: bright amber `0xFFFF9800` (~1.9:1 on white) replaced with deep amber `0xFF9A6700` (~4.6:1) in light mode.
- ThemeManager's colorScheme derivation was already contrast-correct (WCAG luminance-based on-colors).

## Theme presets (same session, user request)

`ThemePresets` adds 6 curated pairs (dark + light variant each): Forest, Ocean, Sunset, Ember, Midnight, Mono. Preset cards in Settings → Theme (swatch strips of primary/secondary/tertiary/bg/surface, active border + check). Clicking applies the variant matching the current dark/light mode and keeps the base theme choice.

## Severity table

| Finding | Severity | Status |
|---------|----------|--------|
| P1 sync full-save per frame | HIGH (IO) | FIXED |
| P2 EntityStore corruption | MEDIUM (data) | FIXED |
| P3 stale tmp file | LOW | FIXED |
| P4 prior hotspots | - | verified clean |
| P5 startup | LOW | acceptable |
