# Sync Hardening Contracts, September 2026 (Phase 0a)

**Status:** PINNED. Written 2026-09-22 at repo HEAD e4fcbbe plus the Phase 0a commit.
**Authority:** This file is the wire-format and behavior contract for the
Nepenthe Journal hardening pipeline. Later agents (SYNC-JVM, SYNC-IOS, TEST,
UI, data/util owners) implement platform sides AGAINST THIS FILE. If an
implementation disagrees with a pin here, the pin wins until this document is
formally revised.

Line numbers below were verified against the Phase 0a tree. They drift as
files change; the surrounding function names are the stable anchors.

Scope note: Phase 0a modified only commonMain/sync, jvmMain/sync, the two
iosMain sync call sites listed below, commonTest, and this document. It did
not touch ui/, data/, model/, util/, ingest/, build scripts, or CI.

---

## 0. What Phase 0a already implemented (do not re-do)

| Item | Location |
|---|---|
| Shared caps (union, JVM-strictest wins) | `commonMain/sync/SyncLimits.kt` `object SyncLimits` (:17) |
| Shared timestamp policy | `commonMain/sync/SyncLimits.kt` `object EntityTimePolicy` (:55): `MIN_ENTITY_TIMESTAMP = 946684800000L`, `FUTURE_MARGIN_MS = 86_400_000L`, `isReasonableEntityTime(ts, now)` |
| Unified validator (both platform copies deleted) | `commonMain/sync/SyncValidation.kt`: `validateSyncBatch` (:32), `validateWsDelta` (:56), `validateSyncResponse` (:102), per-entity `internal` checks |
| Push chunking + cursor helpers | `commonMain/sync/SyncChunking.kt`: `buildPushSlices` (:47), `allSucceeded` (:122), `advanceCursorIfAllSucceeded` (:130), `SyncChunkLimits` (:35) |
| Client-response guard wired into apply | `commonMain/sync/SyncContract.kt`: `applySyncResponse` (:164) returns `SyncApplyResult` (:148) with skip counts, logs skips under tag `SyncContract` |
| WS delta cursor field | `commonMain/sync/SyncWebSocket.kt` `WsDelta.since` (:40), default 0 for wire compat |
| HostInfo.wsSupported + ECDH key field | `commonMain/sync/SyncPayload.kt` `HostInfo` (:61): `wsSupported` (:73, default false), `ecdhPublicKeyB64` (:80, default null) |
| Pairing ECDH DTO fields | `SyncPayload.kt`: `PairingVerifyRequest.clientEcdhPublicKeyB64` (:100), `PairingResultResponse.ecdhSecretB64` (:141) |
| ECDH shared crypto (KDF + seal helpers) | `commonMain/sync/PairingEcdh.kt` `object PairingEcdh` (:50) |
| URL scheme constant | `SyncContract.kt` `SyncEndpoints.URL_SCHEME = "http"` (:36) |
| JVM host advertises WS support | `jvmMain/sync/KtorSyncServerJvm.kt` `/info` (:159) and `/pairing/start` (:172): `wsSupported = true` |
| iOS host advertises no WS | `iosMain/sync/IosSyncServerRouter.kt` (:52, :65): `wsSupported = false` |
| JVM pull-page caps and since bound read shared constants | `KtorSyncServerJvm.kt` `handlePull` (:842, caps :857-864), since bound (:473) uses `EntityTimePolicy.FUTURE_MARGIN_MS` |
| iOS pull-page caps and since bound read shared constants | `IosSyncServerRouter.kt` (:387-394), `MAX_FUTURE_SINCE_MS` (:448) = `EntityTimePolicy.FUTURE_MARGIN_MS` |
| JVM WS apply-time check delegates to shared policy | `jvmMain/sync/SyncTransportJvm.kt` `isReasonableTimestamp` (:762) |
| Tests | `commonTest/sync/SyncChunkingTest.kt`, `commonTest/sync/SyncResponseGuardTest.kt`, `commonTest/sync/PairingEcdhTest.kt`; `desktopTest/sync/SyncValidatorsTest.kt` unchanged and re-points at the commonMain validator automatically (same package, same function names) |

