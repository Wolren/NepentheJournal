package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.log.Log
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
    private val repo: JournalRepository,
    private val trustStore: IosDeviceTrustStore,
    private val pairingManager: IosPairingManager,
    private val nonceCache: IosNonceReplayCache,
    private val deviceId: String,
    private val deviceName: String,
    private val fingerprint: String,
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

    fun installRouting(app: Application) {
        app.routing {
            get(SyncEndpoints.INFO) {
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

            get(SyncEndpoints.PAIRING_START) {
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
                val challengeRaw = call.request.queryParameters["challenge"]
                if (deviceIdParam.isNullOrBlank() || challengeRaw.isNullOrBlank()) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
                // Cap the challenge like the JVM (MAX_CHALLENGE_LEN): an
                // unbounded challenge would be signed and stored verbatim.
                if (challengeRaw.length > MAX_CHALLENGE_LEN) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
                val challenge = challengeRaw
                val secret = trustStore.getSharedSecret(deviceIdParam)
                if (secret == null) {
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
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
                trustStore.addPeer(
                    IosDeviceTrustStore.IosTrustedPeer(
                        deviceId = clientDeviceId,
                        displayName = req.clientDeviceName.ifBlank { clientDeviceId },
                        fingerprint = req.clientFingerprint,
                        sharedSecret = secret,
                        pairedAt = currentTimeMillis()
                    )
                )
                // C2 wrap: the same secret encrypted under a key derived from
                // the pairing token, so a LAN observer of this response learns
                // nothing. The legacy field stays populated this wave.
                val encSecretB64 = try {
                    IosPairingSecretCrypto.encrypt(req.token, clientDeviceId, secret)
                } catch (e: Exception) {
                    Log.withTag("IosSync").w { "pairing secret wrap failed, sending legacy field only" }
                    null
                }
                call.respondText(
                    json.encodeToString(PairingResultResponse(
                        success = true,
                        deviceId = clientDeviceId,
                        sharedSecret = secret,
                        encSecretB64 = encSecretB64,
                        hostDeviceId = deviceId,
                        hostDeviceName = deviceName,
                        hostFingerprint = fingerprint
                    )),
                    ContentType.Application.Json
                )
                onConnection("Paired with ${req.clientDeviceName}")
            }

            post(SyncEndpoints.SYNC_PUSH) {
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

                val validationError = IosSyncValidators.validateSyncBatch(batch)
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
            sessions = page(repo.sessions.value, IosSyncValidators.MAX_ITEMS_DEFAULT) { it.updatedAt },
            doses = page(repo.doses.value, IosSyncValidators.MAX_ITEMS_DEFAULT) { it.updatedAt },
            substances = page(repo.substances.value, IosSyncValidators.MAX_SUBSTANCES) { it.updatedAt },
            effects = page(repo.effects.value, IosSyncValidators.MAX_EFFECTS) { it.updatedAt },
            interactions = page(repo.interactions.value, IosSyncValidators.MAX_INTERACTIONS) { it.updatedAt },
            notes = page(repo.notes.value, IosSyncValidators.MAX_ITEMS_DEFAULT) { it.updatedAt },
            timelineEvents = page(repo.timelineEvents.value, IosSyncValidators.MAX_ITEMS_DEFAULT) { it.updatedAt },
            customUnits = page(repo.customUnits.value, IosSyncValidators.MAX_CUSTOM_UNITS) { it.updatedAt },
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
        // Last writer wins by updatedAt, so a replayed or stale push cannot
        // roll back newer local data.
        repo.applyBatch(
            sessions = batch.sessions,
            doses = batch.doses,
            substances = batch.substances,
            effects = batch.effects,
            interactions = batch.interactions,
            notes = batch.notes,
            timelineEvents = batch.timelineEvents,
            customUnits = batch.customUnits,
            lastWriterWins = true,
            deletedSessionIds = batch.deletedSessionIds,
            deletedDoseIds = batch.deletedDoseIds,
            deletedNoteIds = batch.deletedNoteIds,
            deletedSubstanceIds = batch.deletedSubstanceIds,
            deletedEffectIds = batch.deletedEffectIds,
            deletedInteractionIds = batch.deletedInteractionIds,
            deletedTimelineEventIds = batch.deletedTimelineEventIds,
            deletedCustomUnitIds = batch.deletedCustomUnitIds,
            tombstoneCutoff = batch.since
        )
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

        /** Pull cursors may be at most 1 day in the future (clock skew allowance). */
        const val MAX_FUTURE_SINCE_MS = 86_400_000L
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
