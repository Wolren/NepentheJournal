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
import app.journal.sync.base64Encode
import app.journal.sync.base64Decode
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
    private var hostingJob: Job? = null
    private val continuousSyncJobs = mutableMapOf<String, Job>()

    private val _status = MutableStateFlow(SyncStatusSnapshot(
        isHosting = false, hostAddress = null,
        activeConnections = emptyList(), lastSyncAt = null,
        pendingConflicts = 0, lastError = null
    ))
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    internal val identityStore = IosDeviceIdentityStore(dataDir)
    internal val trustStore = IosDeviceTrustStore(dataDir)
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
            val port = config.listenerPort
            val router = IosSyncServerRouter(
                repo = repo,
                trustStore = trustStore,
                pairingManager = pairingManager,
                nonceCache = nonceCache,
                deviceId = deviceId,
                deviceName = platformDeviceName(),
                fingerprint = deviceFingerprint,
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
            hostingJob = scope.launch {
                Log.withTag("IosSync").i { "Starting iOS sync server on port $port" }
                try {
                    embeddedServer(CIO, port = port) {
                        router.installRouting(this)
                    }.start(wait = false)
                    Log.withTag("IosSync").i { "iOS sync server started on :$port" }
                } catch (e: Exception) {
                    Log.withTag("IosSync").e(e) { "Failed to start iOS sync server" }
                }
            }
            _status.update { it.copy(isHosting = true, hostAddress = "0.0.0.0:$port") }
            Result.success(HostingInfo("0.0.0.0", port, deviceFingerprint))
        } catch (e: Exception) {
            Log.withTag("IosSync").e(e) { "startHosting failed: ${e.message}" }
            _status.update { it.copy(lastError = e.message) }
            Result.failure(e)
        }
    }

    override suspend fun stopHosting() {
        hostingJob?.cancel()
        hostingJob = null
        pairingManager.clearPendingPairing()
        _status.update { it.copy(isHosting = false, hostAddress = null, pairingToken = null) }
    }

    /** Pairing rate limiter keyed on socket level identity, 5 attempts per 120s window. */
    internal fun isPairingRateLimited(clientKey: String): Boolean = pairingAttemptsLock.withLock {
        val now = currentTimeMillis()
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
            val pushSince = _status.value.lastSyncAt ?: 0L
            val batch = buildSyncBatch(repo, deviceId, platformDeviceName(), pushSince)
            var pushResp: SyncResponse? = null
            if (batch != null) {
                val batchJson = json.encodeToString(batch)
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
                val syncResp = if (pushBody.isNotEmpty()) {
                    try {
                        val encryptedResp = base64Decode(pushBody)
                        val respJson = decryptBody(encryptedResp, aesKey)
                        runCatching { json.decodeFromString<SyncResponse>(respJson) }.getOrNull()
                    } catch (e: Exception) {
                        null
                    }
                } else null
                pushResp = syncResp
                if (syncResp != null) applySyncResponse(repo, syncResp, pushSince)
            }

            // Pull: sign the exact target including the since param, same as JVM.
            // Drain while truncated, following the low-water nextSince cursor.
            var pullCursor = if (pushResp?.truncated == true && pushResp.nextSince > 0L)
                pushResp.nextSince else (_status.value.lastSyncAt ?: 0L)
            var pullPages = 0
            while (pullPages < 20) {
                val pullResp = fetchPullPage(client, peer, deviceId, secret, aesKey, pullCursor)
                if (pullResp == null) break
                if (pullResp.success) applySyncResponse(repo, pullResp, pullCursor)
                if (!pullResp.truncated) break
                val next = if (pullResp.nextSince > 0L) pullResp.nextSince
                    else maxOf(pullResp.maxUpdatedAt(), pullCursor)
                if (next <= pullCursor) break
                pullCursor = next
                pullPages++
            }

            _status.update { it.copy(lastSyncAt = currentTimeMillis()) }
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
        try {
            // Start pairing
            val infoResp = pairingClient.get("http://$host:$port${SyncEndpoints.PAIRING_START}")
            val info = json.decodeFromString<HostInfo>(infoResp.bodyAsText())

            // Complete pairing
            val tokenStr = token ?: return Result.failure(Exception("Pairing token required"))
            val verifyResp = pairingClient.post("http://$host:$port${SyncEndpoints.PAIRING_VERIFY}") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(PairingVerifyRequest(
                    token = tokenStr,
                    clientDeviceId = deviceId,
                    clientDeviceName = platformDeviceName(),
                    clientFingerprint = deviceFingerprint
                )))
            }
            val result = json.decodeFromString<PairingResultResponse>(verifyResp.bodyAsText())
            if (!result.success) return Result.failure(Exception(result.error ?: "Pairing failed"))

            // C2 unwrap: try the encrypted field first (key derived from the
            // user-entered token and our own client deviceId), fall back to
            // the legacy plaintext field the server keeps populated.
            val sharedSecret = IosPairingSecretCrypto.resolveSecret(
                token = tokenStr,
                clientDeviceId = deviceId,
                encSecretB64 = result.encSecretB64,
                sharedSecret = result.sharedSecret
            ) ?: return Result.failure(Exception("No secret returned"))
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
        hostingJob?.cancel()
        continuousSyncJobs.values.forEach { it.cancel() }
        continuousSyncJobs.clear()
        scope.cancel()
    }

    // ==========  Private helpers  ==========

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
    }
}