Deleted in Phase 0a (proofs in section 7): `jvmMain/sync/SyncValidators.kt`,
`iosMain/sync/IosSyncValidators.kt` (the 2-year margin is gone with it),
`commonMain/sync/KtorSyncClient.kt` (comment-only stub), and
`SyncContract.VerifiedPush`.

---

## a. WebSocket delta sequence protocol

**Pin:**

1. The client owns ONE outgoing sequence counter per WebSocket connection.
   The first `WsDelta` on a fresh connection carries `seq = 1`, and every
   subsequent delta on that connection increments it by exactly 1. The counter
   is per connection (member of `WsConnection`), never global, never
   `System.nanoTime()`, and it restarts at 1 whenever a new connection is
   established (including reconnects after client reboot).
2. Control frames (`WsPing`/`WsPong`) keep their own independent counter. The
   server sequence-checks only `WsDelta` frames.
3. The server treats every authenticated WS handshake as a NEW EPOCH for that
   device: immediately after auth succeeds it discards all previous sequence
   state for the device (expected seq becomes 0, reorder window emptied).
   Stale/replayed seqs are rejected ONLY within the current connection; state
   from an earlier connection can never reject a frame of a later one.
4. On `WsAck` with `error != null` the client must NOT advance its cursor.
   It logs through `appendDebug` and falls back to one HTTP push for that
   cycle. Cursor advancement rules are section d.
5. Server-side sequence state is evicted when device sessions close, so the
   map cannot grow forever.

**Implementation targets (SYNC-JVM):**

- `KtorSyncServerJvm.kt` WS route `webSocket("/sync/ws")` (:502): after
  `authenticator.verifyRequest` succeeds (:516), add
  `wsSeqState.remove(callerDeviceId)` (epoch reset). `wsSeqState` lives at
  :137, `WsSeqState` at :139, `checkWsSeq` impl at :816, call site at :566.
- `KtorSyncServerJvm.closeDeviceSessions` (:689-697): also
  `wsSeqState.remove(deviceId)` before/while closing sessions. The wrapper at
  :91 simply delegates; eviction belongs in the router method.
- `SyncTransportJvm.kt buildDelta` (:604-629): replace
  `seq = System.nanoTime()` (:607) with the per-connection counter, and set
  `since` (section b). `WsConnection` class is at :77-89: add the counter there.
- `SyncTransportJvm.kt` mutation collector (:462-471): stop setting
  `lastSyncTime` right after `sendDelta` (:467); advance only on a clean
  `WsAck` per section d.
- `SyncTransportJvm.kt` incoming reader `is WsAck` branch (:661): handle
  `msg.error != null` per pin 4 above (`appendDebug`, no cursor advance,
  HTTP push fallback for that cycle).

**Test obligation (TEST agent):** epoch-reset test: open connection A, push
high seq values, drop it; open connection B for the same device with seq
starting at 1; every frame must be accepted. Plus: `WsAck.error` leaves
`lastSyncTime` unchanged and triggers one HTTP push.

---

## b. Tombstone cutoff (cursor LWW rule)

**Pin:** ALL apply sites use the same rule:

> Apply a delete only when NO local entity exists for that id, OR
> `local.updatedAt <= senderCursor`.

Where `senderCursor` is: `batch.since` for HTTP push, `delta.since` for a WS
delta, the `since` argument for pull/`applySyncResponse`.

**The four (plus two) apply sites and their state:**

| # | Site | Current code | Status |
|---|---|---|---|
| 1 | JVM host HTTP push | `KtorSyncServerJvm.handlePush` (:735) `tombstoneCutoff = batch.since` (:754) | already correct, keep |
| 2 | JVM host WS delta | `KtorSyncServerJvm` WS `repo.applyBatch(... tombstoneCutoff = 0L)` (:597) | MUST change to `tombstoneCutoff = msg.since` (SYNC-JVM) |
| 3 | JVM client pull apply | `KtorSyncClient.applyPull` (:309) `tombstoneCutoff = since` (:328) | already correct, keep |
| 4 | iOS client apply (shared) | `SyncContract.applySyncResponse` (:164) `tombstoneCutoff = since`; callers pass `pushSince`/`pullCursor` (`IosSyncTransport.kt` :179, :190) | already correct, keep |
| 5 | JVM client WS incoming | `SyncTransportJvm.validateAndApplyDelta` (:770) `tombstoneCutoff = 0L` (:829) | MUST change to `tombstoneCutoff = msg.since` (SYNC-JVM) |
| 6 | iOS host push apply | `IosSyncServerRouter.applySyncBatch` (:408) `tombstoneCutoff = batch.since` (:429) | already correct, keep |

