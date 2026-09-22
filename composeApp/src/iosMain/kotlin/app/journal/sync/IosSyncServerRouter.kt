package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.serde.AppJson
import app.journal.log.Log
import app.journal.model.Note
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString

/**
 * Production iOS sync server routing, extracted for testing with
 * testApplication the same way the JVM SyncServerRouter is.
 *
 * Both embeddedServer and the integration test call [installRouting], so
 * the test exercises the exact production code path: pairing token check,
 * per device secret lookup, nonce replay protection, field caps, timestamp
 * bounds, and last writer wins apply.
 */
class IosSyncServerRouter(
    private val repo: IJournalRepository,
    private val trustStore: IosDeviceTrustStore,
    private val pairingManager: IosPairingManager,
    private val nonceCache: IosNonceReplayCache,
    private val deviceId: String,
    private val deviceName: String,
    private val fingerprint: String,
    /**
     * The host's STATIC P-256 ECDH keypair store (contract section g):
     * supplies HostInfo.ecdhPublicKeyB64 on both info routes and performs
     * the /pairing/verify key agreement.
     */
    private val ecdhIdentity: IosEcdhIdentityStore,
    private val onConnection: (String) -> Unit = {},
    private val isRateLimited: (String) -> Boolean = { false },
    /**
     * Persist callback invoked after every accepted apply and before the
     * response is sent (durability: the client advances its sync cursor on
     * a successful response, so an unpersisted ack would lose pushed data
     * forever on crash). Mirrors the JVM persistAfterApply.
     */
    private val persistAfterApply: (() -> Unit)? = null
) {
    private val json = AppJson.json

    // Per-IP throttles for the authenticated sync surface (one bucket per
    // endpoint so a pull drain can never starve pairing), mirroring the JVM
    // KtorSyncServerJvm buckets: push 120/min, pull 200/min, verify 30/min.
    private val pushThrottle = mutableMapOf<String, Pair<Int, Long>>()
    private val pullThrottle = mutableMapOf<String, Pair<Int, Long>>()
    private val verifyThrottle = mutableMapOf<String, Pair<Int, Long>>()
    private val throttleLock = PlatformLock()

    fun installRouting(app: Application) {
        app.routing {
            get(SyncEndpoints.INFO) {
                call.respondText(
                    json.encodeToString(HostInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        fingerprint = fingerprint,
                        protocolVersion = 2,
                        wsSupported = false,
                        // Static host ECDH key (contract g). Throws fail the
                        // route, so clients only ever see a real key or an error.
                        ecdhPublicKeyB64 = ecdhIdentity.publicKeyB64()
                    )),
                    ContentType.Application.Json
                )
            }

            get(SyncEndpoints.PAIRING_START) {
                call.respondText(
                    json.encodeToString(HostInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        fingerprint = fingerprint,
                        protocolVersion = 2,
                        wsSupported = false,
                        // Advertised here too so the client holds the host key
                        // before the pairing token is entered (contract g).
                        ecdhPublicKeyB64 = ecdhIdentity.publicKeyB64()
                    )),
                    ContentType.Application.Json
                )
            }

            get("/auth/verify") {
                val clientIp = call.request.local.remoteHost
                if (isThrottled(verifyThrottle, clientIp, MAX_VERIFY_PER_WINDOW, VERIFY_WINDOW_MS)) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                // Header first with query fallback, same read order as the
                // JVM route, so header-style and query-style clients both work.
                val deviceIdParam = call.request.headers[SyncAuth.DEVICE_ID_HEADER]
                    ?: call.request.queryParameters["deviceId"]
                val challengeRaw = call.request.headers["X-Sync-Challenge"]
                    ?: call.request.queryParameters["challenge"]
                val challenge = challengeRaw?.take(MAX_CHALLENGE_LEN)
                // Uniform failure code: missing params, overlong challenges, and
                // unknown devices all answer 401 with the SAME empty body, so
                // this endpoint is not a device-existence oracle. Mirrors
                // KtorSyncServerJvm exactly.
                if (deviceIdParam.isNullOrBlank() || challenge.isNullOrBlank()) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                if (challengeRaw != null && challengeRaw.length > MAX_CHALLENGE_LEN) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val secret = trustStore.getSharedSecret(deviceIdParam)
                if (secret == null) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                // Prove knowledge of the shared secret bound to the fresh
                // client challenge, so a captured response cannot be replayed
                // against a different challenge.
                val timestamp = currentTimeMillis()
                val signature = pairingManager.signChallenge(deviceIdParam, timestamp, challenge, secret)
                call.respondText(
                    json.encodeToString(HostChallengeResponse(timestamp, signature)),
                    ContentType.Application.Json
                )
            }

            post(SyncEndpoints.PAIRING_VERIFY) {
                if (isRateLimited(call.request.local.remoteHost)) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Too many attempts. Try again later.")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }

                // Declared length is enforced BEFORE the body is buffered:
                // missing, chunked, or oversized bodies are refused unread,
                // mirroring the JVM pairing route.
                if (!hasValidContentLength(call, IosPairingManager.MAX_PAIRING_BODY_BYTES.toLong())) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Body too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }
                val bodyText = call.receiveText()
                if (bodyText.length > IosPairingManager.MAX_PAIRING_BODY_BYTES) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Body too large")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

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

                // Cap identity fields so a client cannot register oversized
                // display names or ids in the trust store.
                if (req.clientDeviceId.length > 128 ||
                    req.clientDeviceName.length > 200 ||
                    req.clientFingerprint.length > 128
                ) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid client identity")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                // Contract section g: validate the client's EPHEMERAL P-256
                // key BEFORE the token is consumed, so a malformed key can
                // never burn a single-use token. Rules match the JVM host:
                // present, valid base64, 65 bytes, first byte 0x04, and a
                // point on the curve (SecKeyCreateWithData rejects off-curve
                // points, so a failed agreement yields null here as well).
                val clientEcdhB64 = req.clientEcdhPublicKeyB64
                if (clientEcdhB64 == null) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Client ECDH public key required")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }
                val clientEcdhBytes = runCatching { base64Decode(clientEcdhB64) }.getOrNull()
                if (clientEcdhBytes == null ||
                    clientEcdhBytes.size != PairingEcdh.PUBLIC_KEY_BYTES ||
                    clientEcdhBytes[0] != PairingEcdh.UNCOMPRESSED_PREFIX
                ) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid client ECDH public key")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }
                // ECDH with the STATIC host private key (SecKeyCreateKeyExchange).
                val ecdhShared = ecdhIdentity.agreeSharedSecret(clientEcdhBytes)
                if (ecdhShared == null) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid client ECDH public key")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                // Reject before minting any secret: single use token, 120s TTL.
                if (!pairingManager.verifyPairingToken(req.token)) {
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid or expired token")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@post
                }

                val secret = pairingManager.generateSharedSecret()
                val clientDeviceId = req.clientDeviceId.ifBlank {
                    "client-${req.clientFingerprint.take(8)}"
                }
                // Contract section g: seal the secret under the ECDH-derived
                // key BEFORE registering the peer, so every failure above
                // leaves no trust-store side effect. The plaintext
                // sharedSecret field and the legacy PBKDF2 encSecretB64 wrap
                // leave the protocol: a LAN observer of this response must not
                // learn the permanent sync secret.
                val ecdhSecretB64 = try {
                    PairingEcdh.wrapSharedSecret(ecdhShared, secret)
                } catch (e: Exception) {
                    Log.withTag("IosSync").e(e) { "pairing ECDH wrap failed" }
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Pairing crypto failed")),
                        ContentType.Application.Json, status = HttpStatusCode.InternalServerError
                    )
                    return@post
                }
                trustStore.addPeer(
                    IosDeviceTrustStore.IosTrustedPeer(
                        deviceId = clientDeviceId,
                        displayName = req.clientDeviceName.ifBlank { clientDeviceId },
                        fingerprint = req.clientFingerprint,
                        sharedSecret = secret,
                        pairedAt = currentTimeMillis()
                    )
                )
                call.respondText(
                    json.encodeToString(PairingResultResponse(
                        success = true,
                        deviceId = clientDeviceId,
                        ecdhSecretB64 = ecdhSecretB64,
                        hostDeviceId = deviceId,
                        hostDeviceName = deviceName,
                        hostFingerprint = fingerprint
                    )),
                    ContentType.Application.Json
                )
                onConnection("Paired with ${req.clientDeviceName}")
            }

            post(SyncEndpoints.SYNC_PUSH) {
                val pushIp = call.request.local.remoteHost
                if (isThrottled(pushThrottle, pushIp, MAX_PUSH_PER_WINDOW, PUSH_WINDOW_MS)) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }
                // Declared length is enforced BEFORE the body is buffered:
                // missing, chunked, or oversized bodies are refused unread,
                // mirroring the JVM push route.
                if (!hasValidContentLength(call, MAX_SYNC_BODY_BYTES)) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }
                val auth = verifyPushAuth(call)
                if (auth == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val (callerDeviceId, encryptedBody) = auth

                if (encryptedBody.length > MAX_SYNC_BODY_BYTES) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }

                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "No shared secret for device")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@post
                }
                val aesKey = aesEncryptionKey(callerSecret)
                val batchJson = try {
                    decryptBody(base64Decode(encryptedBody), aesKey)
                } catch (e: Exception) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Decryption failed")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }
                val batch = try {
                    json.decodeFromString<SyncBatch>(batchJson)
                } catch (e: Exception) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Invalid payload")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                // The batch must identify the authenticated caller, otherwise
                // a paired device could attribute writes to another device.
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

                applySyncBatch(batch)
                trustStore.updateLastSeen(callerDeviceId)
                // Durability: persist before acknowledging, so a crash after
                // the response cannot lose data the client believes was
                // accepted. Mirrors the JVM push route.
                persistAfterApply?.invoke()
                val response = buildSyncResponse(batch.since)
                val responseJson = json.encodeToString(response)
                val encryptedResp = encryptBody(responseJson, aesKey)
                call.respondText(base64Encode(encryptedResp), ContentType.Application.Json)
            }

            get(SyncEndpoints.SYNC_PULL) {
                val pullIp = call.request.local.remoteHost
                if (isThrottled(pullThrottle, pullIp, MAX_PULL_PER_WINDOW, PULL_WINDOW_MS)) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val auth = verifyPullAuth(call)
                if (auth == null) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val (callerDeviceId, _) = auth

                val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L
                if (since < 0 || since > currentTimeMillis() + MAX_FUTURE_SINCE_MS) {
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

                val response = buildSyncResponse(since)
                trustStore.updateLastSeen(callerDeviceId)
                val aesKey = aesEncryptionKey(callerSecret)
                val encryptedResponse = base64Encode(encryptBody(
                    json.encodeToString(response), aesKey
                ))
                call.respondText(encryptedResponse, ContentType.Application.Json)
            }
        }
    }

    private suspend fun verifyPushAuth(call: ApplicationCall): Pair<String, String>? {
        val callerDeviceId = call.request.headers[SyncAuth.DEVICE_ID_HEADER] ?: return null
        val authHeader = call.request.headers[SyncAuth.AUTH_HEADER] ?: return null
        if (!trustStore.isTrustedDeviceId(callerDeviceId)) return null
        val secret = trustStore.getSharedSecret(callerDeviceId) ?: return null
        val body = call.receiveText()
        return if (verifyAuth(callerDeviceId, body, authHeader, secret.encodeToByteArray())) {
            Pair(callerDeviceId, body)
        } else null
    }

    private fun verifyPullAuth(call: ApplicationCall): Pair<String, String>? {
        val callerDeviceId = call.request.headers[SyncAuth.DEVICE_ID_HEADER] ?: return null
        val authHeader = call.request.headers[SyncAuth.AUTH_HEADER] ?: return null
        if (!trustStore.isTrustedDeviceId(callerDeviceId)) return null
        val secret = trustStore.getSharedSecret(callerDeviceId) ?: return null
        // Sign the exact pull target including the since param, same as JVM.
        val body = call.request.uri
        return if (verifyAuth(callerDeviceId, body, authHeader, secret.encodeToByteArray())) {
            Pair(callerDeviceId, body)
        } else null
    }

    internal fun verifyAuth(deviceId: String, body: String, authHeader: String, secret: ByteArray): Boolean {
        val parts = authHeader.split(":", limit = 3)
        if (parts.size != 3) return false
        val (timestampStr, nonce, signature) = parts
        val timestamp = timestampStr.toLongOrNull() ?: return false
        // Timestamp window plus nonce replay protection, never cleared.
        if (!nonceCache.checkAndRecord(nonce, timestamp)) return false
        val payload = "$deviceId:$timestamp:$nonce:$body"
        val expected = hmacSha256Hex(secret, payload.encodeToByteArray())
        return constantTimeEquals(signature, expected)
    }

    private fun buildSyncResponse(since: Long): SyncResponse {
        val deleted = repo.deletedIdsSince(since)
        // Oldest-first pages with a low-water nextSince, same contract as
        // the JVM pull handler: the client drains while truncated is set.
        val lowWater = mutableListOf<Long>()
        var truncated = false
        fun <T> page(list: List<T>, max: Int, updatedAt: (T) -> Long): List<T> {
            val fresh = list.filter { updatedAt(it) > since }.sortedBy(updatedAt)
            if (fresh.size <= max) return fresh
            truncated = true
            val cut = fresh.take(max)
            lowWater.add(cut.maxOf(updatedAt))
            return cut
        }
        return SyncResponse(
            success = true,
            sessions = page(repo.sessions.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
            doses = page(repo.doses.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
            substances = page(repo.substances.value, SyncLimits.MAX_SUBSTANCES) { it.updatedAt },
            effects = page(repo.effects.value, SyncLimits.MAX_EFFECTS) { it.updatedAt },
            interactions = page(repo.interactions.value, SyncLimits.MAX_INTERACTIONS) { it.updatedAt },
            notes = page(repo.notes.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
            timelineEvents = page(repo.timelineEvents.value, SyncLimits.MAX_ITEMS_DEFAULT) { it.updatedAt },
            customUnits = page(repo.customUnits.value, SyncLimits.MAX_CUSTOM_UNITS) { it.updatedAt },
            deletedSessionIds = deleted.deletedSessionIds,
            deletedDoseIds = deleted.deletedDoseIds,
            deletedNoteIds = deleted.deletedNoteIds,
            deletedSubstanceIds = deleted.deletedSubstanceIds,
            deletedEffectIds = deleted.deletedEffectIds,
            deletedInteractionIds = deleted.deletedInteractionIds,
            deletedTimelineEventIds = deleted.deletedTimelineEventIds,
            deletedCustomUnitIds = deleted.deletedCustomUnitIds,
            truncated = truncated,
            nextSince = if (truncated) lowWater.min() else 0L
        )
    }

    private fun applySyncBatch(batch: SyncBatch) {
        // Mirror the JVM host push handler (KtorSyncServerJvm.handlePush):
        // truncate the peer device name to 200 chars, bulk-apply everything
        // EXCEPT sessions and notes with last-writer-wins and the batch.since
        // tombstone cutoff, then route those two collections through the
        // shared conflict-aware repo APIs. A conflicting peer session becomes
        // a sync-conflict note with deviceOrigin provenance, and notes go
        // through upsertNoteWithConflict, so identical peer data produces the
        // same journal on iOS as on the JVM host (contract section c).
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
            tombstoneCutoff = tagged.since
        )
        tagged.sessions.forEach { session ->
            if (session.id in tagged.deletedSessionIds) return@forEach
            val existing = repo.getSession(session.id)
            if (existing != null && existing.updatedAt > session.updatedAt) {
                repo.upsertNote(Note(
                    id = "conflict:${session.id}:${tagged.deviceId}",
                    sessionId = session.id,
                    title = "Sync conflict: ${session.title}",
                    body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                    createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                    // Conflict notes always carry the pushing device so the
                    // provenance is never ambiguous.
                    deviceOrigin = "sync:${tagged.deviceId}"
                ))
            } else repo.upsertSession(session.copy(deviceOrigin = session.deviceOrigin.ifBlank { "sync:${tagged.deviceId}" }))
        }
        tagged.notes.forEach { note ->
            if (note.id in tagged.deletedNoteIds) return@forEach
            repo.upsertNoteWithConflict(note, tagged.deviceId)
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    companion object {
        /** Max body size for sync requests (10 MB). */
        const val MAX_SYNC_BODY_BYTES = 10L * 1024 * 1024

        /** Max challenge length for /auth/verify, matching the JVM cap. */
        const val MAX_CHALLENGE_LEN = 128

        /** Pull cursors may be at most 1 day in the future (shared EntityTimePolicy margin). */
        const val MAX_FUTURE_SINCE_MS = EntityTimePolicy.FUTURE_MARGIN_MS

        // Per-IP throttle limits, mirroring KtorSyncServerJvm exactly:
        // 120 pushes, 200 pulls, 30 auth challenges per 60s window.
        const val MAX_PUSH_PER_WINDOW = 120
        const val PUSH_WINDOW_MS = 60_000L
        const val MAX_PULL_PER_WINDOW = 200
        const val PULL_WINDOW_MS = 60_000L
        const val MAX_VERIFY_PER_WINDOW = 30
        const val VERIFY_WINDOW_MS = 60_000L
    }

    /**
     * Generic per-IP throttle with stale-bucket eviction, a local
     * reimplementation of the JVM isThrottled/evictStaleThrottle pair
     * (iosMain must not import jvmMain). Both steps run under one lock, so
     * counting is atomic: exactly [max] requests are allowed per
     * [windowMs] window and the max+1th is blocked. Expired buckets are
     * dropped on every call, so idle client IPs never accumulate.
     */
    private fun isThrottled(
        throttle: MutableMap<String, Pair<Int, Long>>,
        clientKey: String,
        max: Int,
        windowMs: Long
    ): Boolean = throttleLock.withLock {
        val now = currentTimeMillis()
        val expired = throttle.entries.filter { now - it.value.second > windowMs }.map { it.key }
        expired.forEach { throttle.remove(it) }
        val (count, windowStart) = throttle[clientKey] ?: Pair(0, now)
        val next = if (now - windowStart > windowMs) Pair(1, now) else Pair(count + 1, windowStart)
        throttle[clientKey] = next
        next.first > max
    }

    /**
     * Strict Content-Length pre-check: the request is valid only when it
     * declares a length AND it fits in [maxBytes]. Missing or chunked bodies
     * are refused unread, since Ktor receiveText has no size cap and would
     * otherwise buffer an unbounded body before any check runs.
     * Mirrors the JVM hasValidContentLength.
     */
    private fun hasValidContentLength(call: ApplicationCall, maxBytes: Long): Boolean {
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return false
        return declared in 1..maxBytes
    }
}
