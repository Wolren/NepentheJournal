# Nepenthe Journal — Token Pairing Security & Robustness Audit

Date: 2026-07-31
Scope: pairing protocol end-to-end (token lifecycle, /pairing/start, /pairing/verify, HMAC auth, nonce replay, rate limiting, trust store, secret handling, WS continuous sync, iOS parity, test coverage)
Files audited: KtorSyncServerJvm.kt, SyncAuthenticator.kt, KtorSyncClient.kt (JVM), SyncTransportJvm.kt, DeviceTrustStore.kt, TlsIdentityManager.kt, SyncCryptoJvm.kt, SyncValidators.kt, SyncContract.kt, SyncWebSocket.kt, IosSyncTransport.kt, JournalRepository.applyBatch, 4 test files

---

## Verdict

The pairing protocol is structurally sound: the token never leaves the host screen, the shared secret is 256-bit random, requests are HMAC-SHA256 signed with constant-time comparison, nonces are replay-protected with a bounded set, bodies are AES-256-GCM encrypt-then-MAC, and the old two-secret bug is genuinely fixed. No CRITICAL finding. The weaknesses are concentrated in four areas: the rate limiter's client-IP key is wrong, secrets at rest are obfuscated with derivable keys rather than encrypted, the pairing exchange itself is sniffable by design, and sync responses are not freshness-bound (replay can roll back data).

Findings: 2 HIGH, 4 MEDIUM, 8 LOW. Exploitability on a residential LAN is low across the board; the HIGHs are broken-defense-in-depth, not active holes.

---

## HIGH

### H1. Rate limiter keys on a client-controlled header (or the server's own IP)

**Files:** `KtorSyncServerJvm.kt:132-133` (`X-Forwarded-For` ?: `local.remoteHost`), `:383-393` (`isRateLimited`)

**Detail:** `/pairing/verify` rate limiting keys on `call.request.headers["X-Forwarded-For"]` first. The server is directly reachable on the LAN with no proxy, so this header is entirely attacker-supplied. Rotating `X-Forwarded-For` values resets the bucket per request: the 5-attempts-per-120s cap is trivially bypassed. The fallback `call.request.local.remoteHost` is the server's own address in Ktor 3 (verified in a prior session), which makes every client on the network share ONE global bucket of 5 attempts per 120s — blocking legitimate second devices while protecting nothing.

**Exploit scenario:** an attacker sprays token guesses with a random `X-Forwarded-For` per request. The limiter never trips. The only thing that saves the token is its 2^29.7 entropy against the 120s TTL: a full brute force needs ~7.4M attempts/sec, which is not feasible over LAN HTTP. So the bypass does not produce a practical brute-force today — it removes the only throttle, so any future weakening of entropy or TTL (or a longer-lived token) becomes instantly brute-forceable.

**Remediation:** key the limiter on the socket-level remote address, never on a client-supplied header unless the server sits behind a configured trusted proxy. On Ktor 3 Netty, log `call.request.local.remoteHost` and `call.request.origin` once to see which actually carries the peer address, and use the one that does. If a proxy is ever introduced, install the `ForwardedHeaders` plugin and only then trust `X-Forwarded-For`. The `ConcurrentHashMap.compute` logic itself (atomic read-modify-write, 5 then block, no off-by-one) is correct — the key is the only problem.

### H2. Secrets at rest are derivable by anyone who can read the file

**Files:** `DeviceTrustStore.kt:253-262` (deriveKey), `TlsIdentityManager.kt:42-57` (derivePassword)

**Detail:** the trust store encrypts shared secrets with AES-256-GCM, but the key is `PBKDF2(password = "nepenthe-truststore-v2" (hardcoded constant), salt = random, 100k)`. The salt is stored in the file next to the ciphertext, and the password is a public constant in the source. Anyone with file read access derives the key in ~10ms and decrypts every shared secret. Same class on the TLS identity: `PBKDF2(user.home + os.name, salt = "nepenthe-tls-v1" (constant), 100k)` — both inputs are guessable from public knowledge of the OS and username.

**Exploit scenario:** a second local user (or a backup/cloud-sync leak of the data dir) reads `trusted-devices.json`, derives the key from the known constant + embedded salt, decrypts all shared secrets, and can impersonate every paired device on the LAN.

**Remediation:** the password must contain real entropy not present in the file: OS keychain (Windows Credential Manager / DPAPI, macOS Keychain), a user-provided passphrase, or a random key file with user-only ACL. Note the honest mitigation context: the journal JSON itself is plaintext, so a disk attacker already has the data; this finding is about the shared secrets enabling *device impersonation* beyond data theft. The previous audit flagged the same root cause; the PBKDF2 migration improved stretching but did not fix the derivable password.