The WS paths today apply an unconditional `cutoff = 0`, which lets an older
WS delete wipe a newer local edit. Transport choice must not change delete
semantics.

**Sender obligation:** every delta builder sets `since` to the cursor it
built the delta from (`SyncTransportJvm.buildDelta` :604: `since =
lastSyncTime ?: 0L`). Default 0 (older senders) yields the conservative
"no local entity exists" behavior.

**Test obligation (TEST agent): PARITY TEST REQUIRED.** One fixture run
against all four data paths (JVM HTTP push, JVM WS delta, JVM client pull
apply, iOS `applySyncResponse`): a local entity with `updatedAt` below and
above the sender cursor plus a remote tombstone for both. Delete/keep
outcomes must be identical across every path.

Out of scope here (tracked by the audit, separate fix): 30-day tombstone
retention at `data/JournalRepository.kt:131` resurrects deletes for peers
offline longer than the window; the eventual rule is "retain until every
peer's high-water mark passes".

---

## c. Conflict merge (shared function, losing body preserved)

**Pin:**

1. ONE shared, pure merge function lives in commonMain (target file:
   `commonMain/sync/SyncConflictMerge.kt`) and every apply path calls it. No
   platform may implement its own conflict branch.
2. Signature (pinned):

   ```kotlin
   fun mergeNoteConflict(existing: Note?, incoming: Note, remoteDeviceId: String): Note
   ```

3. Semantics:
   - `existing == null` -> return `incoming`.
   - bodies equal -> return `incoming` carrying the union of both
     `conflictSiblings`.
   - bodies differ -> the note with the HIGHER `updatedAt` wins (tie:
     incoming wins). The LOSING note's FULL body is appended to the winner's
     `conflictSiblings` as `ConflictSibling(body = loser.body, deviceOrigin =
     remoteDeviceId, updatedAt = loser.updatedAt)`. The losing body is never
     destroyed. Winner keeps its own `updatedAt` (no timestamp inflation, so
     the merge is idempotent and LWW stays stable across peers).
4. The session-outcome conflict branch that exists only on the JVM host today
   (`KtorSyncServerJvm.handlePush` :756-770, conflict note with title
   `Sync conflict: <title>`) moves into the same shared file as
   `mergeSessionConflict(existing, incoming, deviceOrigin)` so the iOS host
   stops silently LWW-upserting identical peer data into a different journal.
   Device names flowing into conflict notes stay truncated to 200 chars.
5. `pendingConflictCount` is defined as: the number of notes whose
   `conflictSiblings` is non-empty (`IJournalRepository.pendingConflictCount`
   :66, already computed at `JournalRepository.kt:114`).
6. The five apply paths that must route through the shared functions:
   1. JVM host HTTP push: `KtorSyncServerJvm.handlePush` (:735-780, note
      branch calls `repo.upsertNoteWithConflict` at ~:776 today).
   2. JVM host WS delta: `KtorSyncServerJvm` `repo.applyBatch` (:579-598)
      (notes currently bypass conflict logic entirely).
   3. JVM client pull apply: `KtorSyncClient.applyPull` (:309-336).
   4. iOS client apply: `SyncContract.applySyncResponse` (:164).
   5. iOS host push apply: `IosSyncServerRouter.applySyncBatch` (:408-431).

   Mechanically this means `JournalRepository.upsertNoteWithConflict`
   (`data/JournalRepository.kt:776-794`, currently KEEPS the incoming body as
   main and stores the incoming body again as sibling, LOSING the local body)
   becomes a thin wrapper over `mergeNoteConflict`, and `applyBatch`'s note
   branch calls the same wrapper. Owners: the data-layer agent plus SYNC-JVM
   and SYNC-IOS for call sites.
7. **UI obligation (UI agent):** render a conflict banner driven by
   `pendingConflictCount`; surface the sibling bodies for review. Today the
   field has zero ui/ references, so conflicts are invisible to users.

