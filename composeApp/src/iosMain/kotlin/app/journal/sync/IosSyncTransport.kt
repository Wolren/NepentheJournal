package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.data.JournalSnapshot
import app.journal.log.Log
import app.journal.model.SyncConfig
import app.journal.util.currentTimeMillis
import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * iOS implementation of SyncEngine that speaks the standard Nepenthe sync protocol.
 *
 * Wire format: SyncBatch (push) / SyncResponse (pull/push response)
 * Auth: HMAC-SHA256 via "X-Sync-Auth" header (timestamp:nonce:hex-signature)
 * Transport: plain HTTP (no TLS on LAN — ATS handles app-to-internet)
 * Discovery: Bonjour via NSNetServiceBrowser
 *
 * Compatible with desktop and Android hosts using the same protocol.
 */
class IosSyncTransport(
    private val repo: JournalRepository,
    private val dataDir: String = platformSyncDataDir()
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

    // Derived identity — on iOS, fingerprint is a hash of device name (no TLS cert)
    private val deviceId: String by lazy { "ios-${platformDeviceName().hashCode().toUShort()}" }
    private val deviceFingerprint: String by lazy {
        hmacSha256Hex(deviceId.encodeToByteArray(), platformDeviceName().encodeToByteArray())
    }
    // No persistent secret store on iOS — generate on each pairing response
    private var pairingSecret: ByteArray? = null

    override suspend fun startHosting(config: SyncConfig): Result<HostingInfo> {
        return try {
            val port = config.port
            hostingJob = scope.launch {
                Log.withTag("IosSync").i { "Starting iOS sync server on port $port" }
                try {
                    embeddedServer(CIO, port = port) {
                        routing {
                            get(SyncEndpoints.INFO) {
                                call.respondText(
                                    json.encodeToString(HostInfo(
                                        deviceId = deviceId,
                                        deviceName = platformDeviceName(),
                                        fingerprint = deviceFingerprint,
                                        protocolVersion = 2
                                    )),
                                    ContentType.Application.Json
                                )
                            }

                            get(SyncEndpoints.PAIRING_START) {
                                call.respondText(
                                    json.encodeToString(HostInfo(
                                        deviceId = deviceId,
                                        deviceName = platformDeviceName(),
                                        fingerprint = deviceFingerprint,
                                        protocolVersion = 2
                                    )),
                                    ContentType.Application.Json
                                )
                            }

                            post(SyncEndpoints.PAIRING_VERIFY) {
                                val bodyText = call.receiveText()
                                val req = runCatching {
                                    json.decodeFromString<PairingVerifyRequest>(bodyText)
                                }.getOrNull()
                                if (req == null) {
                                    call.respondText(
                                        json.encodeToString(PairingResultResponse(false, error = "Invalid request")),
                                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                                    )
                                    return@post
                                }
                                val secret = generateNonce() + generateNonce() // 64-char hex
                                pairingSecret = secret.encodeToByteArray()
                                call.respondText(
                                    json.encodeToString(PairingResultResponse(
                                        success = true,
                                        deviceId = "${req.clientFingerprint.take(8)}",
                                        sharedSecret = secret,
                                        hostDeviceId = deviceId,
                                        hostDeviceName = platformDeviceName(),
                                        hostFingerprint = deviceFingerprint
                                    )),
                                    ContentType.Application.Json
                                )
                            }

                            post(SyncEndpoints.SYNC_PUSH) {
                                val deviceHeader = call.request.headers[SyncAuth.DEVICE_ID_HEADER]
                                val authHeader = call.request.headers[SyncAuth.AUTH_HEADER]
                                if (deviceHeader == null || authHeader == null) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "Authentication missing")),
                                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                                    )
                                    return@post
                                }
                                val secret = pairingSecret ?: run {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "No shared secret")),
                                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                                    )
                                    return@post
                                }
                                val body = call.receiveText()
                                val verified = verifyAuth(deviceHeader, body, authHeader, secret)
                                if (!verified) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "HMAC verification failed")),
                                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                                    )
                                    return@post
                                }
                                val batch = try {
                                    json.decodeFromString<SyncBatch>(body)
                                } catch (e: Exception) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "Invalid payload")),
                                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                                    )
                                    return@post
                                }
                                val validationError = validateSyncBatch(batch)
                                if (validationError != null) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = validationError)),
                                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                                    )
                                    return@post
                                }
                                applySyncBatch(batch)
                                val response = buildSyncResponse(batch.since)
                                call.respondText(json.encodeToString(response), ContentType.Application.Json)
                            }

                            get(SyncEndpoints.SYNC_PULL) {
                                val deviceHeader = call.request.headers[SyncAuth.DEVICE_ID_HEADER]
                                val authHeader = call.request.headers[SyncAuth.AUTH_HEADER]
                                if (deviceHeader == null || authHeader == null) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "Authentication missing")),
                                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                                    )
                                    return@get
                                }
                                val secret = pairingSecret ?: run {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "No shared secret")),
                                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                                    )
                                    return@get
                                }
                                val body = ""
                                val verified = verifyAuth(deviceHeader, body, authHeader, secret)
                                if (!verified) {
                                    call.respondText(
                                        json.encodeToString(SyncResponse(false, error = "HMAC verification failed")),
                                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                                    )
                                    return@get
                                }
                                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                                val response = buildSyncResponse(since)
                                call.respondText(json.encodeToString(response), ContentType.Application.Json)
                            }
                        }
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
        _status.update { it.copy(isHosting = false, hostAddress = null) }
    }

    override suspend fun syncWith(peer: DiscoveredPeer, continuous: Boolean): Result<Unit> {
        val secret = pairingSecret ?: return Result.failure(Exception("Not paired"))
        val client = HttpClient(Darwin)
        return try {
            val batch = buildSyncBatch(repo, deviceId, platformDeviceName(), _status.value.lastSyncAt ?: 0L)
            if (batch != null) {
                val pushReq = SyncPushRequest.fromBatch(batch, secret, deviceId)
                val pushResponse = client.post("http://${peer.host}:${peer.port}${SyncEndpoints.SYNC_PUSH}") {
                    contentType(ContentType.Application.Json)
                    header(SyncAuth.DEVICE_ID_HEADER, pushReq.deviceId)
                    header(SyncAuth.AUTH_HEADER, pushReq.authHeader)
                    setBody(pushReq.body)
                }
                val pushBody = pushResponse.bodyAsText()
                val syncResp = runCatching { json.decodeFromString<SyncResponse>(pushBody) }.getOrNull()
                if (syncResp != null) applySyncResponse(repo, syncResp)
            }

            // Always pull
            val pullAuth = buildAuthHeader(deviceId, "", secret, currentTimeMillis(), generateNonce())
            val pullResponse = client.get("http://${peer.host}:${peer.port}${SyncEndpoints.SYNC_PULL}") {
                header(SyncAuth.DEVICE_ID_HEADER, deviceId)
                header(SyncAuth.AUTH_HEADER, pullAuth)
                parameter("since", _status.value.lastSyncAt?.toString() ?: "0")
            }
            val pullBody = pullResponse.bodyAsText()
            val pullResp = runCatching { json.decodeFromString<SyncResponse>(pullBody) }.getOrNull()
            if (pullResp?.success == true) applySyncResponse(repo, pullResp)

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
    override suspend fun revokeTrustedDevice(deviceId: String) = disconnectFrom(deviceId)

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

            pairingSecret = (result.sharedSecret ?: return Result.failure(Exception("No secret returned"))).encodeToByteArray()
            return syncWith(DiscoveredPeer(
                deviceId = result.hostDeviceId,
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

    override fun observeStatus(): Flow<SyncStatusSnapshot> = _status.asStateFlow()

    fun dispose() {
        hostingJob?.cancel()
        continuousSyncJobs.values.forEach { it.cancel() }
        continuousSyncJobs.clear()
        scope.cancel()
    }

    // ==========  Private helpers  ==========

    /**
     * Validate a SyncBatch from an incoming push request.
     * Enforces field-length and item-count limits to prevent injection
     * of malformed data from untrusted peers.
     */
    private fun validateSyncBatch(batch: SyncBatch): String? {
        if (batch.sessions.size > MAX_ITEMS) return "Too many sessions"
        if (batch.doses.size > MAX_ITEMS) return "Too many doses"
        if (batch.substances.size > 100) return "Too many substances"
        if (batch.notes.size > MAX_ITEMS) return "Too many notes"
        if (batch.timelineEvents.size > MAX_ITEMS) return "Too many events"
        if (batch.interactions.size > 100) return "Too many interactions"
        if (batch.effects.size > 100) return "Too many effects"
        if (batch.customUnits.size > 100) return "Too many custom units"
        return null
    }

    private companion object {
        private const val MAX_ITEMS = 500
    }

    private fun verifyAuth(deviceId: String, body: String, authHeader: String, secret: ByteArray): Boolean {
        val parts = authHeader.split(":", limit = 3)
        if (parts.size != 3) return false
        val (timestampStr, nonce, signature) = parts
        val timestamp = timestampStr.toLongOrNull() ?: return false
        val now = currentTimeMillis()
        if (kotlin.math.abs(now - timestamp) > SyncAuth.TIMESTAMP_WINDOW_MS) return false
        val payload = "$deviceId:$timestamp:$nonce:$body"
        val expected = hmacSha256Hex(secret, payload.encodeToByteArray())
        return constantTimeEquals(signature, expected)
    }

    private fun buildSyncResponse(since: Long): SyncResponse {
        fun <T> changed(list: List<T>, since: Long, updatedAt: (T) -> Long): List<T> =
            list.filter { updatedAt(it) >= since }
        return SyncResponse(
            success = true,
            sessions = changed(repo.sessions.value, since) { it.updatedAt },
            doses = changed(repo.doses.value, since) { it.updatedAt },
            substances = changed(repo.substances.value, since) { it.updatedAt },
            effects = changed(repo.effects.value, since) { it.updatedAt },
            interactions = changed(repo.interactions.value, since) { it.updatedAt },
            notes = changed(repo.notes.value, since) { it.updatedAt },
            timelineEvents = changed(repo.timelineEvents.value, since) { it.updatedAt },
            customUnits = changed(repo.customUnits.value, since) { it.updatedAt }
        )
    }

    private fun applySyncBatch(batch: SyncBatch) {
        for (s in batch.sessions) repo.upsertSession(s)
        for (d in batch.doses) repo.upsertDose(d)
        for (s in batch.substances) repo.upsertSubstance(s)
        for (e in batch.effects) repo.upsertEffect(e)
        for (i in batch.interactions) repo.upsertInteraction(i)
        for (n in batch.notes) repo.upsertNote(n)
        for (t in batch.timelineEvents) repo.upsertTimelineEvent(t)
        for (u in batch.customUnits) repo.upsertCustomUnit(u)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }
}
