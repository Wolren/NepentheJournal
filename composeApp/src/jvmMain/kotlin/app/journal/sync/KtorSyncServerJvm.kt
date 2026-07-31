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
    private val json = AppJson.json
    private val pairingAttempts = ConcurrentHashMap<String, Pair<Int, Long>>()

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
                val deviceIdParam = call.request.queryParameters["deviceId"]
                val challenge = call.request.queryParameters["challenge"]
                if (deviceIdParam.isNullOrBlank() || challenge.isNullOrBlank()) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
                val secret = trustStore.getSharedSecret(deviceIdParam)
                if (secret == null) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
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

                call.respondText(
                    json.encodeToString(PairingResultResponse(
                        success = true,
                        deviceId = clientDeviceId,
                        sharedSecret = sharedSecret,
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
                val auth = verifyRequest(call)
                if (auth == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val (callerDeviceId, encryptedBody) = auth

                if (encryptedBody.length > SyncAuthenticator.MAX_SYNC_BODY_BYTES) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }

                // Decrypt the encrypted body before processing
                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "No shared secret for device")),
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

                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "No shared secret for device")),
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

                // Derive AES key for encrypting/decrypting WsDelta frames
                val wsAesKey = trustStore.getSharedSecret(callerDeviceId)?.let { aesEncryptionKey(it) }

                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        val msg = try {
                            wsJson.decodeFromString<WsMessage>(text)
                        } catch (_: Exception) {
                            // Not a plain WsMessage — try encrypted WsDelta
                            if (wsAesKey != null) {
                                try {
                                    val plaintext = decryptBody(base64Decode(text), wsAesKey)
                                    wsJson.decodeFromString<WsMessage>(plaintext)
                                } catch (_: Exception) { null }
                            } else null
                        }
                        if (msg == null) {
                            outgoing.send(Frame.Text(
                                wsJson.encodeToString(WsMessage.serializer(), WsAck(0, error = "Malformed frame"))
                            ))
                            continue
                        }
                        when (msg) {
                            is WsDelta -> {
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
                                    lastWriterWins = true
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
        repo.applyBatch(
            substances = batch.substances,
            doses = batch.doses,
            interactions = batch.interactions,
            timelineEvents = batch.timelineEvents,
            effects = batch.effects,
            customUnits = batch.customUnits,
            lastWriterWins = true
        )
        batch.sessions.forEach { session ->
            val existing = repo.getSession(session.id)
            if (existing != null && existing.updatedAt > session.updatedAt) {
                repo.upsertNote(Note(
                    id = "conflict:${session.id}:${batch.deviceId}",
                    sessionId = session.id,
                    title = "Sync conflict — ${session.title}",
                    body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                    createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    deviceOrigin = batch.deviceId
                ))
                conflicts++
            } else repo.upsertSession(session)
        }
        batch.notes.forEach { note ->
            val resolved = repo.upsertNoteWithConflict(note, batch.deviceId)
            if (resolved != null && resolved.conflictSiblings.isNotEmpty()) conflicts++
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
