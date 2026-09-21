package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.log.Log
import app.journal.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.journal.sync.aesEncryptionKey
import app.journal.sync.base64Decode
import app.journal.sync.base64Encode
import app.journal.sync.decryptBody
import app.journal.sync.encryptBody
import java.util.concurrent.ConcurrentHashMap

class KtorSyncServer(
    private val repo: JournalRepository,
    private val port: Int,
    private val tlsIdentity: TlsIdentityManager,
    private val trustStore: DeviceTrustStore,
    private val authenticator: SyncAuthenticator,
    private val onConnection: (String) -> Unit,
    private val persistAfterApply: (() -> Unit)? = null
) {
    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null
    private var router: SyncServerRouter? = null

    private val fingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${fingerprint.take(16)}" }
    private val deviceName: String by lazy { platformDeviceName() }

    /** Port the server was configured to listen on. */
    val actualPort: Int get() = port

    suspend fun start(): HostingInfo = runInterruptible {
        try {
            val fp = fingerprint
            val name = deviceName

            val router = SyncServerRouter(
                repo = repo,
                trustStore = trustStore,
                authenticator = authenticator,
                onConnection = onConnection,
                deviceId = deviceId,
                deviceName = name,
                fingerprint = fp,
                persistAfterApply = persistAfterApply
            )
            this.router = router

            server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                router.installRouting(this)
            }

            server!!.start(wait = false)
            val lanIp = resolveLocalIpV4() ?: "127.0.0.1"
            Log.withTag("KtorSyncServer").i { "Server started on $lanIp:$port (fingerprint=$fp)" }
            HostingInfo(lanIp, port, fp)
        } catch (e: Exception) {
            Log.withTag("KtorSyncServer").e(e) { "Server start failed: ${e.message}" }
            throw e
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
        router = null
    }

    /**
     * Close every live server-side WebSocket session for [deviceId].
     * Called on revocation so a revoked device is dropped immediately,
     * including idle connections with no frames in flight (the per-frame
     * trust recheck covers connections with traffic).
     */
    suspend fun closeDeviceSessions(deviceId: String) {
        router?.closeDeviceSessions(deviceId)
    }

    val isRunning: Boolean get() = server != null
}

/**
 * Production sync server routing, extracted for testing with [io.ktor.server.testing.testApplication].
 * Use [install] inside [io.ktor.server.engine.embeddedServer] or [io.ktor.server.testing.testApplication].
 */
