package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.data.JournalStore
import app.journal.serde.AppJson
import app.journal.log.Log
import app.journal.model.SyncConfig
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
import app.journal.sync.aesEncryptionKey
import app.journal.sync.encryptBody
import app.journal.sync.decryptBody
import app.journal.util.crypto.base64Encode
import app.journal.util.crypto.base64Decode
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString

/**
 * iOS implementation of SyncEngine that speaks the standard Nepenthe sync protocol.
 *
 * Wire format: SyncBatch (push) / SyncResponse (pull/push response)
 * Auth: HMAC-SHA256 via "X-Sync-Auth" header (timestamp:nonce:hex-signature)
 * Bodies: AES-256-GCM encrypted, base64 wrapped (encrypt-then-MAC)
 * Transport: plain HTTP (no TLS on LAN, HMAC plus encryption secures data)
 * Discovery: Bonjour via NSNetServiceBrowser
 *
 * Security model (mirrors the JVM transport):
 * Pairing verifies a single use 6 char token with a 120s TTL before any
 * secret is minted. Secrets are stored per device in a sandboxed trust
 * store, requests look up the caller deviceId, unknown callers are
 * rejected, and the push batch deviceId must equal the caller. Nonces are
 * replay protected with a 45s window and oldest first eviction.
 *
 * Compatible with desktop and Android hosts using the same protocol.
 */
