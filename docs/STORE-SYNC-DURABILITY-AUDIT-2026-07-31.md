# Nepenthe Journal — Store Robustness & Sync Durability Audit

Date: 2026-07-31
Scope: crash safety of the JSON journal store (desktop/android/iOS actuals), backup/restore paths, and durability of the sync pipeline (persist-before-ack, crash windows, cursor semantics)
Files audited: JournalStoreDesktop.kt, JournalStoreAndroid.kt, JournalStoreIos.kt, JournalStore.kt (expect + AppJson), EntityStore.kt, JournalRepository.kt (autoSave, fullSnapshot), DataInitializer.kt, App.kt (close path), Main.kt (shutdown), KtorSyncServerJvm.kt, KtorSyncClient.kt, SyncTransportJvm.kt, SyncEngineFactoryDesktop/Android.kt

---

## Verdict

The store has good bones (atomic tmp+rename, versioned backups, per-list recovery, size guards, the PlatformLock added earlier today) but had two real data-loss holes and one durability gap in the sync layer. All three are fixed in this pass; the store cannot crash the app on any input path, and sync data is now persisted before it is acknowledged.

Findings: 2 HIGH (both fixed), 2 MEDIUM (fixed), 3 LOW (1 fixed, 2 documented). No CRITICAL.

---

## HIGH

### S1. save() snapshots the repository without the lock — torn multi-store snapshots

**Files:** `JournalStoreDesktop.kt:113`, `JournalStoreAndroid.kt:104`, `JournalStoreIos.kt:189` (pre-fix)

**Detail:** all three `save()` implementations called `AppJson.snapshot(repo)`, which reads the eight EntityStore StateFlows **sequentially without the repository lock**. Each per-store `.value` read is atomic, but the snapshot as a whole is not: a mutation landing between two store reads persists a logically inconsistent state — a session without its doses, a dose whose session vanished, an interaction referencing a missing substance. On reload, orphaned entities appear and, worse, entities that were committed to memory before the crash can be permanently missing from the file even though a "successful" save ran.

**Fix:** `save()` now uses `repo.fullSnapshot()` (which wraps the snapshot in `lock.withLock`) in all three actuals. Seed loading, restore, and settings-triggered saves go through `store.save()` and inherit the fix.

### S2. Clean close loses up to 2 seconds of edits — and backs up the stale file

**Files:** `App.kt:112-116`, `Main.kt` (pre-fix)

**Detail:** window close (`onCloseRequest = ::exitApplication`) disposed the root composable, whose `onDispose` called only `triggerAutoBackup()` — a copy of the **existing** journal file. Autosave is debounced 2s (`JournalRepository.autoSave`), so closing within 2 seconds of an edit silently dropped that edit: the main file was stale, the backup was a copy of the stale file, and the 5 versioned `.bak.N` backups were also stale. Ctrl+C / taskkill had the same problem with no hook at all.

**Fix:** `onDispose` now runs `journalStore.save()` **then** `triggerAutoBackup()` (save the fresh state, then back it up). `Main.kt` registers a JVM shutdown hook that does a best-effort `JournalStore(repo).save()` for non-window exits (Ctrl+C, taskkill, kill). Residual window: hard power loss or a hard kill that bypasses shutdown hooks — covered by the debounced autosave (≤2s) plus the startup recovery dialog and `.bak` chain.

---

## MEDIUM

### S3. Backup and restore paths raced with save()

**Files:** `JournalStoreDesktop.kt` (triggerAutoBackup/restoreFromBackup), `JournalStoreIos.kt` (same) — pre-fix

**Detail:** after today's `saveLock` was added, `triggerAutoBackup()` and `restoreFromBackup()` still ran unlocked. A backup copying the target file while a concurrent save was mid-direct-write (Windows fallback path) produced a torn `.auto/` or `.bak` copy; restore's `copyTo` could interleave with a writer the same way.

**Fix:** both methods wrapped in `saveLock.withLock` on desktop and iOS (Android restore is a documented no-op).

### S4. Sync applied data was never persisted before acknowledgment

**Files:** `KtorSyncServerJvm.kt` (push route, WS delta route), `KtorSyncClient.kt` (applyPull), `SyncTransportJvm.kt` (applyWsDelta) — pre-fix

**Detail:** the entire sync layer had **zero** persistence calls. A push or WS delta was applied to memory and acknowledged; the data sat in memory until the next debounced autosave tick (up to 2s). A crash in that window lost the data **permanently**: the client advances its sync cursor on a successful response, so the data would never be re-sent. Same for pulled data on the client — the pull cursor moved past it.