---

## MEDIUM

### M1. Pairing exchange is sniffable: secret in plaintext over HTTP

**Files:** `KtorSyncServerJvm.kt:186-196` (response with sharedSecret), `KtorSyncClient.kt:95-103` (reads it)

**Detail:** the pairing response carries the 256-bit shared secret as plain JSON over HTTP. A passive LAN attacker (ARP spoofing) who captures the pairing exchange obtains the secret and can thereafter decrypt every sync body and sign every request. The `signPairingResponse` / `verifyPairingResponse` functions in `SyncAuthenticator.kt:141-162` look like a mitigation but are logically impossible in this design — the client has no secret yet at pairing time to verify a signature with. They are used only by tests.

**Exploit scenario:** attacker ARP-spoofs the host, passively captures the pairing POST/response, extracts the secret, then impersonates the client for all future syncs (or decrypts all traffic). One capture, permanent compromise of that pair.

**Remediation (accepted-risk documentation):** this is the documented no-TLS design. The clean fix is a Diffie-Hellman-style exchange: client generates an ephemeral keypair, sends the public half in `PairingVerifyRequest`; server derives the secret from both halves; the token authenticates the exchange. Moderate effort. At minimum, document that pairing must happen on a network the user trusts, and delete the dead `signPairingResponse` pair.

### M2. Host identity is never proven on fingerprint re-connect (mDNS spoofing)

**Files:** `SyncTransportJvm.kt:254-290` (fingerprint probe + secret reuse), `LanDiscovery` (mDNS advertisement)

**Detail:** when a client reconnects to a "trusted" peer, it probes `/pairing/start`, reads the host fingerprint, looks it up in the trust store, and if found re-uses the stored secret. But mDNS is unauthenticated and `/pairing/start` is open: an attacker registers a spoofed mDNS service advertising the victim host's deviceId + fingerprint (both publicly visible by probing the real host once). The client then connects to the attacker, finds the fingerprint trusted, and pushes encrypted data to the wrong host.

**Impact:** data stays confidential (AES-GCM) and unmodifiable (attacker lacks the secret), so this is not data theft. It is sync redirection: the attacker learns *when* data changes (mutation pushes) and can disrupt syncing. The client never proves the host knows the secret.

**Remediation:** challenge-response on reconnect. Client sends a random challenge; host replies with `HMAC(secret, challenge)`; client verifies against the stored secret. The existing `signPairingResponse`/`verifyPairingResponse` machinery is the right shape — wire it as a host-challenge endpoint instead of deleting it.

### M3. Sync responses and WS deltas are not freshness-bound: replay rolls back data

**Files:** `KtorSyncServerJvm.kt:263-266, 302-305` (encrypted responses), `JournalRepository.applyBatch` (`putAll` blind upsert), `SyncTransportJvm.kt` applyPull

**Detail:** push/pull responses and WS deltas are AES-GCM authenticated (cannot be modified) but carry no binding to the request (no response nonce, no timestamp check on the client). A MITM can capture a response ciphertext and replay the exact bytes later. Because `applyBatch` is a blind upsert with no `updatedAt` comparison, re-applying an older response overwrites newer local data with stale server data.

**Exploit scenario:** attacker captures a push response (plaintext bytes on the wire — the ciphertext IS the wire body), replays it to the client after the user has made newer local edits; the client re-applies the old server state for the affected entities. Data loss by replay, no key required.

**Remediation (two cheap fixes):** (a) make `applyBatch` LWW-aware — skip entities whose `updatedAt` is older than the existing record (same rule the server already applies in `handlePush` for sessions); (b) bind responses to requests: server includes the request's nonce (from the auth header) in the response plaintext, client rejects mismatches. (a) alone closes the rollback window.

### M4. Token comparison is not constant-time

**Files:** `SyncAuthenticator.kt:79`

**Detail:** `pending.token == enteredToken.uppercase().trim()` — a standard equality comparison. Timing attack on a 6-char token over LAN HTTP is impractical (network jitter dwarfs per-char comparison time), and the token is single-use on success, so this is defense-in-depth. Cheap to fix: constant-time compare like the HMAC path already uses.

---

## LOW

### L1. Dead code would leak the token if ever wired
`PairingStartResponse` (`KtorSyncServerJvm.kt:471-480`) and `PairingStartResponseRaw` (`KtorSyncClient.kt:305-314`) contain a `token` field and are never used. The live `/pairing/start` correctly returns `HostInfo` without a token (verified: skill checklist item passes). Delete both dead classes so a future refactor cannot accidentally serialize the token to the wire.

