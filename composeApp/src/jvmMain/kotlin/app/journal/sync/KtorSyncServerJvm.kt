package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore

/**
 * TLS + HMAC-authenticated sync server with data validation.
 * All traffic encrypted via self-signed TLS certificate.
 * All data-changing endpoints require HMAC-SHA256 signed requests.
 *
 * Endpoints:
 *   GET  /info              — public (device info)
 *   GET  /pairing/start     — public (returns token for pairing)
 *   POST /pairing/verify    — public (complete pairing, exchange secrets)
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
    private val fingerprint: String by lazy {
        try { TlsIdentityManager().ensureIdentity() } catch (_: Exception) { "unknown" }
    }
    private val deviceId: String by lazy { "desktop-${fingerprint.take(8)}" }

    val actualPort: Int get() = port

    suspend fun start(): Result<HostingInfo> = withContext(Dispatchers.IO) {
        try {
            val fingerprint = tlsIdentity.ensureIdentity()
            val ks = tlsIdentity.loadKeyStore()
            val pw = tlsIdentity.password

            server = embeddedServer(CIO, configure = {
                sslConnector(
                    keyStore = ks,
                    keyAlias = tlsIdentity.alias,
                    keyStorePassword = { pw },
                    privateKeyPassword = { pw }
                ) { }
                connectors = connectors.map { it.withPort(port) }.toMutableList()
            }) {
                routing {
                    // ---- Public: device info ----
                    get("/info") {
                        call.respondText(
                            json.encodeToString(HostInfo(
                                deviceId = deviceId,
                                deviceName = "Desktop (Windows)",
                                fingerprint = fingerprint,
                                protocolVersion = 2
                            )),
                            ContentType.Application.Json
                        )
                    }

                    // ---- Public: pairing start (returns host info, NOT the token) ----
                    get("/pairing/start") {
                        // Returns only device info. Token must be entered visually by the user.
                        call.respondText(
                            json.encodeToString(HostInfo(
                                deviceId = deviceId,
                                deviceName = "Desktop (Windows)",
                                fingerprint = fingerprint,
                                protocolVersion = 2
                            )),
                            ContentType.Application.Json
                        )
                    }

                    // ---- Public: pairing verification with user-entered token ----
                    post("/pairing/verify") {
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
                                    displayName = "Desktop (Windows)",
                                    fingerprint = fingerprint,
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
                                hostDeviceName = "Desktop (Windows)",
                                hostFingerprint = fingerprint
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
                        val (deviceId, body) = auth

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
                        trustStore.updateLastSeen(deviceId)
                        // Atomic exchange: also return server changes since client's timestamp
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
                        val (deviceId, _) = auth

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
                        trustStore.updateLastSeen(deviceId)
                        call.respondText(json.encodeToString(response), ContentType.Application.Json)
                    }
                }
            }

            server!!.start(wait = false)
            Result.success(HostingInfo("0.0.0.0", port, fingerprint))
        } catch (e: Exception) {
            System.err.println("KtorSyncServer start failed: ${e.message}")
            e.printStackTrace()
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

    private fun validateBatch(batch: SyncBatch): String? {
        val maxItems = 500
        if (batch.sessions.size > maxItems) return "Too many sessions (max $maxItems)"
        if (batch.doses.size > maxItems) return "Too many doses (max $maxItems)"
        if (batch.substances.size > 100) return "Too many substances (max 100)"
        if (batch.notes.size > maxItems) return "Too many notes (max $maxItems)"
        if (batch.timelineEvents.size > maxItems) return "Too many events (max $maxItems)"
        if (batch.interactions.size > 100) return "Too many interactions (max 100)"

        val maxFieldLen = 65536
        for (s in batch.sessions) {
            if (s.id.length > 128) return "Session ID too long"
            if (s.title.length > 500) return "Session title too long"
            if ((s.set?.length ?: 0) > maxFieldLen) return "Session set too long"
            if ((s.setting?.length ?: 0) > maxFieldLen) return "Session setting too long"
            if ((s.intention?.length ?: 0) > maxFieldLen) return "Session intention too long"
            if ((s.outcome?.length ?: 0) > maxFieldLen) return "Session outcome too long"
            if (s.tags.size > 50) return "Too many session tags"
            if (s.tags.any { it.length > 100 }) return "Session tag too long"
            if (s.rating != null && (s.rating < 1 || s.rating > 10)) return "Invalid rating"
        }
        for (d in batch.doses) {
            if (d.id.length > 128) return "Dose ID too long"
            if (d.sessionId.length > 128) return "Dose sessionId too long"
            if (d.substanceId.length > 128) return "Dose substanceId too long"
            if (d.routeOfAdministration.length > 50) return "Invalid ROA length"
            if (d.unit.length > 20) return "Invalid unit length"
            if (d.amount < 0 || d.amount > 1_000_000) return "Invalid dose amount"
            if (d.notes?.length ?: 0 > maxFieldLen) return "Dose notes too long"
        }
        for (n in batch.notes) {
            if (n.id.length > 128) return "Note ID too long"
            if (n.body.length > maxFieldLen) return "Note body too long"
            if (n.title?.length ?: 0 > 500) return "Note title too long"
            if (n.tags.size > 50) return "Too many note tags"
        }
        for (s in batch.substances) {
            if (s.id.length > 128) return "Substance ID too long"
            if (s.name.length > 200) return "Substance name too long"
            if (s.aliases.any { it.length > 200 }) return "Substance alias too long"
            if ((s.summary?.length ?: 0) > maxFieldLen) return "Substance summary too long"
        }
        for (i in batch.interactions) {
            if (i.id.length > 128) return "Interaction ID too long"
            if (i.substanceAId.length > 128) return "Interaction substanceAId too long"
            if (i.substanceBId.length > 128) return "Interaction substanceBId too long"
            if (i.description?.length ?: 0 > maxFieldLen) return "Interaction description too long"
        }
        for (t in batch.timelineEvents) {
            if (t.id.length > 128) return "TimelineEvent ID too long"
            if (t.label.length > 200) return "TimelineEvent label too long"
            if (t.body?.length ?: 0 > maxFieldLen) return "TimelineEvent body too long"
        }
        return null
    }

    private fun handlePush(batch: SyncBatch) {
        var conflicts = 0
        batch.substances.forEach { repo.upsertSubstance(it) }
        batch.doses.forEach { repo.upsertDose(it) }
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
        batch.interactions.forEach { repo.upsertInteraction(it) }
        batch.timelineEvents.forEach { repo.upsertTimelineEvent(it) }
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