**Test obligation (TEST agent):** for every one of the five paths, a fixture
with differing bodies on both sides must leave BOTH bodies in the store
(winner body + `conflictSiblings`), and `pendingConflictCount` must equal the
number of conflicted notes.

---

## d. Push chunking and cursor discipline

**Pin:**

1. The client slices its outgoing `SyncBatch` with
   `buildPushSlices(batch, SyncChunkLimits())` (`SyncChunking.kt:47`). Every
   slice respects the validator caps: interactions <= `SyncLimits.MAX_INTERACTIONS`
   (100), substances <= `MAX_SUBSTANCES` (1000), effects/custom units <= 100,
   everything else (sessions, doses, notes, events, and every tombstone list)
   <= `MAX_ITEMS_DEFAULT` (500). Slices preserve metadata and entity order;
   concatenation reproduces the input exactly with disjoint ids. An empty
   batch yields exactly one empty slice (documented choice, tested).
2. Slices are sent SEQUENTIALLY: slice N+1 only after slice N received a
   response. Every slice response must carry `success = true`.
3. The cursor advances ONLY after every slice returns `success = true`
   (`allSucceeded` / `advanceCursorIfAllSucceeded`, `SyncChunking.kt:122/:130`).
   On ANY failure (transport error, `success = false`, decode failure, zero
   acks) the cursor stays at its previous value; the next cycle resends from
   there. The SAME hold rule covers the pull half: the cycle cursor may only
   move through `cursorAfterCycle(previous, cycleStart, acks, drainComplete)`
   (`SyncChunking.kt`), and `drainComplete` is false whenever the paginated
   pull did not reach an untruncated page. An incomplete drain therefore
   keeps the old cursor instead of skipping whatever never arrived (an
   advance to `cycleStart` would make those rows invisible to every later
   cycle while the process lives).
4. Cursor VALUE for a push cycle: the wall-clock instant captured BEFORE the
   batch was built (`cycleStart`, taken immediately before
   `buildSyncBatch`/`pushChanges` enters), NOT `currentTimeMillis()` at or
   after ack time. Rationale: tombstones carry no wire timestamps (so
   `maxUpdatedAt` alone would resend them forever), and a future-dated
   entity could inflate `maxUpdatedAt` past concurrent deletes. Capturing
   before the build guarantees no change made during the round trip is
   skipped. This is the ONLY wall-clock value permitted as a cursor.
   Banned patterns (present today, must be removed):
   - `SyncTransportJvm.kt:256` `lastSyncTime = System.currentTimeMillis()`
     after `pushChanges` succeeds.
   - `SyncTransportJvm.kt:467` same, right after `sendDelta`.
   - `SyncTransportJvm.kt:656` same, right after applying an incoming delta.
     Incoming deltas and acks must not stamp wall-clock either: advance by
     the applied payload's `maxUpdatedAt` at most, never by the local clock.
   - `IosSyncTransport.kt:199` `_status.update { it.copy(lastSyncAt = currentTimeMillis()) }`
     after the whole sync (audit C2): banned outright.
5. Pull pagination drains while `truncated == true` following the server's
   COMPOSITE low-water cut `(nextSince, nextSinceId)` — the pair that walks
   inside a group of tied timestamps. A timestamp alone cannot: the bundled
   seed's 2015 interactions all share one `updatedAt`, so the old
   `updatedAt > since` filter re-served page one and silently dropped every
   row past the cut. The resume rule is `isAfterPullCursor(updatedAt, id,
   since, sinceId)` (`SyncContract.kt`): with an `sinceId` half it is
   strictly-after in `(updatedAt, id)` order; with a blank `sinceId` (wall-
   clock cursor, or an old server) it is inclusive `updatedAt >= since`, so
   a row stamped in the cursor's own millisecond is re-served rather than
   skipped. Falling back to `maxUpdatedAt` only applies when an old server
   sends `nextSince = 0`; a cursor that cannot advance STOPS the drain
   (`complete = false`) instead of looping. Drains
   (`KtorSyncClient.drainPullPages` and `IosSyncTransport.syncWith`) must
   report completion; the bound is 200 pages (~100k rows of one collection)
   and exists only to bound a hostile peer — hitting it is not a loss path,
   because incomplete means the cursor is held. Wire-compatible both ways:
   `SyncResponse.nextSinceId` defaults to `""` (old peers decode it blank
   and keep their timestamp-only behaviour; old servers never send it).
