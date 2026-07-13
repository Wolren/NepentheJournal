package app.journal.sync

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Plain-HTTP plus HMAC-authenticated sync server.
 *
 * All data-changing endpoints require HMAC-SHA256 signed requests via
 * [SyncAuthenticator]. No TLS — on a LAN the HMAC provides message
 * integrity and authentication between devices that already share a
 * secret established during pairing.
 *
 * Pairing endpoint has per-IP rate limiting to prevent token brute force.
 *
 * Endpoints:
 *   GET  /info              — public (device info)
 *   GET  /pairing/start     — public (returns token for pairing)
 *   POST /pairing/verify    — public with rate limiting (complete pairing)
 *   POST /sync/push         — HMAC-authenticated (requires trusted device)
 *   GET  /sync/pull         — HMAC-authenticated (requires trusted device)
 */
class KtorSyncServer(
    private val repo: JournalRepository,
    private val port: Int,
    private val tlsIdentity: TlsIdentityManager,
    private val trustStore: DeviceTrustStore,
    private val authenticator: SyncAuthenticator,
    private val onConnection: (String) -> Unit = {}
) {
    private var server: EmbeddedServer<*, *>? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val wsJson = Json { ignoreUnknownKeys = true; classDiscriminator = "#type"; serializersModule = wsModule }

    /** Stable device identity derived from the self-signed cert fingerprint. */
    val fingerprint: String by lazy { tlsIdentity.ensureIdentity() }
    val deviceId: String by lazy { "device-${fingerprint.take(16)}" }
    val deviceName: String by lazy { platformDeviceName() }

    // ---- Rate limiting for pairing endpoint ----
    private val pairingAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()
    private val maxPairingAttempts = 5
    private val pairingWindowMs = 120_000L

    private fun isRateLimited(clientIp: String): Boolean {
        val now = System.currentTimeMillis()
        val (count, windowStart) = pairingAttempts.getOrDefault(clientIp, Pair(0, now))
        if (now - windowStart > pairingWindowMs) {
            pairingAttempts[clientIp] = Pair(1, now)
            return false
        }
        if (count >= maxPairingAttempts) return true
        pairingAttempts[clientIp] = Pair(count + 1, windowStart)
        return false
    }

    val actualPort: Int get() = port

    suspend fun start(): Result<HostingInfo> = withContext(Dispatchers.IO) {
        try {
            val fp = fingerprint // force identity generation
            val deviceName = deviceName

            server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                install(WebSockets) {
                    pingPeriod = 15.seconds
                    timeout = 15.seconds
                    maxFrameSize = Long.MAX_VALUE
                }

                routing {
                    // ---- Public: device info ----
                    get("/info") {
                        call.respondText(
                            json.encodeToString(HostInfo(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                fingerprint = fp,
                                protocolVersion = 2
                            )),
                            ContentType.Application.Json
                        )
                    }

                    // ---- Public: pairing start ----
                    get("/pairing/start") {
                        call.respondText(
                            json.encodeToString(HostInfo(
                                deviceId = deviceId,
                                deviceName = deviceName,
                                fingerprint = fp,
                                protocolVersion = 2
                            )),
                            ContentType.Application.Json
                        )
                    }

                    // ---- Public: pairing verification with user-entered token ----
                    post("/pairing/verify") {
                        val clientIp = call.request.local.remoteHost
                        if (isRateLimited(clientIp)) {
                            call.respondText(
                                json.encodeToString(PairingResultResponse(false, error = "Too many attempts. Try again later.")),
                                ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
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

                        if (!authenticator.verifyPairingToken(verifyReq.token)) {
                            call.respondText(
                                json.encodeToString(PairingResultResponse(false, error = "Invalid or expired token")),
                                ContentType.Application.Json, status = HttpStatusCode.Forbidden
                            )
                            return@post
                        }

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

                        val hostSecret = trustStore.getSharedSecret(deviceId)
                            ?: authenticator.generateSharedSecret().also {
                                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                                    deviceId = deviceId,
                                    displayName = deviceName,
                                    fingerprint = fp,
                                    sharedSecret = it,
                                    pairedAt = System.currentTimeMillis()
                                ))
                            }

                        call.respondText(
                            json.encodeToString(PairingResultResponse(
                                success = true,
                                deviceId = clientDeviceId,
                                sharedSecret = hostSecret,
                                hostDeviceId = deviceId,
                                hostDeviceName = deviceName,
                                hostFingerprint = fp
                            )),
                            ContentType.Application.Json
                        )
                        authenticator.clearPendingPairing()
                        onConnection("Paired with ${verifyReq.clientDeviceName}")
                    }

                    // ---- Authenticated: push changes ----
                    post("/sync/push") {
                        val auth = verifyRequest(call)
                        if (auth == null) {
                            call.respondText(
                                json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                                ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                            )
                            return@post
                        }
                        val (callerDeviceId, body) = auth

                        if (body.length > SyncAuthenticator.MAX_SYNC_BODY_BYTES) {
                            call.respondText(
                                json.encodeToString(SyncResponse(false, error = "Payload too large")),
                                ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
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

                        val validationError = validateBatch(batch)
                        if (validationError != null) {
                            call.respondText(
                                json.encodeToString(SyncResponse(false, error = validationError)),
                                ContentType.Application.Json, status = HttpStatusCode.BadRequest
                            )
                            return@post
                        }

                        handlePush(batch)
                        trustStore.updateLastSeen(callerDeviceId)
                        val exchangeResponse = handlePull(batch.since)
                        call.respondText(
                            json.encodeToString(exchangeResponse),
                            ContentType.Application.Json
                        )
                    }

                    // ---- Authenticated: pull changes ----
                    get("/sync/pull") {
                        val auth = verifyRequest(call)
                        if (auth == null) {
                            call.respondText(
                                json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                                ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                            )
                            return@get
                        }
                        val (callerDeviceId, _) = auth

                        val sinceStr = call.request.queryParameters["since"]
                        val since = sinceStr?.toLongOrNull() ?: 0L
                        if (since < 0) {
                            call.respondText(
                                json.encodeToString(SyncResponse(false, error = "Invalid since")),
                                ContentType.Application.Json, status = HttpStatusCode.BadRequest
                            )
                            return@get
                        }

                        val response = handlePull(since)
                        trustStore.updateLastSeen(callerDeviceId)
                        call.respondText(json.encodeToString(response), ContentType.Application.Json)
                    }

                    // ---- Authenticated: WebSocket continuous sync ----
                    webSocket("/sync/ws") {
                        val callerDeviceId = call.request.queryParameters["deviceId"]
                        val authHeader = call.request.queryParameters["auth"]
                        if (callerDeviceId == null || authHeader == null) {
                            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Missing deviceId or auth"))
                            return@webSocket
                        }
                        if (!trustStore.isTrustedDeviceId(callerDeviceId)) {
                            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Untrusted device"))
                            return@webSocket
                        }
                        if (!authenticator.verifyRequest(callerDeviceId, "ws", authHeader)) {
                            close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Authentication failed"))
                            return@webSocket
                        }

                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                val msg = try {
                                    wsJson.decodeFromString<WsMessage>(text)
                                } catch (_: Exception) {
                                    outgoing.send(Frame.Text(
                                        wsJson.encodeToString(WsAck(0, error = "Malformed frame"))
                                    ))
                                    continue
                                }
                                when (msg) {
                                    is WsDelta -> {
                                        val validationError = validateWsDelta(msg)
                                        if (validationError != null) {
                                            outgoing.send(Frame.Text(
                                                wsJson.encodeToString(WsAck(seq = msg.seq, error = validationError))
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
                                            customUnits = msg.customUnits
                                        )
                                        outgoing.send(Frame.Text(
                                            wsJson.encodeToString(WsAck(seq = msg.seq))
                                        ))
                                        trustStore.updateLastSeen(callerDeviceId)
                                    }
                                    is WsPing -> {
                                        outgoing.send(Frame.Text(wsJson.encodeToString(WsPong(msg.seq))))
                                    }
                                    is WsPong -> { /* ignore */ }
                                    is WsAck -> { /* ignore */ }
                                }
                            }
                        }
                    }
                }
            }

            server!!.start(wait = false)
            Log.withTag("KtorSyncServer").i { "Server started on 0.0.0.0:$port (fingerprint=$fp)" }
            Result.success(HostingInfo("0.0.0.0", port, fp))
        } catch (e: Exception) {
            Log.withTag("KtorSyncServer").e(e) { "Server start failed: ${e.message}" }
            Result.failure(e)
        }
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    val isRunning: Boolean get() = server != null

    // ---- Auth middleware ----

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

    // ---- Data validation ----

    private fun validateBatch(batch: SyncBatch): String? = validateSyncBatch(batch)

    private fun handlePush(batch: SyncBatch) {
        var conflicts = 0
        // Batch-apply entities without conflict logic
        repo.applyBatch(
            substances = batch.substances,
            doses = batch.doses,
            interactions = batch.interactions,
            timelineEvents = batch.timelineEvents,
            effects = batch.effects,
            customUnits = batch.customUnits
        )
        // Sessions and notes need individual handling for conflict resolution
        batch.sessions.forEach { session ->
            val existing = repo.getSession(session.id)
            if (existing != null && existing.updatedAt > session.updatedAt) {
                repo.upsertNote(Note(
                    id = "conflict:${session.id}:${batch.deviceId}",
                    sessionId = session.id,
                    title = "Sync conflict — ${session.title}",
                    body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                    tags = listOf("sync-conflict"),
                    createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    deviceOrigin = batch.deviceId
                ))
                conflicts++
            } else repo.upsertSession(session)
        }
        batch.notes.forEach { note ->
            val existing = repo.notes.value.find { it.id == note.id }
            val resolved = if (existing != null && existing.body != note.body) {
                note.copy(conflictSiblings = existing.conflictSiblings +
                        ConflictSibling(note.body, batch.deviceId, note.updatedAt))
            } else note
            repo.upsertNote(resolved)
            if (resolved.conflictSiblings.isNotEmpty()) conflicts++
        }
        onConnection(if (conflicts > 0) "$conflicts conflict(s)" else "Synced from ${batch.deviceName}")
    }

    private fun handlePull(since: Long) = SyncResponse(
        success = true,
        sessions = repo.sessions.value.filter { it.updatedAt > since },
        doses = repo.doses.value.filter { it.updatedAt > since },
        substances = repo.substances.value.filter { it.updatedAt > since },
        interactions = repo.interactions.value.filter { it.updatedAt > since },
        notes = repo.notes.value.filter { it.updatedAt > since },
        timelineEvents = repo.timelineEvents.value.filter { it.updatedAt > since },
        effects = repo.effects.value.filter { it.updatedAt > since },
        customUnits = repo.customUnits.value.filter { it.updatedAt > since },
        conflictsCreated = repo.notes.value.count { it.conflictSiblings.isNotEmpty() }
    )
}

@kotlinx.serialization.Serializable
private data class PairingStartResponse(
    val token: String,
    val hostFingerprint: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val hostAddress: String,
    val listenerPort: Int,
    val protocolVersion: Int = 2
)

@kotlinx.serialization.Serializable
private data class PairingVerifyRequest(
    val token: String,
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientFingerprint: String
)

@kotlinx.serialization.Serializable
private data class PairingResultResponse(
    val success: Boolean,
    val error: String? = null,
    val deviceId: String? = null,
    val sharedSecret: String? = null,
    val hostDeviceId: String? = null,
    val hostDeviceName: String? = null,
    val hostFingerprint: String? = null
)