### L2. Nonce RNG on iOS/common is predictable
`SyncContract.generateNonce()` (`SyncContract.kt:58-63`) uses `kotlin.random.Random`; the JVM client uses `SecureRandom` (`KtorSyncClient.kt:234-238`). Nonce predictability does not enable auth bypass (an attacker still cannot sign), but a predictable nonce stream weakens replay bookkeeping. Fix: expect/actual secure nonce, or reuse the platform HMAC signer's RNG.

### L3. Stale security docstring
`SyncTransportJvm.kt:22` claims "HMAC-SHA256 signed requests over HTTPS with cert pinning". The transport is plain HTTP + HMAC (a deliberate, documented decision). Design-doc-only security: a reader believes there is TLS. Update the docstring.

### L4. Pairing request fields unvalidated
`KtorSyncServerJvm.kt:171-181`: `clientDeviceId`, `clientDeviceName`, `clientFingerprint` are stored as-is (body capped at 4KB, but no per-field length cap). A paired client can register a 4KB display name, polluting the trusted-devices UI. Add the same MAX_ID_LEN-style caps used in SyncValidators.

### L5. Identity keystore silently regenerates on any load error
`TlsIdentityManager.kt:69-81`: any exception while loading the keystore (e.g. transient IO error, env-var password disappearing) deletes `identity.p12` and regenerates it — rotating the device fingerprint and forcing every paired client to re-pair. Also: if `NEPENTHE_TLS_PASSWORD` is set for first run and unset later, the same rotation happens. Fix: distinguish "password mismatch" (regenerate) from other IO failures (keep file, report error).

### L6. iOS has no persistent secret store
`IosSyncTransport.kt:61-62`: `pairingSecret` is in-memory only. Every app restart forgets the pairing and requires a full re-pair. Known TODO; robustness gap, not a security hole. Persist via NSUserDefaults/Keychain with the same PBKDF2-GCM scheme.

### L7. Sync payload timestamps not validated
`SyncValidators.kt`: no sanity bounds on `updatedAt`/`createdAt`/`timestamp` in any entity. A trusted-but-buggy (or compromised) peer can push `updatedAt = year 9999`, after which `since`-based syncs never return that entity again — silent sync stall. Add `0 < ts < now + 1 year` bounds. Also `Note.sessionId` length is unchecked in `validateNotes`.

### L8. Batch deviceId not cross-checked against the authenticated caller
`KtorSyncServerJvm.kt:411-441`: `handlePush` trusts `batch.deviceId` for conflict-note attribution and deviceOrigin without comparing it to the `X-Sync-Device` header. A paired device can attribute its writes to another device. Trusted-peer confusion only; one-line check fixes it.

---

## Verified strengths (no action needed)

| Control | Evidence |
|---------|----------|
| Token never on the wire | `/pairing/start` and `/info` return HostInfo only; token is generated + displayed host-side (`SyncTransportJvm.kt:119-128`) |
| Token entropy | 6 chars from 31-char alphabet ≈ 2^29.7; 120s TTL; single-use on success (`SyncAuthenticator.kt:68-82`) |
| Token refresh while hosting | regenerated every 60s, expiry surfaced in status (`SyncTransportJvm.kt:131-141`) |
| Failed guesses don't consume the token | invalidation only on match (`SyncAuthenticator.kt:79-81`) |
| Rate limiter arithmetic | `compute` atomic; 5 allowed, 6th blocked; no off-by-one (key is the problem, see H1) |
| Shared secret | 32 random bytes, hex, 256 bits; one secret, correctly returned to client and stored under the client's id — the old two-secret bug is gone (`KtorSyncServerJvm.kt:170-181`) |
| HMAC | SHA-256, constant-time compare, 45s window, nonce replay blocked via `putIfAbsent` with oldest-eviction (no `clear()` wipe) (`SyncAuthenticator.kt:33-49, 181-186`) |
| Encrypt-then-MAC | AES-256-GCM, random 12-byte IV per message, HMAC over the base64 ciphertext (`SyncCryptoJvm.kt:37-58`, `KtorSyncClient.kt:135-139`) |
| Wire key derivation | PBKDF2 600k iterations; fixed salt acceptable because key material is high-entropy random (`SyncCryptoJvm.kt:29-34`) |
| Size limits | 10MB sync body, 4KB pairing body, 10MB WS frames, entity caps 500/100, field caps 128-65536 (`SyncValidators.kt`, server routes) |
| Trust store hygiene | PBKDF2-100k + GCM + random salt, atomic tmp+rename writes, orphan-tmp cleanup, corrupt-file reset (password issue per H2) |
| Client robustness | 30s/10s/15s timeouts, 3x exponential-backoff retry, sync mutex, `client.close()` in finally (`KtorSyncClient.kt:57-61, 273-294`) |
| Test coverage of pairing | integration test exercises the REAL `/pairing/verify` (token generated, posted, secret stored, pushed with it) — no test bypass (`KtorSyncServerIntegrationTest.kt:50-123`); rate limit 5-then-block tested; nonce replay, wrong secret, malformed headers, single-use, expiry, wrong device all unit-tested (`SyncAuthenticatorTest.kt`) |