6. `lastSyncTime`/`lastSyncAt` is per device and persists across cycles; it
   is never reset to "now" on failure.

**Implementation targets:** SYNC-JVM (`KtorSyncClient.pushChanges` :164,
which currently builds ONE un-chunked batch at :166-192 and is audit C1;
`SyncTransportJvm` :243-258, :462-471, :655-657) and SYNC-IOS
(`IosSyncTransport.syncWith` :141-206, push at :152-181).

**Test obligation (TEST agent):** end-to-end integration pushing a batch with
2015 interactions (the bundled seed) through a real server: all slices
accepted, cursor advanced once, and a forced failure in slice k leaves the
cursor at the pre-cycle value. Plus the mirror pull obligation: a fresh
client pulling from cursor 0 against a host holding 2015 interactions that
share ONE `updatedAt` receives all 2015 rows and reports
`drainComplete = true` (`KtorSyncServerIntegrationTest`), and a drained
cut is always resumed with its `nextSinceId` half.

---

## e. EntityTimePolicy (single timestamp policy)

**Pin:** the ONLY accepted bounds are the constants in
`commonMain/sync/SyncLimits.kt object EntityTimePolicy` (:55):

- `MIN_ENTITY_TIMESTAMP = 946684800000L` (2000-01-01T00:00:00Z)
- `FUTURE_MARGIN_MS = 86_400_000L` (1 day)
- `isReasonableEntityTime(ts, now = currentTimeMillis())`

The former iOS 2-year margin (`IosSyncValidators.TIMESTAMP_FUTURE_MARGIN_MS`)
is ELIMINATED; the file that declared it was deleted in Phase 0a, and the
iOS host router now reads the shared margin (`IosSyncServerRouter.kt:448`).
Consumers: `SyncValidation` entity checks (both hosts + client guard),
`SyncTransportJvm.isReasonableTimestamp` (:762, done), pull `since` bounds
(`KtorSyncServerJvm:473`, `IosSyncServerRouter:308`, done).

**Open obligations (owners: data/util and ingest agents; files outside
Phase 0a scope):**

- `util/ExportImport.kt:34` `MIN_VALID_TIMESTAMP = 946684800000L` and :37
  `TIMESTAMP_FUTURE_MARGIN_MS = 86400000L` MUST become aliases of
  `EntityTimePolicy.MIN_ENTITY_TIMESTAMP` / `EntityTimePolicy.FUTURE_MARGIN_MS`
  (values already match; make the dependency explicit so they cannot drift).
  `export/obsidian/ObsidianNoteImporter.kt:30` then follows automatically.
- `ingest/ManualImportAdapter.kt:28-29` hardcodes both literals; replace with
  the same constants.

---

## f. HostInfo.wsSupported

**Pin:** `HostInfo` carries `wsSupported: Boolean = false`
(`SyncPayload.kt:73`). Defaulted, so `ignoreUnknownKeys` decoding is safe in
both directions and older peers decode as "no WS".

- JVM host advertises `wsSupported = true` at both construction sites
  (`KtorSyncServerJvm.kt` `/info` :159, `/pairing/start` :172). DONE.
- iOS host advertises `false` explicitly (`IosSyncServerRouter.kt` :52, :65).
  DONE.
- **Client obligation (SYNC-JVM):** clients MUST skip WebSocket when the
  field is absent or false and use HTTP push/pull only. Concretely:
  `SyncTransportJvm.startContinuousSync` (:433) and the reconnect loop
  (trigger around :745) must consult the peer's `HostInfo`
  (`KtorSyncClient.requestHostInfo` :67) before opening a socket. Today the
  JVM always attempts WS, which 404-cycles against every iOS host. The iOS
  client never uses WS (`IosSyncTransport.startContinuousSync` loops HTTP
  `syncWith`), so no iOS change is needed.

---

## g. Pairing security (audit C4): DECISION = Option ECDH

### Investigation findings (Phase 0a)