class IosSyncTransport(
    private val repo: IJournalRepository,
    private val dataDir: String = platformSyncDataDir(),
    /** Light persistence callback supplied by the composition root; falls back to a self-built store. */
    private val persistAfterApply: (() -> Unit)? = null
) : SyncEngine {

    private val json = AppJson.json
    /**
     * The live embedded server, retained instead of discarded after start.
     *
     * Audit HIGH "iOS embedded server fire-and-forget": the engine used to
     * be built inside a fire-and-forget `scope.launch` whose result was
     * dropped, so stopHosting only cancelled an already completed Job, the
     * listener kept its port bound for the life of the process, and a later
     * startHosting failed with "address already in use". Holding the
     * reference is what lets stopHosting and dispose really stop the engine
     * and release the port.
     */
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val continuousSyncJobs = mutableMapOf<String, Job>()

    private val _status = MutableStateFlow(SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    internal val identityStore = IosDeviceIdentityStore(dataDir)
    internal val trustStore = IosDeviceTrustStore(dataDir)
    /** Static host ECDH keypair store (contract section g), file-backed beside the identity. */
    internal val ecdhIdentity = IosEcdhIdentityStore(dataDir)
    internal val pairingManager = IosPairingManager()
    internal val nonceCache = IosNonceReplayCache()

    /** Stable random device id persisted in the app sandbox. */
    internal val deviceId: String by lazy { identityStore.deviceId() }
    /** Public fingerprint: SHA-256 hex of the secret identity bytes. */
    internal val deviceFingerprint: String by lazy { identityStore.fingerprint() }

    private val pairingAttempts = mutableMapOf<String, Pair<Int, Long>>()
    private val pairingAttemptsLock = PlatformLock()

    override suspend fun startHosting(config: SyncConfig): Result<HostingInfo> {
        return try {
            // Contract section g: generate (first host start) or load the
            // STATIC ECDH keypair before any route can advertise it; if the
            // key cannot be created, hosting fails closed.
            ecdhIdentity.publicKeyB64()
            val port = config.listenerPort
            val router = IosSyncServerRouter(
                repo = repo,
                trustStore = trustStore,
                pairingManager = pairingManager,
                nonceCache = nonceCache,
                deviceId = deviceId,
                deviceName = platformDeviceName(),
                fingerprint = deviceFingerprint,
                ecdhIdentity = ecdhIdentity,
                onConnection = { msg ->
                    _status.update { it.copy(lastError = msg) }
                },
                isRateLimited = ::isPairingRateLimited,
                // Durability: flush the journal to disk after every accepted
                // push and before the ack goes out (mirrors JVM factories).
                persistAfterApply = this.persistAfterApply ?: { JournalStore(repo).save(fullBackup = false) }
            )
            pairingManager.generatePairingToken()
            val now = currentTimeMillis()
            _status.update {
                it.copy(
                    pairingToken = pairingManager.currentPairingToken(),
                    tokenExpiresAt = now + PAIRING_TOKEN_TTL_MS,
                    pairedDeviceCount = trustStore.count()
                )
            }
            // Restart parity with the JVM host: release a port we already
            // hold before rebinding, otherwise the rebind fails with
            // "address already in use".
            server?.let { previous ->
                stopQuietly(previous)
                server = null
            }
            val srv = embeddedServer(CIO, port = port) {
                router.installRouting(this)
            }
            // Retain BEFORE starting, so every failure path below can stop
            // the engine and give the port back.
            server = srv
            try {
                // startSuspend returns only after the engine finished its
                // startup, and a bind failure (port taken, permission
                // denied) reaches the caller as an exception. Nothing below
                // this line claims hosting until that has happened: the old
                // code flipped isHosting and reported "0.0.0.0" while the
                // bind was still running in a discarded coroutine.
                srv.startSuspend(wait = false)
            } catch (e: CancellationException) {
                // Outer handler stops the engine and lets cancellation
                // propagate; never swallow it into a status update.
                throw e
            } catch (e: Exception) {
                server = null
                stopQuietly(srv)
                throw e
            }
            Log.withTag("IosSync").i { "iOS sync server bound on :$port" }
            // Advertised address stays the wildcard bind: iosMain has no
            // LAN IP resolver (resolveLocalIpV4 is jvmMain only, there is
            // no expect/actual local-address API and no Network framework
            // usage in this repo to reuse, and inventing getifaddrs C
            // interop here could not be compiled on the host that owns this
            // file). Peers pair against the address the user types.
            _status.update { it.copy(isHosting = true, hostAddress = "$BIND_ADDRESS:$port") }
            Result.success(HostingInfo(BIND_ADDRESS, port, deviceFingerprint))
        } catch (e: CancellationException) {
            // A cancelled start leaves neither a listener nor a claim: the
            // engine is stopped under NonCancellable, then the cancellation
            // is rethrown untouched.
            val srv = server
            server = null
            if (srv != null) stopQuietly(srv)
            throw e
        } catch (e: Exception) {
            Log.withTag("IosSync").e(e) { "startHosting failed: ${e.message}" }
            // Failure travels through the status mechanism this file already
            // uses (lastError), and no hosting claim survives it: isHosting,
            // the address and the just published pairing token are withdrawn
            // because nothing is listening.
            _status.update {
                it.copy(
                    isHosting = false,
                    hostAddress = null,
                    pairingToken = null,
                    tokenExpiresAt = null,
                    lastError = e.message
                )
            }
            Result.failure(e)
        }
    }

    override suspend fun stopHosting() {
        val srv = server
        if (srv != null) {
            try {
                // Really stop the engine: stopSuspend shuts it down and
                // releases the listener port. The previous implementation
                // only cancelled an already completed Job, so the port stayed
                // bound for the rest of the process lifetime.
                srv.stopSuspend(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Do not report "stopped": the reference and the hosting
                // flag stay as they are and the failure goes out through
                // lastError, so the snapshot keeps telling the truth about
                // a port that may still be bound.
                Log.withTag("IosSync").e(e) { "stopHosting failed" }
                _status.update { it.copy(lastError = "Sync server stop failed: ${e.message}") }
                return
            }
            server = null
            Log.withTag("IosSync").i { "iOS sync server stopped, port released" }
        }
        pairingManager.clearPendingPairing()
        _status.update {
            it.copy(
                isHosting = false,
                hostAddress = null,
                pairingToken = null,
                tokenExpiresAt = null
            )
        }
    }

    /**
     * Stop [srv] so its listener port is released, on every path that can
     * own it: normal stop, failed start, cancelled start. Runs under
     * NonCancellable so a cancelled startHosting still gets its port back.
     * Best effort by design: failures are logged and reported by the caller,
     * never thrown out of a cleanup path.
     */
    private suspend fun stopQuietly(
        srv: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>
    ) {
        try {
            withContext(NonCancellable) {
                srv.stopSuspend(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.withTag("IosSync").w { "Embedded sync server stop failed: ${e.message}" }
        }
    }

    /**
     * Pairing rate limiter keyed on socket level identity, 5 attempts per
     * 120s window. Expired buckets are evicted on EVERY call so this owned
     * map cannot grow without bound.
     */
    internal fun isPairingRateLimited(clientKey: String): Boolean = pairingAttemptsLock.withLock {
        val now = currentTimeMillis()
        val expired = pairingAttempts.filterValues { now - it.value.second > PAIRING_RATE_WINDOW_MS }.keys.toList()
        expired.forEach { pairingAttempts.remove(it) }
        val (count, windowStart) = pairingAttempts[clientKey] ?: Pair(0, now)
        val next = if (now - windowStart > PAIRING_RATE_WINDOW_MS) Pair(1, now) else Pair(count + 1, windowStart)
        pairingAttempts[clientKey] = next
        next.first > MAX_PAIRING_ATTEMPTS
    }

    override suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean): Result<Unit> {
        val peerDeviceId = peer.deviceId
        if (peerDeviceId == null) {
            return Result.failure(Exception("Unknown peer device: pairing required"))
        }
        val secretStr = trustStore.getSharedSecret(peerDeviceId)
            ?: return Result.failure(Exception("Not paired"))
        val secret = secretStr.encodeToByteArray()
        val aesKey = aesEncryptionKey(secretStr)
        val client = HttpClient(Darwin)
        return try {
            // Contract section d: capture the candidate cursor BEFORE any
            // network work. It is written to lastSyncAt ONLY after every push
            // slice and every pull page has succeeded (audit C2: no silent
            // cursor move, no success stamp on a failed cycle).
            val cycleStart = currentTimeMillis()
            val pushSince = _status.value.lastSyncAt ?: 0L
            val batch = buildSyncBatch(repo, deviceId, platformDeviceName(), pushSince)
            var pushResp: SyncResponse? = null
            if (batch != null) {
                // Slice the outgoing batch through the shared chunker so a
                // first sync against the 2015-interaction seed cannot deadlock
                // on the 100-interaction cap (audit C1 applies to iOS pushes
                // too): slices go out sequentially and the cursor advances
                // only if ALL of them ack success.
                val slices = buildPushSlices(batch)
                for (slice in slices) {
                    val batchJson = json.encodeToString(slice)
                    val encrypted = encryptBody(batchJson, aesKey)
                    val bodyStr = base64Encode(encrypted)
                    val time = currentTimeMillis()
                    val nonce = generateNonce()
                    val auth = buildAuthHeader(deviceId, bodyStr, secret, time, nonce)
                    val pushResponse = client.post("http://${peer.host}:${peer.port}${SyncEndpoints.SYNC_PUSH}") {
                        contentType(ContentType.Application.Json)
                        header(SyncAuth.DEVICE_ID_HEADER, deviceId)
                        header(SyncAuth.AUTH_HEADER, auth)
                        setBody(bodyStr)
                    }
                    val pushBody = pushResponse.bodyAsText()
                    // A body that cannot be base64-decoded, decrypted, or
                    // parsed is a FAILED cycle, never a skip: an unverifiable
                    // ack must not advance any cursor (contract section d).
                    val syncResp = if (pushBody.isEmpty()) null else try {
                        val encryptedResp = base64Decode(pushBody)
                        val respJson = decryptBody(encryptedResp, aesKey)
                        runCatching { json.decodeFromString<SyncResponse>(respJson) }.getOrNull()
                    } catch (e: Exception) {
                        null
                    }
                    if (syncResp == null) {
                        val msg = "Push response could not be decrypted or decoded (HTTP ${pushResponse.status.value})"
                        _status.update { it.copy(lastError = msg) }
                        return Result.failure(Exception(msg))
                    }
                    // Branch on success BEFORE any cursor move: on false the
                    // host's own error is surfaced through the status
                    // snapshot, lastSyncAt is untouched, and the cycle fails.
                    if (!syncResp.success) {
                        val msg = syncResp.error ?: "Push rejected by host"
                        _status.update { it.copy(lastError = msg) }
                        return Result.failure(Exception(msg))
                    }
                    pushResp = syncResp
                    applySyncResponse(repo, syncResp, pushSince)
                    persistApplied()
                }
            }

            // Pull: sign the exact target including the since param, same as JVM.
            // Drain while truncated, following the low-water nextSince cursor.
            // A failed, missing, or undecodable page fails the whole cycle
            // WITHOUT touching the cursor (contract section d).
            var pullCursor = if (pushResp?.truncated == true && pushResp.nextSince > 0L)
                pushResp.nextSince else (_status.value.lastSyncAt ?: 0L)
            var pullPages = 0
            while (pullPages < 20) {
                val pullResp = fetchPullPage(client, peer, deviceId, secret, aesKey, pullCursor)
                if (pullResp == null) {
                    val msg = "Pull response missing, undecodable, or rejected by host"
                    _status.update { it.copy(lastError = msg) }
                    return Result.failure(Exception(msg))
                }
                if (!pullResp.success) {
                    val msg = pullResp.error ?: "Pull rejected by host"
                    _status.update { it.copy(lastError = msg) }
                    return Result.failure(Exception(msg))
                }
                applySyncResponse(repo, pullResp, pullCursor)
                persistApplied()
                if (!pullResp.truncated) break
                val next = if (pullResp.nextSince > 0L) pullResp.nextSince
                    else maxOf(pullResp.maxUpdatedAt(), pullCursor)
                if (next <= pullCursor) break
                pullCursor = next
                pullPages++
            }

            // Reached only when every slice and page succeeded, stamped with
            // the pre-build cycleStart rather than the ack-time clock.
            _status.update { it.copy(lastSyncAt = cycleStart) }
            Log.withTag("IosSync").i { "Sync with ${peer.displayName} completed" }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.withTag("IosSync").e(e) { "Sync with ${peer.displayName} failed" }
            _status.update { it.copy(lastError = e.message) }
            Result.failure(e)
        } finally {
            client.close()
        }
    }

    private suspend fun fetchPullPage(
        client: HttpClient,
        peer: DiscoveredPeer,
        deviceId: String,
        secret: ByteArray,
        aesKey: ByteArray,
        cursor: Long
    ): SyncResponse? {
        val target = "${SyncEndpoints.SYNC_PULL}?since=$cursor"
        val auth = buildAuthHeader(deviceId, target, secret, currentTimeMillis(), generateNonce())
        val pullResponse = client.get("http://${peer.host}:${peer.port}$target") {
            header(SyncAuth.DEVICE_ID_HEADER, deviceId)
            header(SyncAuth.AUTH_HEADER, auth)
        }
        val pullBody = pullResponse.bodyAsText()
        if (pullBody.isEmpty()) return null
        return try {
            val encryptedResp = base64Decode(pullBody)
            val respJson = decryptBody(encryptedResp, aesKey)
            runCatching { json.decodeFromString<SyncResponse>(respJson) }.getOrNull()
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun startContinuousSync(peer: DiscoveredPeer) {
        val id = peer.deviceId ?: peer.host
        if (continuousSyncJobs.containsKey(id)) return
        continuousSyncJobs[id] = scope.launch {
            while (isActive) {
                syncWith(peer)
                delay(30_000)
            }
        }
    }

    override suspend fun stopContinuousSync(deviceId: String) {
        continuousSyncJobs[deviceId]?.cancel()
        continuousSyncJobs.remove(deviceId)
    }

    override suspend fun disconnectFrom(deviceId: String) = stopContinuousSync(deviceId)

    override suspend fun revokeTrustedDevice(deviceId: String) {
        trustStore.revokeDevice(deviceId)
        disconnectFrom(deviceId)
    }

    override fun trustedDevices(): List<TrustedDeviceInfo> =
        trustStore.listPeers().map { peer ->
            TrustedDeviceInfo(
                deviceId = peer.deviceId,
                displayName = peer.displayName,
                fingerprint = peer.fingerprint,
                pairedAt = peer.pairedAt,
                lastSeenAt = peer.lastSeenAt
            )
        }

    override fun startDiscovery(mode: DiscoveryMode): Flow<LanDiscoveryEvent> =
        LanDiscovery().startDiscovery()

    override suspend fun stopDiscovery() = Unit

    override suspend fun connectManually(host: String, port: Int, token: String?): Result<Unit> {
        val pairingClient = HttpClient(Darwin)
        // EPHEMERAL client keypair: generated below, discarded in finally so
        // it cannot outlive a single pairing attempt.
        var ephemeral: IosEcdhEphemeral? = null
        try {
            // Start pairing
            val infoResp = pairingClient.get("http://$host:$port${SyncEndpoints.PAIRING_START}")
            val info = json.decodeFromString<HostInfo>(infoResp.bodyAsText())

            // Contract section g: the host must advertise its STATIC P-256 key.
            // Fail closed when it is absent (pre-ECDH host) or malformed.
            val hostEcdhB64 = info.ecdhPublicKeyB64
                ?: return Result.failure(Exception("Host does not support ECDH pairing; upgrade the host"))
            val hostEcdhBytes = runCatching { base64Decode(hostEcdhB64) }.getOrNull()
            if (hostEcdhBytes == null ||
                hostEcdhBytes.size != PairingEcdh.PUBLIC_KEY_BYTES ||
                hostEcdhBytes[0] != PairingEcdh.UNCOMPRESSED_PREFIX
            ) {
                return Result.failure(Exception("Host ECDH public key is malformed"))
            }

            val clientEph = IosEcdh.generateEphemeral()
                ?: return Result.failure(Exception("Could not generate an ECDH key pair"))
            ephemeral = clientEph
            val clientEcdhPublicKeyB64 = clientEph.publicKeyB64()
                ?: return Result.failure(Exception("Could not export the ECDH public key"))

            // Complete pairing
            val tokenStr = token ?: return Result.failure(Exception("Pairing token required"))
            val verifyResp = pairingClient.post("http://$host:$port${SyncEndpoints.PAIRING_VERIFY}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PairingVerifyRequest(
                    token = tokenStr,
                    clientDeviceId = deviceId,
                    clientDeviceName = platformDeviceName(),
                    clientFingerprint = deviceFingerprint,
                    clientEcdhPublicKeyB64 = clientEcdhPublicKeyB64
                )))
            }
            val result = json.decodeFromString<PairingResultResponse>(verifyResp.bodyAsText())
            if (!result.success) return Result.failure(Exception(result.error ?: "Pairing failed"))

            // Resolve the secret ONLY from the ECDH-sealed field: agree
            // first, then unwrap. The legacy sharedSecret and encSecretB64
            // fields are never read. Absence fails CLOSED; the raw ECDH
            // output is normalized by unwrapSharedSecret (32-byte X or
            // 65-byte X9.63 forms from SecKeyCreateKeyExchange). Key
            // material is never logged.
            val sealedSecret = result.ecdhSecretB64
                ?: return Result.failure(Exception("Host does not support ECDH pairing; upgrade the host"))
            val rawShared = clientEph.agreeWith(hostEcdhBytes)
                ?: return Result.failure(Exception("ECDH key agreement failed; host key rejected"))
            val sharedSecret = try {
                PairingEcdh.unwrapSharedSecret(rawShared, sealedSecret)
            } catch (e: Exception) {
                return Result.failure(Exception("Could not unwrap the pairing secret"))
            }
            if (sharedSecret.isEmpty()) {
                return Result.failure(Exception("ECDH key agreement produced an empty secret"))
            }
            val hostId = result.hostDeviceId ?: return Result.failure(Exception("No host id returned"))
            trustStore.addPeer(
                IosDeviceTrustStore.IosTrustedPeer(
                    deviceId = hostId,
                    displayName = result.hostDeviceName ?: info.deviceName,
                    fingerprint = result.hostFingerprint ?: info.fingerprint,
                    sharedSecret = sharedSecret,
                    pairedAt = currentTimeMillis()
                )
            )

            // Prove the host knows the secret before syncing against it.
            // A fingerprint spoofed host cannot answer the challenge.
            val hostProven = verifyHostIdentity(host, port, deviceId, sharedSecret)
            if (!hostProven) {
                trustStore.revokeDevice(hostId)
                return Result.failure(Exception("Host identity challenge failed"))
            }

            return syncWith(DiscoveredPeer(
                deviceId = hostId,
                displayName = result.hostDeviceName ?: info.deviceName,
                host = host, port = port,
                isTrusted = true,
                fingerprint = result.hostFingerprint
            ))
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            ephemeral?.close()
            pairingClient.close()
        }
    }

    /**
     * Challenge the host at [host]:[port] to prove it knows [secret].
     * Sends a fresh random challenge to /auth/verify and checks the HMAC
     * response, same contract as the JVM client verifyHostIdentity.
     */
    suspend fun verifyHostIdentity(host: String, port: Int, callerDeviceId: String, secret: String): Boolean {
        val client = HttpClient(Darwin)
        return try {
            val challenge = generateNonce()
            val resp = client.get("http://$host:$port/auth/verify?deviceId=$callerDeviceId&challenge=$challenge")
            if (resp.status != HttpStatusCode.OK) return false
            val data = json.decodeFromString<HostChallengeResponse>(resp.bodyAsText())
            if (data.signature.isBlank()) return false
            if (kotlin.math.abs(currentTimeMillis() - data.timestamp) > SyncAuth.TIMESTAMP_WINDOW_MS) return false
            val expected = hmacSha256Hex(
                secret.encodeToByteArray(),
                "challenge:$callerDeviceId:${data.timestamp}:$challenge".encodeToByteArray()
            )
            constantTimeEquals(data.signature, expected)
        } catch (e: Exception) {
            false
        } finally {
            client.close()
        }
    }

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status.asStateFlow()

    fun dispose() {
        // dispose() is the only teardown hook this transport exposes (it is
        // not suspend, so the blocking stop is used, same shape as the JVM
        // KtorSyncServer.stop). Without this the listener survived
        // scope.cancel() and kept its port bound for the rest of the
        // process, which is what made a later restart impossible.
        val srv = server
        server = null
        if (srv != null) {
            try {
                srv.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
                Log.withTag("IosSync").i { "iOS sync server stopped (dispose)" }
            } catch (e: Exception) {
                Log.withTag("IosSync").e(e) { "dispose could not stop the sync server" }
            }
        }
        continuousSyncJobs.values.forEach { it.cancel() }
        continuousSyncJobs.clear()
        scope.cancel()
    }

    // ==========  Private helpers  ==========

    /**
     * Durability after every applied sync response (push acks and pull
     * pages): invoke the composition root's persistence callback when one is
     * supplied, otherwise run a self-built light save, exactly like the
     * hosting path does for accepted pushes. Without this a moved cursor
     * could outrun what is actually on disk.
     */
    private fun persistApplied() {
        val callback = persistAfterApply
        if (callback != null) callback() else JournalStore(repo).save(fullBackup = false)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    internal companion object {
        internal const val MAX_PAIRING_ATTEMPTS = 5
        internal const val PAIRING_RATE_WINDOW_MS = 120_000L
        internal const val PAIRING_TOKEN_TTL_MS = 120_000L

        /**
         * Wildcard bind address, also the address startHosting advertises.
         *
         * The JVM host advertises resolveLocalIpV4() instead (first site
         * local IPv4, then any non loopback address). That helper lives in
         * jvmMain only: there is no expect/actual local address API in this
         * project and no existing Network framework usage in iosMain to
         * build on, and the only remaining route (new C interop over
         * platform.darwin.getifaddrs plus sockaddr_in) cannot be compiled
         * on the host that maintains this file, so it is deliberately not
         * invented here. Bind stays on all interfaces; the address a user
         * pairs with is typed by hand.
         */
        private const val BIND_ADDRESS = "0.0.0.0"

        /** Grace period handed to the engine stop before connections are cut. */
        private const val SHUTDOWN_GRACE_MS = 1000L

        /** Hard cap on waiting for a graceful stop, so stopping cannot hang. */
        private const val SHUTDOWN_TIMEOUT_MS = 2000L
    }
}