---

## Suggested fix order

1. H1 — rate limiter key (socket remote address; log-first to confirm the API)
2. M3a — LWW-aware applyBatch (also fixes local multi-device staleness)
3. M2 — host challenge-response on fingerprint reconnect (reuse signPairingResponse machinery)
4. H2 — real secret source for at-rest encryption (OS keychain first)
5. M1, M4, L1-L8 — as time permits; L1/L3/L5 are 10-minute deletions/doc fixes

## Fix status (2026-07-31, same day)

| Finding | Status | Fix |
|---------|--------|-----|
| H1 rate limiter key | FIXED | `/pairing/verify` keys on `call.request.local.remoteHost` only; X-Forwarded-For no longer trusted. Empirically verified on Ktor 3.5.1 Netty that `local.remoteHost` carries the socket peer address (probe test: 127.0.0.1 for loopback client, not the 0.0.0.0 bind). The earlier "returns server IP" note was wrong for server-side use. |
| H2 secrets at rest | FIXED | New `AtRestKey` (dataDir/at-rest.key): 32 random bytes, POSIX 0600 / icacls user-only, used directly as the AES-256 key for the trust store and as the identity.p12 password. Legacy stores keep working (decrypt chain tries at-rest key, then PBKDF2, then SHA-256); existing installs do NOT rotate identity on upgrade (keystore-exists → legacy derivation). First save on a legacy store re-encrypts with the key file. |
| M2 host identity | FIXED | New `GET /auth/verify?deviceId=&challenge=` endpoint: host replies with HMAC-SHA256("challenge:<deviceId>:<timestamp>:<challenge>") using the peer's shared secret. Client `verifyHostIdentity()` checks signature, timestamp window (45s), constant-time compare. Wired into the fingerprint re-connect path in SyncTransport: stored secret is reused ONLY after the challenge passes; failures fall through to pairing. Challenge bound to a fresh random nonce, so captured responses cannot be replayed. |
| M3 replay rollback | FIXED | `JournalRepository.applyBatch(..., lastWriterWins=true)` skips entities whose updatedAt is older than the existing record. All sync paths pass true: KtorSyncClient.applyPull, server handlePush + WS delta, SyncTransportJvm WS delta, SyncContract.applySyncResponse (iOS). Seed load and backup restore keep the default false so Reset/restore stay authoritative. |
| M4 token compare | FIXED | `verifyPairingToken` uses constant-time comparison. |
| L1 dead token-bearing DTOs | FIXED | `PairingStartResponse` (server) and `PairingStartResponseRaw` (client) deleted. |
| L2 nonce RNG | FIXED | `expect fun secureRandomBytes(size)` — SecureRandom (JVM) / SecRandomCopyBytes (iOS). Common `generateNonce()` now uses it (proper nibble hex, 32 chars). |
| L3 stale docstring | FIXED | SyncTransportJvm class doc now states plain HTTP + HMAC + AES-256-GCM LAN threat model. |
| L4 pairing field caps | FIXED | deviceId ≤ 128, deviceName ≤ 200, fingerprint ≤ 128 → 400 otherwise. |
| L5 keystore regeneration | FIXED | `ensureIdentity` regenerates only on empty/tampered/password-mismatch (IOException, UnrecoverableKeyException); other failures propagate instead of silently rotating identity. |
| L8 batch deviceId spoof | FIXED | `/sync/push` rejects batches whose deviceId differs from the authenticated caller (403). Also enforced in the iOS server mirror. |
| M1 sniffable pairing | OPEN | Protocol redesign (DH exchange); documented accepted risk, pairing should happen on a trusted network. |
| L6 iOS secret persistence | OPEN | Requires Keychain integration; iOS still re-pairs on every app restart. |
| L7 sync timestamp bounds | OPEN | SyncValidators lacks updatedAt/createdAt sanity bounds; client-side WS filter has isReasonableTimestamp but the server validators do not. |

New tests: auth/verify proves-host-knows-secret (real endpoint, recomputed signature), rejects unknown device, rejects missing params, push deviceId mismatch 403, LWW applyBatch (stale skipped, equal/newer applied), SessionListViewModelTest fixed and re-enabled (see below).

## Severity table

| Severity | Count | IDs |
|----------|-------|-----|
| CRITICAL | 0 | — |
| HIGH | 2 | H1, H2 |
| MEDIUM | 4 | M1, M2, M3, M4 |
| LOW | 8 | L1-L8 |