class SyncServerRouter(
    private val repo: JournalRepository,
    private val trustStore: DeviceTrustStore,
    private val authenticator: SyncAuthenticator,
    private val onConnection: (String) -> Unit,
    private val deviceId: String,
    private val deviceName: String,
    private val fingerprint: String,
    private val persistAfterApply: (() -> Unit)? = null
) {
    private val MAX_BODY_BYTES = 10L * 1024 * 1024 // 10 MB sync ceiling
    private val MAX_PAIRING_BODY_BYTES = 4096L // 4 KB pairing ceiling
    private val WS_MAX_FRAME_BYTES = 10L * 1024 * 1024
    private val WS_REORDER_WINDOW = 16L
    private val MAX_CHALLENGE_LEN = 128

    private val json = AppJson.json
    private val pairingAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

    // Per-IP throttles for the authenticated sync surface (buckets are
    // per-endpoint so a pull drain can never starve pairing, etc.).
    private val pushThrottle = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val pullThrottle = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val verifyThrottle = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val MAX_PUSH_PER_WINDOW = 120
    private val PUSH_WINDOW_MS = 60_000L
    private val MAX_PULL_PER_WINDOW = 200
    private val PULL_WINDOW_MS = 60_000L
    private val MAX_VERIFY_PER_WINDOW = 30
    private val VERIFY_WINDOW_MS = 60_000L

    /** Live server-side WS sessions per device, so revocation can drop them. */
    private val wsSessions = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    /** Per-connection WS sequence state: highest accepted seq plus a small reorder buffer. */
    private val wsSeqState = ConcurrentHashMap<String, WsSeqState>()

    private class WsSeqState {
        var highestSeq: Long = -1L
        val recent: java.util.LinkedHashSet<Long> = java.util.LinkedHashSet()
    }

    fun installRouting(app: Application) {
        app.install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = 10L * 1024 * 1024 // 10 MB max frame
        }

        app.routing {
            get("/info") {
                call.respondText(
                    json.encodeToString(HostInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        fingerprint = fingerprint,
                        protocolVersion = 2
                    )),
                    ContentType.Application.Json
                )
            }

            get("/pairing/start") {
                call.respondText(
                    json.encodeToString(HostInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        fingerprint = fingerprint,
                        protocolVersion = 2
                    )),
                    ContentType.Application.Json
                )
            }

            get("/auth/verify") {
                val clientIp = call.request.local.remoteHost
                evictStaleThrottle(verifyThrottle, VERIFY_WINDOW_MS)
                if (isThrottled(verifyThrottle, clientIp, MAX_VERIFY_PER_WINDOW, VERIFY_WINDOW_MS)) {
                    warnAuth(call, null, "/auth/verify", "rate limited")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val deviceIdParam = call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER]
                    ?: call.request.queryParameters["deviceId"]
                val challengeRaw = call.request.headers["X-Sync-Challenge"]
                    ?: call.request.queryParameters["challenge"]
                val challenge = challengeRaw?.take(MAX_CHALLENGE_LEN)
                // Uniform failure code: missing params, overlong challenges,
                // and unknown devices all return 401 with the same body, so
                // the endpoint is not a device-existence oracle.
                if (deviceIdParam.isNullOrBlank() || challenge.isNullOrBlank()) {
                    warnAuth(call, deviceIdParam, "/auth/verify", "missing device or challenge")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                if (challengeRaw != null && challengeRaw.length > MAX_CHALLENGE_LEN) {
                    warnAuth(call, deviceIdParam, "/auth/verify", "challenge over 128 chars")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val secret = trustStore.getSharedSecret(deviceIdParam)
                if (secret == null) {
                    warnAuth(call, deviceIdParam, "/auth/verify", "unknown device")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                // Prove knowledge of the shared secret without revealing it.
                // Bound to a fresh client-supplied challenge, so a captured
                // response cannot be replayed against a different challenge.
                val timestamp = System.currentTimeMillis()
                val signature = authenticator.signChallenge(deviceIdParam, timestamp, challenge, secret)
                call.respondText(
                    json.encodeToString(HostChallengeResponse(timestamp, signature)),
                    ContentType.Application.Json
                )
            }

            post("/pairing/verify") {
                // Socket-level remote address. X-Forwarded-For is deliberately
                // NOT trusted: this server is directly reachable on the LAN with
                // no proxy, so the header is client-controlled and would let an
                // attacker rotate the rate-limit bucket per request.
                // Verified empirically on Ktor 3.5.1 Netty: local.remoteHost
                // carries the peer address (the 0.0.0.0 bind is NOT returned).
                val clientIp = call.request.local.remoteHost
                evictStaleBuckets()
                if (isRateLimited(clientIp)) {
                    warnAuth(call, null, "/pairing/verify", "rate limited")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Too many attempts. Try again later.")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }

                if (!hasValidContentLength(call, MAX_PAIRING_BODY_BYTES)) {
                    warnAuth(call, null, "/pairing/verify", "pairing body missing length or over 4KB")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Body too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }
                val bodyText = call.receiveText()
                if (bodyText.length > 4096) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Body too large")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                val verifyReq = try {
                    json.decodeFromString<PairingVerifyRequest>(bodyText)
                } catch (e: Exception) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid request")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                // Cap identity fields so a client cannot register oversized
                // display names / ids in the trust store (4KB body allows it).
                if (verifyReq.clientDeviceId.length > 128 ||
                    verifyReq.clientDeviceName.length > 200 ||
                    verifyReq.clientFingerprint.length > 128
                ) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid client identity")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                if (!authenticator.verifyPairingToken(verifyReq.token)) {
                    warnAuth(call, verifyReq.clientDeviceId, "/pairing/verify", "invalid or expired token")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid or expired token")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@post
                }

                // One shared secret for this client — store it and return it
                val sharedSecret = authenticator.generateSharedSecret()
                val clientDeviceId = verifyReq.clientDeviceId.ifBlank {
                    "client-${verifyReq.clientFingerprint.take(8)}"
                }

                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                    deviceId = clientDeviceId,
                    displayName = verifyReq.clientDeviceName.ifBlank { clientDeviceId },
                    fingerprint = verifyReq.clientFingerprint,
                    sharedSecret = sharedSecret,
                    pairedAt = System.currentTimeMillis()
                ))

                // Also ensure the host has its own record (same shared secret or separate)
                hostSecret() // ensures host peer exists

                // C2 wrap: the same secret encrypted under a key derived from
                // the pairing token, so a LAN observer of this response learns
                // nothing. The legacy field stays populated this wave.
                val encSecretB64 = try {
                    SyncAuthenticator.encryptPairingSecret(verifyReq.token, clientDeviceId, sharedSecret)
                } catch (e: Exception) {
                    Log.withTag("KtorSyncServer").w { "pairing secret wrap failed, sending legacy field only" }
                    null
                }

                call.respondText(
                    json.encodeToString(PairingResultResponse(
                        success = true,
                        deviceId = clientDeviceId,
                        sharedSecret = sharedSecret,
                        encSecretB64 = encSecretB64,
                        hostDeviceId = deviceId,
                        hostDeviceName = deviceName,
                        hostFingerprint = fingerprint
                    )),
                    ContentType.Application.Json
                )
                authenticator.clearPendingPairing()
                onConnection("Paired with ${verifyReq.clientDeviceName}")
            }

            post("/sync/push") {
                val pushIp = call.request.local.remoteHost
                evictStaleThrottle(pushThrottle, PUSH_WINDOW_MS)
                if (isThrottled(pushThrottle, pushIp, MAX_PUSH_PER_WINDOW, PUSH_WINDOW_MS)) {
                    warnAuth(call, null, "/sync/push", "rate limited")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }
                // Declared length is enforced BEFORE the body is buffered:
                // missing, chunked, or oversized bodies are refused unread.
                if (!hasValidContentLength(call, MAX_BODY_BYTES)) {
                    warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], "/sync/push", "missing or oversized content length")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }
                val auth = verifyRequest(call)
                if (auth == null) {
                    warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], "/sync/push", "authentication failed")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val (callerDeviceId, encryptedBody) = auth

                if (encryptedBody.length > SyncAuthenticator.MAX_SYNC_BODY_BYTES) {
                    warnAuth(call, callerDeviceId, "/sync/push", "payload too large")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }

                // Decrypt the encrypted body before processing
                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    warnAuth(call, callerDeviceId, "/sync/push", "unknown device")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Unknown device; re-pair required")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@post
                }
                val aesKey = aesEncryptionKey(callerSecret)
                val body = try {
                    decryptBody(base64Decode(encryptedBody), aesKey)
                } catch (e: Exception) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Body decryption failed: ${e.message}")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
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

                // The batch must identify the authenticated caller. Otherwise a
                // paired device could attribute writes / conflict notes to
                // another device (audit L8).
                if (batch.deviceId != callerDeviceId) {
                    warnAuth(call, callerDeviceId, "/sync/push", "device ID mismatch")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Device ID mismatch")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
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

                handlePush(batch)
                trustStore.updateLastSeen(callerDeviceId)
                // Durability: persist before acknowledging, so a crash after the
                // response cannot lose data the client believes was accepted.
                // The client advances its sync cursor on a successful response,
                // so an unpersisted ack would lose the pushed data forever.
                persistAfterApply?.invoke()
                val exchangeResponse = handlePull(batch.since)
                // Encrypt the response: encryptBody + base64Encode
                val encryptedResponse = base64Encode(encryptBody(
                    json.encodeToString(exchangeResponse), aesKey
                ))
                call.respondText(encryptedResponse, ContentType.Application.Json)
            }

            get("/sync/pull") {
                val pullIp = call.request.local.remoteHost
                evictStaleThrottle(pullThrottle, PULL_WINDOW_MS)
                if (isThrottled(pullThrottle, pullIp, MAX_PULL_PER_WINDOW, PULL_WINDOW_MS)) {
                    warnAuth(call, null, "/sync/pull", "rate limited")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val auth = verifyRequest(call)
                if (auth == null) {
                    warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], "/sync/pull", "authentication failed")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val (callerDeviceId, _) = auth

                val sinceStr = call.request.queryParameters["since"]
                val since = sinceStr?.toLongOrNull() ?: 0L
                if (since < 0 || since > System.currentTimeMillis() + 86_400_000L) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Invalid since")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@get
                }

                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    warnAuth(call, callerDeviceId, "/sync/pull", "unknown device")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Unknown device; re-pair required")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@get
                }

                val response = handlePull(since)
                trustStore.updateLastSeen(callerDeviceId)
                val aesKey = aesEncryptionKey(callerSecret)
                val encryptedResponse = base64Encode(encryptBody(
                    json.encodeToString(response), aesKey
                ))
                call.respondText(encryptedResponse, ContentType.Application.Json)
            }

            webSocket("/sync/ws") {
                // Header-only auth: query strings leak into logs, caches, and
                // history. Older query-based clients are rejected, not
                // downgraded.
                val callerDeviceId = call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER]
                val authHeader = call.request.headers[SyncAuthenticator.AUTH_HEADER]
                if (callerDeviceId == null || authHeader == null) {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Missing deviceId or auth"))
                    return@webSocket
                }
                if (!trustStore.isTrustedDeviceId(callerDeviceId)) {
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Unknown device"))
                    return@webSocket
                }
                if (!authenticator.verifyRequest(callerDeviceId, "ws", authHeader)) {
                    Log.withTag("KtorSyncServer").w { "WS auth failed peer=$callerDeviceId endpoint=/sync/ws" }
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Authentication failed"))
                    return@webSocket
                }

                // Derive AES key for decrypting WsDelta frames. Once keyed,
                // plaintext deltas are refused: every WsDelta frame must be
                // AES-GCM ciphertext (base64 of encryptBody output).
                val wsAesKey = trustStore.getSharedSecret(callerDeviceId)?.let { aesEncryptionKey(it) }
                if (wsAesKey == null) {
                    Log.withTag("KtorSyncServer").w { "WS keyed check failed peer=$callerDeviceId endpoint=/sync/ws" }
                    close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Unknown device"))
                    return@webSocket
                }

                // Track this live session so revocation can drop it immediately.
                // Indentation inside the try is intentionally flat: Kotlin does
                // not care, and this keeps the diff reviewable.
                val serverSession: DefaultWebSocketServerSession = this
                wsSessions.getOrPut(callerDeviceId) { ConcurrentHashMap.newKeySet() }.add(serverSession)
                try {
                for (frame in incoming) {
                    // Revocation takes effect on live connections: a device
                    // revoked mid-session is closed on its next frame, and
                    // closeDeviceSessions handles idle connections.
                    if (!trustStore.isTrustedDeviceId(callerDeviceId)) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device revoked"))
                        return@webSocket
                    }
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        if (text.length > WS_MAX_FRAME_BYTES) {
                            outgoing.send(Frame.Text(
                                wsJson.encodeToString(WsMessage.serializer(), WsAck(0, error = "Frame too large"))
                            ))
                            continue
                        }
                        val msg = tryDecryptWsMessage(text, wsAesKey)
                        if (msg == null) {
                            outgoing.send(Frame.Text(
                                wsJson.encodeToString(WsMessage.serializer(), WsAck(0, error = "Malformed frame"))
                            ))
                            continue
                        }
                        // WsDelta frames must arrive encrypted. tryDecryptWsMessage
                        // only returns a delta when AES-GCM decryption succeeded;
                        // plaintext on a keyed connection yields null above.
                        when (msg) {
                            is WsDelta -> {
                                if (!checkWsSeq(callerDeviceId, msg.seq)) {
                                    outgoing.send(Frame.Text(
                                        wsJson.encodeToString(WsMessage.serializer(), WsAck(seq = msg.seq, error = "Stale or replayed seq"))
                                    ))
                                    continue
                                }
                                val validationError = validateWsDelta(msg)
                                if (validationError != null) {
                                    outgoing.send(Frame.Text(
                                        wsJson.encodeToString(WsMessage.serializer(), WsAck(seq = msg.seq, error = validationError))
                                    ))
                                    continue
                                }
                                repo.applyBatch(
                                    sessions = msg.sessions,
                                    doses = msg.doses,
                                    substances = msg.substances,
                                    effects = msg.effects,
                                    interactions = msg.interactions,
                                    notes = msg.notes,
                                    timelineEvents = msg.timelineEvents,
                                    customUnits = msg.customUnits,
                                    lastWriterWins = true,
                                    deletedSessionIds = msg.deletedSessionIds,
                                    deletedDoseIds = msg.deletedDoseIds,
                                    deletedNoteIds = msg.deletedNoteIds,
                                    deletedSubstanceIds = msg.deletedSubstanceIds,
                                    deletedEffectIds = msg.deletedEffectIds,
                                    deletedInteractionIds = msg.deletedInteractionIds,
                                    deletedTimelineEventIds = msg.deletedTimelineEventIds,
                                    deletedCustomUnitIds = msg.deletedCustomUnitIds,
                                    tombstoneCutoff = 0L
                                )
                                // Persist before acking the delta (same rule as
                                // the push route; see audit D1).
                                persistAfterApply?.invoke()
                                outgoing.send(Frame.Text(
                                    wsJson.encodeToString(WsMessage.serializer(), WsAck(seq = msg.seq))
                                ))
                                trustStore.updateLastSeen(callerDeviceId)
                            }
                            is WsPing -> {
                                outgoing.send(Frame.Text(wsJson.encodeToString(WsMessage.serializer(), WsPong(msg.seq))))
                            }
                            is WsPong -> { /* ignore */ }
                            is WsAck -> { /* ignore */ }
                        }
                    }
                }
                } finally {
                    wsSessions[callerDeviceId]?.remove(serverSession)
                }
            }
        }
    }

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        // Atomic read-modify-write: ConcurrentHashMap.compute prevents TOCTOU between
        // reading the current count and writing the updated value.
        val state = pairingAttempts.compute(clientIp) { _, current ->
            val (count, windowStart) = current ?: Pair(0, now)
            if (now - windowStart > 120_000) Pair(1, now) // new window
            else Pair(count + 1, windowStart)
        } ?: Pair(1, now)
        return state.first > 5
    }

    /** Drop rate-limit buckets whose window expired so idle IPs never accumulate. */
    private fun evictStaleBuckets() {
        val now = System.currentTimeMillis()
        pairingAttempts.entries.removeIf { now - it.value.second > 120_000 }
    }

    /**
     * Drop entries of a per-endpoint throttle whose window expired.
     * Generic form of [evictStaleBuckets] for the push/pull/verify maps.
     */
    private fun evictStaleThrottle(
        throttle: ConcurrentHashMap<String, Pair<Int, Long>>,
        windowMs: Long
    ) {
        val now = System.currentTimeMillis()
        throttle.entries.removeIf { now - it.value.second > windowMs }
    }

    /**
     * Generic per-IP throttle. Returns true when [ip] exceeded [max] requests
     * in the current window. Atomic via ConcurrentHashMap.compute, same as
     * the pairing limiter. Allows exactly [max], blocks the max+1th.
     */
    private fun isThrottled(
        throttle: ConcurrentHashMap<String, Pair<Int, Long>>,
        ip: String,
        max: Int,
        windowMs: Long
    ): Boolean {
        val now = System.currentTimeMillis()
        val state = throttle.compute(ip) { _, current ->
            val (count, windowStart) = current ?: Pair(0, now)
            if (now - windowStart > windowMs) Pair(1, now) // new window
            else Pair(count + 1, windowStart)
        } ?: Pair(1, now)
        return state.first > max
    }

    /**
     * Strict Content-Length pre-check: the request is valid only when it
     * declares a length AND it fits in [maxBytes]. Missing or chunked bodies
     * are refused unread, since Ktor receiveText has no size cap and would
     * otherwise buffer an unbounded body before any check runs.
     */
    private fun hasValidContentLength(call: ApplicationCall, maxBytes: Long): Boolean {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return false
        return declared in 1..maxBytes
    }

    /**
     * Close every live server-side WebSocket session for [deviceId].
     * Called on revocation so a revoked device drops immediately, including
     * idle connections with no frames in flight (the per-frame trust recheck
     * in the WS route covers connections with traffic).
     */
    suspend fun closeDeviceSessions(deviceId: String) {
        val sessions = wsSessions.remove(deviceId) ?: return
        for (session in sessions.toList()) {
            try {
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device revoked"))
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Pre-check Content-Length before reading the body: Ktor receiveText has
     * no default size limit, so refuse oversized requests early instead of
     * buffering them. Returns false when the request must be rejected.
     */
    private fun checkContentLength(call: ApplicationCall, maxBytes: Long): Boolean {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return true
        return declared <= maxBytes
    }

    /**
     * Warn log for auth failures. Includes peer IP, device ID, endpoint, and
     * reason. Never logs secrets, tokens, auth headers, or bodies.
     */
    private fun warnAuth(call: ApplicationCall, deviceId: String?, endpoint: String, reason: String) {
        val peer = try { call.request.local.remoteHost } catch (_: Exception) { "unknown" }
        val safeDevice = deviceId?.take(64) ?: "unknown"
        Log.withTag("KtorSyncServer").w { "auth denied peer=$peer device=$safeDevice endpoint=$endpoint reason=$reason" }
    }

    private suspend fun verifyRequest(call: ApplicationCall): Pair<String, String>? {
        val callerDeviceId = call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER] ?: return null
        val authHeader = call.request.headers[SyncAuthenticator.AUTH_HEADER] ?: return null
        if (!trustStore.isTrustedDeviceId(callerDeviceId)) return null

        val body = when (call.request.httpMethod.value) {
            "POST" -> call.receiveText()
            "GET" -> call.request.uri
            else -> return null
        }

        return if (authenticator.verifyRequest(callerDeviceId, body, authHeader)) {
            Pair(callerDeviceId, body)
        } else null
    }

    private fun handlePush(batch: SyncBatch) {
        var conflicts = 0
        val tagged = batch.copy(deviceName = batch.deviceName.take(200))
        repo.applyBatch(
            substances = tagged.substances,
            doses = tagged.doses,
            interactions = tagged.interactions,
            timelineEvents = tagged.timelineEvents,
            effects = tagged.effects,
            customUnits = tagged.customUnits,
            lastWriterWins = true,
            deletedSessionIds = tagged.deletedSessionIds,
            deletedDoseIds = tagged.deletedDoseIds,
            deletedNoteIds = tagged.deletedNoteIds,
            deletedSubstanceIds = tagged.deletedSubstanceIds,
            deletedEffectIds = tagged.deletedEffectIds,
            deletedInteractionIds = tagged.deletedInteractionIds,
            deletedTimelineEventIds = tagged.deletedTimelineEventIds,
            deletedCustomUnitIds = tagged.deletedCustomUnitIds,
            tombstoneCutoff = batch.since
        )
        tagged.sessions.forEach { session ->
            if (session.id in tagged.deletedSessionIds) return@forEach
            val existing = repo.getSession(session.id)
            if (existing != null && existing.updatedAt > session.updatedAt) {
                repo.upsertNote(Note(
                    id = "conflict:${session.id}:${tagged.deviceId}",
                    sessionId = session.id,
                    title = "Sync conflict — ${session.title}",
                    body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                    createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    // Tag interaction provenance: conflict notes always carry
                    // the pushing device so the origin is never ambiguous.
                    deviceOrigin = "sync:${tagged.deviceId}"
                ))
                conflicts++
            } else repo.upsertSession(session.copy(deviceOrigin = session.deviceOrigin.ifBlank { "sync:${tagged.deviceId}" }))
        }
        tagged.notes.forEach { note ->
            if (note.id in tagged.deletedNoteIds) return@forEach
            val resolved = repo.upsertNoteWithConflict(note, tagged.deviceId)
            if (resolved != null && resolved.conflictSiblings.isNotEmpty()) conflicts++
        }
        onConnection(if (conflicts > 0) "$conflicts conflict(s)" else "Synced from ${tagged.deviceName}")
    }

    /**
     * Decode one WS frame. Ping/pong/ack frames are accepted in the clear
     * (they carry no entity data); WsDelta frames are ONLY accepted as
     * AES-GCM ciphertext, never as plaintext, once the connection is keyed.
     */
    private fun tryDecryptWsMessage(text: String, key: ByteArray): WsMessage? {
        // Encrypted path first: base64 AES-GCM of the polymorphic JSON.
        try {
            val plaintext = decryptBody(base64Decode(text), key)
            val msg = wsJson.decodeFromString<WsMessage>(plaintext)
            return msg
        } catch (_: Exception) {
            Log.withTag("KtorSyncServer").d { "WS frame is not decryptable ciphertext, trying cleartext control frames" }
        }
        // Cleartext fallback for control frames only. A plaintext WsDelta is
        // refused (returns null): on a keyed connection every delta must be
        // encrypted, otherwise a LAN observer could inject unsigned batches.
        return try {
            when (val msg = wsJson.decodeFromString<WsMessage>(text)) {
                is WsPing, is WsPong, is WsAck -> msg
                is WsDelta -> {
                    Log.withTag("KtorSyncServer").d { "WS cleartext delta refused, deltas must be encrypted" }
                    null
                }
            }
        } catch (_: Exception) { null }
    }

    /**
     * Enforce a strictly rising sequence per device with a small reorder
     * window: accepts seq values above the highest seen, plus up to
     * WS_REORDER_WINDOW recent lower values once each (covers reordered
     * delivery); rejects replays and stale frames.
     */
    private fun checkWsSeq(deviceId: String, seq: Long): Boolean {
        if (seq < 0) return false
        val state = wsSeqState.computeIfAbsent(deviceId) { WsSeqState() }
        synchronized(state) {
            if (seq > state.highestSeq) {
                // Slide the window forward, remembering skipped values.
                var s = state.highestSeq + 1
                while (s < seq) {
                    state.recent.add(s)
                    s++
                    while (state.recent.size > WS_REORDER_WINDOW.toInt()) {
                        state.recent.remove(state.recent.iterator().next())
                    }
                }
                state.highestSeq = seq
                state.recent.remove(seq)
                return true
            }
            // Within the reorder window and not seen before: accept once.
            if (seq > state.highestSeq - WS_REORDER_WINDOW && state.recent.remove(seq)) {
                return true
            }
            return false
        }
    }

    private fun handlePull(since: Long): SyncResponse {
        val deleted = repo.deletedIdsSince(since)
        // Oldest-first pages with a low-water nextSince: dropping the newest
        // (takeLast) would skip the dropped entities forever once the client
        // advances its cursor past them.
        val lowWater = mutableListOf<Long>()
        var truncated = false
        fun <T> page(items: List<T>, max: Int, updatedAt: (T) -> Long): List<T> {
            val fresh = items.filter { updatedAt(it) > since }.sortedBy(updatedAt)
            if (fresh.size <= max) return fresh
            truncated = true
            val cut = fresh.take(max)
            lowWater.add(cut.maxOf(updatedAt))
            return cut
        }
        return SyncResponse(
        success = true,
        sessions = page(repo.sessions.value, MAX_ITEMS_DEFAULT) { it.updatedAt },
        doses = page(repo.doses.value, MAX_ITEMS_DEFAULT) { it.updatedAt },
        substances = page(repo.substances.value, MAX_SUBSTANCES) { it.updatedAt },
        interactions = page(repo.interactions.value, MAX_INTERACTIONS) { it.updatedAt },
        notes = page(repo.notes.value, MAX_ITEMS_DEFAULT) { it.updatedAt },
        timelineEvents = page(repo.timelineEvents.value, MAX_ITEMS_DEFAULT) { it.updatedAt },
        effects = page(repo.effects.value, MAX_EFFECTS) { it.updatedAt },
        customUnits = page(repo.customUnits.value, MAX_CUSTOM_UNITS) { it.updatedAt },
        deletedSessionIds = deleted.deletedSessionIds,
        deletedDoseIds = deleted.deletedDoseIds,
        deletedNoteIds = deleted.deletedNoteIds,
        deletedSubstanceIds = deleted.deletedSubstanceIds,
        deletedEffectIds = deleted.deletedEffectIds,
        deletedInteractionIds = deleted.deletedInteractionIds,
        deletedTimelineEventIds = deleted.deletedTimelineEventIds,
        deletedCustomUnitIds = deleted.deletedCustomUnitIds,
        conflictsCreated = repo.notes.value.count { it.conflictSiblings.isNotEmpty() },
        truncated = truncated,
        nextSince = if (truncated) lowWater.min() else 0L
    )
    }

    /** Ensure the host's own peer record exists in the trust store. */
    private fun hostSecret(): String {
        return trustStore.getSharedSecret(deviceId)
            ?: authenticator.generateSharedSecret().also {
                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                    deviceId = deviceId,
                    displayName = deviceName,
                    fingerprint = fingerprint,
                    sharedSecret = it,
                    pairedAt = System.currentTimeMillis()
                ))
            }
    }
}