- `jvmMain/sync/TlsIdentityManager.kt` generates an RSA self-signed PKCS12
  identity, but its own header (:18-20) states TLS is NOT wired into the sync
  server; the cert exists only for fingerprint/device-id generation. There is
  no server-side SSLContext anywhere.
- `jvmMain/sync/KtorSyncClient.kt` is a plain CIO `HttpClient` with no
  TrustManager, no certificate pinning, and an UNUSED `trustedFingerprint`
  constructor parameter. No fingerprint-pinning code exists on any client.
- The hosting UI (`commonMain/ui/components/sync/SyncHostingCard.kt`) shows
  ONLY the pairing token and host address (:96-110, :165-226). The audit's
  claim that a fingerprint is "already shown in the hosting UI" is STALE:
  no screen displays the host fingerprint for comparison. Option TLS would
  therefore also require new UI it was premised on not needing.
- iOS hosting (`IosSyncTransport.startHosting`) has NO identity/cert
  infrastructure at all (no `TlsIdentityManager` equivalent in iosMain), so
  "serve the WHOLE sync server over TLS with the existing self-signed
  identity" cannot cover iOS hosts without building cert generation from
  scratch on iOS, unverifiable on this Windows host.

### Decision

**Option ECDH** is pinned: the client sends an ephemeral ECDH public key
during pairing; the host seals the returned `sharedSecret` under the
ECDH-derived key with AES-GCM; the plaintext `sharedSecret` field leaves the
protocol. Chosen over Option TLS because (1) it fixes the actual C4 defect,
passive LAN capture of the permanent secret, without rewiring either server's
transport; (2) the JVM-only TLS identity has no iOS counterpart, so TLS would
force unverifiable cert-infra work on iosMain; (3) TLS flips every endpoint
URL plus client trust managers on both platforms and would break the
plaintext `testApplication` integration suite, while ECDH touches only the
pairing exchange; (4) all shared crypto (sha256, base64, AES-GCM
encryptBody/decryptBody) already exists as commonMain expect/actuals, so the
shared half of ECDH is real, testable code (landed as `PairingEcdh`).

Residual risk, accepted and documented in `PairingEcdh.kt`: an ACTIVE
man-in-the-middle present only during the 2-minute pairing window can still
substitute keys, because keys travel on plaintext HTTP. Defeating that
requires authenticated transport with a user-verified fingerprint (the TLS
option) and is deferred until BOTH hosts have certificate infrastructure and
the UI can display a fingerprint for comparison.

### Pinned ECDH wire spec (exact)

- Curve: NIST P-256 (secp256r1), both platforms.
- Public key encoding: ANSI X9.62 uncompressed point `0x04 || X || Y`
  (65 bytes), standard base64.
- Client key: EPHEMERAL, fresh per pairing attempt, discarded afterward.
  Sent in `PairingVerifyRequest.clientEcdhPublicKeyB64`
  (`PairingEcdh.CLIENT_PUBLIC_KEY_FIELD`). Note: the pairing START step is a
  body-less GET (`GET /pairing/start` returns `HostInfo`), so the key rides
  on the POST `/pairing/verify` body; the keypair is still generated before
  START so the exchange precedes the token entry. (This is a deliberate
  precision over the earlier "with /pairing/start" sketch, which cannot carry
  a request body.)
- Host key: STATIC, generated once, persisted with the device identity.
  Advertised as `HostInfo.ecdhPublicKeyB64`
  (`PairingEcdh.HOST_PUBLIC_KEY_FIELD`) on BOTH `GET /info` and
  `GET /pairing/start`, so clients hold it before the token is entered.
- Agreement: both sides compute the raw P-256 shared secret. JVM
  `KeyAgreement.generateSecret()` returns the 32-byte X coordinate; iOS
  `SecKeyCreateKeyExchange` may return the 65-byte X9.63 form; pass either to
  `PairingEcdh.normalizeSharedSecret` (accepts 32 bytes, or 65 bytes starting
  with 0x04, and reduces to X).
- KDF: `wrapKey = SHA-256(X || UTF-8("nepenthe-pairing-ecdh-v1"))`
  (`PairingEcdh.KDF_INFO`). No salt, no counter.
- Sealing: `ecdhSecretB64 = base64(encryptBody(sharedSecret, wrapKey))`
  (`PairingEcdh.wrapSharedSecret` / `unwrapSharedSecret`), reusing the
  existing 12-byte-IV AES-256-GCM layout.