**Fix:** `persistAfterApply: (() -> Unit)?` threaded through `KtorSyncServer` → `SyncServerRouter` → `KtorSyncClient` → `SyncTransport`. Called:
- server: after `handlePush` (before the response), after WS delta apply (before `WsAck`)
- client: after `applyPull`, after WS delta apply

Production wiring (`SyncEngineFactoryDesktop` / `SyncEngineFactoryAndroid`): `{ JournalStore(repo).save() }`. Tests construct the transport directly without the callback, so they never write to the real user-home data path. Regression test added: `push persists applied data before responding` asserts the callback fires on every accepted push.

---

## LOW

### S5. Fixed: load() deletes an orphaned `.tmp` unconditionally
Correct behavior for a single-process app (the tmp file can only be a leftover from a crashed save). Locked since today's saveLock change, so it cannot delete an in-flight save's tmp file.

### S6. Documented: Android `restoreFromBackup()` is a no-op
Returns false with a log line. The `.bak` chain still exists on Android; the recovery dialog's Restore button silently does nothing there. Acceptable for now (mobile OSes have their own backup), but should be implemented eventually.

### S7. Documented: sync timestamps still unbounded in server validators
`validateSyncBatch` has no `updatedAt`/`createdAt` sanity bounds (client-side WS filter does). A trusted-but-buggy peer could push `updatedAt = year 9999`, stalling `since`-based syncs for that entity. Carry-over from the pairing audit (L7); low risk.

---

## Verified strengths (no action needed)

| Control | Evidence |
|---------|----------|
| Atomic write | tmp + rename with direct-write fallback (Windows rename-over-existing fails) |
| Crash recovery | orphan tmp cleaned on load; per-list `recoverSnapshot` (JsonElement-based, no string splitting); `lastLoadHadIssues` + startup recovery dialog |
| Backup depth | `.bak` immediate + `.bak.1..5` versioned rotation + `.auto/` timestamped rotation (10) |
| Save retry | 3 attempts, 100ms backoff on IOException, logged |
| Size guards | 50MB refuse-to-load / refuse-to-save on all three platforms |
| Concurrency | PlatformLock per instance around save/load/backup/restore (all platforms) |
| LWW sync apply | `applyBatch(lastWriterWins=true)` on every sync path (from the pairing audit) |
| Save isolation | snapshot now locked via `fullSnapshot()`; per-store `.value` reads atomic; EntityStore mutations all under repo lock |
| Validation gates | `validateSyncBatch` / `validateWsDelta` before any apply; field caps; deviceId match |
| Autosave | mutation-count debounce 2s, exceptions caught+logged, save retried on next mutation |
| Crash logging | uncaught-exception handler writes standalone crash dumps |

## Severity table

| Severity | Count | Status |
|----------|-------|--------|
| CRITICAL | 0 | — |
| HIGH | 2 | both FIXED (S1, S2) |
| MEDIUM | 2 | both FIXED (S3, S4) |
| LOW | 3 | 1 fixed (S5), 2 documented (S6, S7) |

## Addendum: WS protocol discriminator bug (found during test hardening, same day)

**S8. FIXED — every WS frame failed decode on the receiving end; the channel was silently dead.**

The server encoded all outbound WS frames concretely (`wsJson.encodeToString(WsAck(...))` — no `#type` discriminator), while both peers decode inbound frames polymorphically (`decodeFromString<WsMessage>`). Every ack, pong, and real client delta failed decode on arrival and was swallowed by blanket `catch { skip }` handlers. Consequences: deltas never applied on the server, heartbeat pings never received pongs (the 10s pong-timeout reconnect logic could only ever see `-1L`), ack/seq tracking dead. The serialization tests passed because they used `WsMessage.serializer()` — the production encode sites did not.

Fix: all seven encode sites (4 server, 3 client) now serialize with `WsMessage.serializer()`. Blanket catch-and-skip in the client loop now logs the decode failure via appendDebug instead of hiding protocol drift. Regression coverage: `ws delta applies data and persists before ack` integration test exercises the real wire format end-to-end (pair → WS handshake with signed "ws" body → delta → ack → repo state → persist-before-ack).

**Test hardening added this pass:** orphan tmp cleanup on load, truncated-JSON recovery + post-recovery writability, restore-with-no-backup returns false, WS delta integration (apply + persist before ack), re-pairing rotates secret and stale secret rejected, rate limit ignores spoofed X-Forwarded-For, pairing-token alphabet/uniqueness across 200 draws, auth-header full format check (timestamp:nonce:signature), exact WS discriminator assertions.