- The plaintext `PairingResultResponse.sharedSecret` field is REMOVED from
  the protocol: hosts stop populating it, clients stop accepting it, pairing
  fails CLOSED when `ecdhSecretB64` is absent. The legacy PBKDF2 wrap
  (`encSecretB64`) is superseded for the same reason its unwrap key (the
  token) sits in the same cleartext request body.
- `SyncEndpoints.URL_SCHEME = "http"` (`SyncContract.kt:36`): ECDH keeps
  plaintext HTTP; build endpoint URLs from this constant so a later TLS
  phase is a one-line flip.

### Implemented in Phase 0a (commonMain only)

- DTO fields with defaults: `HostInfo.ecdhPublicKeyB64` (:80),
  `PairingVerifyRequest.clientEcdhPublicKeyB64` (:100),
  `PairingResultResponse.ecdhSecretB64` (:141); legacy fields kept but
  marked LEGACY in KDoc. Existing code compiles unchanged.
- `commonMain/sync/PairingEcdh.kt`: constants, normalization, KDF,
  wrap/unwrap, full spec in KDoc.
- `SyncEndpoints.URL_SCHEME`.
- commonTest coverage: `PairingEcdhTest` (round trip, X/Y normalization,
  wrong-key failure, malformed lengths, field-name pin, HostInfo defaults).

### SYNC-JVM obligations (exact)

1. Generate and persist a STATIC P-256 keypair next to `identity.p12`
   (extend `TlsIdentityManager` or add `EcdhIdentityManager` beside it).
   Advertise the base64 uncompressed public key at both `HostInfo` sites in
   `KtorSyncServerJvm.installRouting` (:152-174), field
   `ecdhPublicKeyB64`, and keep `wsSupported = true`.
2. `/pairing/verify` handler (mints the secret around :297-340): read
   `req.clientEcdhPublicKeyB64`; reject the pairing (success=false,
   400) when it is null, not valid base64, not 65 bytes, first byte != 0x04,
   or not a point on the curve; derive the shared secret with
   `KeyAgreement("ECDH")` using the STATIC private key; set
   `ecdhSecretB64 = PairingEcdh.wrapSharedSecret(shared, secret)`; STOP
   populating `sharedSecret` and `encSecretB64` (drop the
   `SyncAuthenticator.encryptPairingSecret` call).
3. `KtorSyncClient.completePairing` (:79-117): generate an EPHEMERAL P-256
   keypair per attempt; base64-encode the uncompressed point into
   `PairingVerifyRequest.clientEcdhPublicKeyB64`; resolve the secret ONLY
   via `PairingEcdh.unwrapSharedSecret` from `result.ecdhSecretB64`; FAIL
   with "host does not support ECDH pairing; upgrade the host" when absent.
   Delete the legacy fallbacks at :105/:107 and the `encSecretB64` path.
4. Never log key material or the sealed secret (the debug log replays to UI).
5. After SYNC-IOS has also stopped reading/writing them, delete
   `PairingResultResponse.sharedSecret` and `.encSecretB64`
   (`SyncPayload.kt` :118-131) plus any now-dead helpers
   (`PairingSecretCrypto`, `SyncAuthenticator.encryptPairingSecret`/
   `decryptPairingSecret`). Coordinate: whichever platform phase runs LAST
   removes the fields.

### SYNC-IOS obligations (exact; iosMain is NOT compilable on this host, keep edits mechanical)

1. `IosSyncServerRouter` HostInfo sites (:45-69): add
   `ecdhPublicKeyB64 = <static pub>`; keep `wsSupported = false`.
2. `/pairing/verify` (response built at :193-204): validate
   `req.clientEcdhPublicKeyB64` with the same rules as the JVM, perform ECDH
   with the host's STATIC private key (SecKeyCreateKeyExchange), set
   `ecdhSecretB64 = PairingEcdh.wrapSharedSecret(...)`, stop populating
   `sharedSecret` (:197) and `encSecretB64` (:198, replaces the
   `IosPairingSecretCrypto.encrypt` call at :188).
3. `IosSyncTransport.connectManually` (:275-315): generate an ephemeral
   keypair, send `clientEcdhPublicKeyB64` in the `PairingVerifyRequest`
   (:286-291), resolve the secret via `PairingEcdh.unwrapSharedSecret` from
   `result.ecdhSecretB64`, replacing `IosPairingSecretCrypto.resolveSecret`
   (:299-304); fail closed when absent.
4. Generate/persist the host static keypair on first host start (alongside
   the device identity in `IosDeviceIdentityStore` or a sibling file).
5. Same never-log rule; after the JVM phase also drops the legacy fields,
   remove `IosPairingSecretCrypto` if unused (or when this phase runs last,
   delete the DTO fields per the SYNC-JVM item 5 handshake).

### UI obligation (UI agent, optional but recommended)

Display the host fingerprint (and later the ECDH key fingerprint) next to
the pairing token so a user CAN compare devices out of band. This is the
missing prerequisite for any future move to Option TLS.

---

## 7. Dead code removed and remaining (proofs)

Removed in Phase 0a:

- `commonMain/sync/KtorSyncClient.kt`: contained ONLY two comment lines (no
  package, no declarations). Grep across all source sets shows every
  `KtorSyncClient` reference resolves to `jvmMain/sync/KtorSyncClient.kt:41`;
  zero references from commonMain, iosMain, or androidMain.
- `SyncContract.VerifiedPush` (was :236-240): a repo-wide grep found the
  declaration and nothing else, no tests included.

Deliberately NOT removed:

- `SyncPushRequest` (commonMain): production-dead but actively used by
  `SyncContractTest` (:114, :178) and `IosSyncTransportIntegrationTest`
  (:48, :81, :100, :234) as the reference request builder. Remove only with
  those tests.

Remaining dead code OUTSIDE Phase 0a scope, reported for the dead-code batch:

- `jvmMain/sync/KtorSyncClient.pullChanges` (:228) and `fetchHostInfo` (:263):
  zero production callers (audit).
- `jvmMain/sync/SyncAuthenticator.signRequest`/`signPairingResponse`/
  `verifyPairingResponse`: zero production callers (audit).
- `KtorSyncServerJvm.checkContentLength` (find in file; never called, opposite
  missing-header policy of the live `hasValidContentLength`).

---

## 8. Consolidated obligations by downstream agent

**SYNC-JVM:** section a items 3-5 (epoch reset at `KtorSyncServerJvm:516`,
seq eviction in `closeDeviceSessions:689`, per-connection client counter at
`SyncTransportJvm:607`, `WsAck.error` handling at :661); section b rows 2 and
5 (`tombstoneCutoff = msg.since`) plus `buildDelta` setting `since`; section d
(chunk `pushChanges`, sequential acks, cursor rules, remove wall-clock stamps
at :256, :467, :656); section f client skip (gate `startContinuousSync` on
`wsSupported`); section g SYNC-JVM items 1-5; section c call sites for the
JVM host paths.

**SYNC-IOS:** section b sender `since` (already produced by shared code once
`buildDelta` equivalents set it; iOS has no WS sender); section d cursor
discipline in `syncWith` (replace `lastSyncAt = now` at :199, check
`syncResp.success` before any cursor move, treat decrypt/decode failure as
failure: audit C2); section g SYNC-IOS items 1-5; section c call sites for
the iOS host path; iosMain edits must be re-read end-to-end (no compiler on
this host).

**TEST:** tombstone cutoff parity test (section b); 2015-interaction
end-to-end push (section d); conflict merge keeps losing body on all five
paths plus `pendingConflictCount` (section c); WS epoch reset and
`WsAck.error` cursor behavior (section a). CommonTest already covers
chunking, the response guard, and PairingEcdh.

**UI:** conflict banner from `pendingConflictCount` (section c item 7);
optional fingerprint display (section g).

**data/util/ingest owners:** shared conflict merge wrapper in
`JournalRepository.upsertNoteWithConflict` (section c item 6, NOTE the
current implementation loses the local body); ExportImport and
ManualImportAdapter constants (section e).

**Never:** git push, wall-clock cursors, per-device cross-connection seq
rejection, plaintext `sharedSecret` emission, redeclaring sync caps or
timestamp bounds outside `SyncLimits`/`EntityTimePolicy`.
