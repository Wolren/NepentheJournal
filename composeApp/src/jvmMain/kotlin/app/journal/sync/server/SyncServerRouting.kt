package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.serde.AppJson
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
import app.journal.util.crypto.base64Decode
import app.journal.util.crypto.base64Encode
import app.journal.sync.decryptBody
import app.journal.sync.encryptBody
import java.util.concurrent.ConcurrentHashMap

/**
 * Production sync server routing, extracted for testing with [io.ktor.server.testing.testApplication].
 * Use [install] inside [io.ktor.server.engine.embeddedServer] or [io.ktor.server.testing.testApplication].
 */
class SyncServerRouter(
    private val repo: IJournalRepository,
    private val trustStore: DeviceTrustStore,
    private val authenticator: SyncAuthenticator,
    private val ecdhIdentity: EcdhIdentityManager,
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

    // wave2 file split (file-size-governor): cohesive private-method groups
    // moved verbatim into helpers this router owns; state is passed by
    // reference (same instances) so rate limits, sessions, and sequence
    // state behave identically to the pre-split single file.
    private val security = SyncServerSecurity(
        trustStore = trustStore, authenticator = authenticator,
        deviceId = deviceId, deviceName = deviceName, fingerprint = fingerprint,
        pairingAttempts = pairingAttempts
    )
    private val handlers = SyncServerHandlers(
        repo = repo, onConnection = onConnection, persistAfterApply = persistAfterApply
    )

    fun installRouting(app: Application) {
        app.install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 15.seconds
            maxFrameSize = 10L * 1024 * 1024 // 10 MB max frame
        }

        app.routing {
            get(SyncEndpoints.INFO) {
                call.respondText(
                    json.encodeToString(HostInfo(
                        deviceId = deviceId,
                        deviceName = deviceName,
                        fingerprint = fingerprint,
                        protocolVersion = SYNC_PROTOCOL_VERSION,
                        wsSupported = true,
                        ecdhPublicKeyB64 = ecdhIdentity.publicKeyB64
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
                        protocolVersion = SYNC_PROTOCOL_VERSION,
                        wsSupported = true,
                        ecdhPublicKeyB64 = ecdhIdentity.publicKeyB64
                    )),
                    ContentType.Application.Json
                )
            }

            get(SyncEndpoints.AUTH_VERIFY) {
                val clientIp = call.request.local.remoteHost
                security.evictStaleThrottle(verifyThrottle, VERIFY_WINDOW_MS)
                if (security.isThrottled(verifyThrottle, clientIp, MAX_VERIFY_PER_WINDOW, VERIFY_WINDOW_MS)) {
                    security.warnAuth(call, null, SyncEndpoints.AUTH_VERIFY, "rate limited")
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
                    security.warnAuth(call, deviceIdParam, SyncEndpoints.AUTH_VERIFY, "missing device or challenge")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                if (challengeRaw != null && challengeRaw.length > MAX_CHALLENGE_LEN) {
                    security.warnAuth(call, deviceIdParam, SyncEndpoints.AUTH_VERIFY, "challenge over 128 chars")
                    call.respondText(
                        json.encodeToString(HostChallengeResponse(0, "")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val secret = trustStore.getSharedSecret(deviceIdParam)
                if (secret == null) {
                    security.warnAuth(call, deviceIdParam, SyncEndpoints.AUTH_VERIFY, "unknown device")
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

            post(SyncEndpoints.PAIRING_VERIFY) {
                // Socket-level remote address. X-Forwarded-For is deliberately
                // NOT trusted: this server is directly reachable on the LAN with
                // no proxy, so the header is client-controlled and would let an
                // attacker rotate the rate-limit bucket per request.
                // Verified empirically on Ktor 3.5.1 Netty: local.remoteHost
                // carries the peer address (the 0.0.0.0 bind is NOT returned).
                val clientIp = call.request.local.remoteHost
                security.evictStaleBuckets()
                if (security.isRateLimited(clientIp)) {
                    security.warnAuth(call, null, SyncEndpoints.PAIRING_VERIFY, "rate limited")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Too many attempts. Try again later.")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }

                if (!security.hasValidContentLength(call, MAX_PAIRING_BODY_BYTES)) {
                    security.warnAuth(call, null, SyncEndpoints.PAIRING_VERIFY, "pairing body missing length or over 4KB")
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
                    security.warnAuth(call, verifyReq.clientDeviceId, SyncEndpoints.PAIRING_VERIFY, "invalid or expired token")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid or expired token")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@post
                }

                // Contract section g (ECDH): the permanent secret is ONLY
                // delivered sealed under an ECDH-derived key. Reject the
                // pairing when the client key is missing, not valid base64,
                // not 65 bytes, not prefixed 0x04, or not on the curve.
                val clientEcdhPublicKeyB64 = verifyReq.clientEcdhPublicKeyB64
                if (clientEcdhPublicKeyB64 == null) {
                    security.warnAuth(call, verifyReq.clientDeviceId, SyncEndpoints.PAIRING_VERIFY, "missing ECDH public key")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "ECDH public key required; upgrade the client")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }
                val ecdhSharedSecret = try {
                    ecdhIdentity.agreeWith(clientEcdhPublicKeyB64)
                } catch (e: Exception) {
                    security.warnAuth(call, verifyReq.clientDeviceId, SyncEndpoints.PAIRING_VERIFY, "invalid ECDH public key")
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Invalid ECDH public key")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@post
                }

                // One shared secret for this client: mint it, seal it under
                // the ECDH-derived key, then store it. The legacy plaintext
                // sharedSecret and PBKDF2 encSecretB64 fields are NOT
                // populated anymore: a LAN observer of this response must
                // not learn the permanent sync secret (audit C4).
                val sharedSecret = authenticator.generateSharedSecret()
                val clientDeviceId = verifyReq.clientDeviceId.ifBlank {
                    "client-${verifyReq.clientFingerprint.take(8)}"
                }
                val ecdhSecretB64 = try {
                    PairingEcdh.wrapSharedSecret(ecdhSharedSecret, sharedSecret)
                } catch (e: Exception) {
                    // Fail closed: never fall back to emitting the secret in
                    // the clear when the seal cannot be produced.
                    Log.withTag("KtorSyncServer").e(e) { "ECDH pairing seal failed" }
                    call.respondText(
                        json.encodeToString(PairingResultResponse(false, error = "Pairing seal failed")),
                        ContentType.Application.Json, status = HttpStatusCode.InternalServerError
                    )
                    return@post
                } finally {
                    ecdhSharedSecret.fill(0)
                }

                trustStore.addPeer(DeviceTrustStore.TrustedPeer(
                    deviceId = clientDeviceId,
                    displayName = verifyReq.clientDeviceName.ifBlank { clientDeviceId },
                    fingerprint = verifyReq.clientFingerprint,
                    sharedSecret = sharedSecret,
                    pairedAt = System.currentTimeMillis()
                ))

                // Also ensure the host has its own record (same shared secret or separate)
                security.hostSecret() // ensures host peer exists

                call.respondText(
                    json.encodeToString(PairingResultResponse(
                        success = true,
                        deviceId = clientDeviceId,
                        hostDeviceId = deviceId,
                        hostDeviceName = deviceName,
                        hostFingerprint = fingerprint,
                        ecdhSecretB64 = ecdhSecretB64
                    )),
                    ContentType.Application.Json
                )
                authenticator.clearPendingPairing()
                onConnection("Paired with ${verifyReq.clientDeviceName}")
            }

            post(SyncEndpoints.SYNC_PUSH) {
                val pushIp = call.request.local.remoteHost
                security.evictStaleThrottle(pushThrottle, PUSH_WINDOW_MS)
                if (security.isThrottled(pushThrottle, pushIp, MAX_PUSH_PER_WINDOW, PUSH_WINDOW_MS)) {
                    security.warnAuth(call, null, SyncEndpoints.SYNC_PUSH, "rate limited")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@post
                }
                // Declared length is enforced BEFORE the body is buffered:
                // missing, chunked, or oversized bodies are refused unread.
                if (!security.hasValidContentLength(call, MAX_BODY_BYTES)) {
                    security.warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], SyncEndpoints.SYNC_PUSH, "missing or oversized content length")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }
                val auth = security.verifyRequest(call)
                if (auth == null) {
                    security.warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], SyncEndpoints.SYNC_PUSH, "authentication failed")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@post
                }
                val (callerDeviceId, encryptedBody) = auth

                if (encryptedBody.length > SyncAuthenticator.MAX_SYNC_BODY_BYTES) {
                    security.warnAuth(call, callerDeviceId, SyncEndpoints.SYNC_PUSH, "payload too large")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Payload too large")),
                        ContentType.Application.Json, status = HttpStatusCode.fromValue(413)
                    )
                    return@post
                }

                // Decrypt the encrypted body before processing
                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    security.warnAuth(call, callerDeviceId, SyncEndpoints.SYNC_PUSH, "unknown device")
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
                    security.warnAuth(call, callerDeviceId, SyncEndpoints.SYNC_PUSH, "device ID mismatch")
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

                handlers.handlePush(batch)
                trustStore.updateLastSeen(callerDeviceId)
                // Durability: persist before acknowledging, so a crash after the
                // response cannot lose data the client believes was accepted.
                // The client advances its sync cursor on a successful response,
                // so an unpersisted ack would lose the pushed data forever.
                persistAfterApply?.invoke()
                val exchangeResponse = handlers.handlePull(batch.since)
                // Encrypt the response: encryptBody + base64Encode
                val encryptedResponse = base64Encode(encryptBody(
                    json.encodeToString(exchangeResponse), aesKey
                ))
                call.respondText(encryptedResponse, ContentType.Application.Json)
            }

            get(SyncEndpoints.SYNC_PULL) {
                val pullIp = call.request.local.remoteHost
                security.evictStaleThrottle(pullThrottle, PULL_WINDOW_MS)
                if (security.isThrottled(pullThrottle, pullIp, MAX_PULL_PER_WINDOW, PULL_WINDOW_MS)) {
                    security.warnAuth(call, null, SyncEndpoints.SYNC_PULL, "rate limited")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Too many requests")),
                        ContentType.Application.Json, status = HttpStatusCode.TooManyRequests
                    )
                    return@get
                }
                val auth = security.verifyRequest(call)
                if (auth == null) {
                    security.warnAuth(call, call.request.headers[SyncAuthenticator.DEVICE_ID_HEADER], SyncEndpoints.SYNC_PULL, "authentication failed")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Authentication failed")),
                        ContentType.Application.Json, status = HttpStatusCode.Unauthorized
                    )
                    return@get
                }
                val (callerDeviceId, _) = auth

                val sinceStr = call.request.queryParameters["since"]
                val since = sinceStr?.toLongOrNull() ?: 0L
                if (since < 0 || since > System.currentTimeMillis() + EntityTimePolicy.FUTURE_MARGIN_MS) {
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Invalid since")),
                        ContentType.Application.Json, status = HttpStatusCode.BadRequest
                    )
                    return@get
                }
                // Composite resume id (SyncResponse.nextSinceId). Bounded to
                // MAX_ID_LEN and blanked when malformed, so a hostile client
                // cannot smuggle an oversized cursor component into logs or
                // comparisons; an unknown parameter from an older client
                // simply stays "".
                val sinceId = call.request.queryParameters["sinceId"]
                    ?.trim()
                    ?.take(SyncLimits.MAX_ID_LEN)
                    .orEmpty()

                val callerSecret = trustStore.getSharedSecret(callerDeviceId)
                if (callerSecret == null) {
                    security.warnAuth(call, callerDeviceId, SyncEndpoints.SYNC_PULL, "unknown device")
                    call.respondText(
                        json.encodeToString(SyncResponse(false, error = "Unknown device; re-pair required")),
                        ContentType.Application.Json, status = HttpStatusCode.Forbidden
                    )
                    return@get
                }

                val response = handlers.handlePull(since, sinceId)
                trustStore.updateLastSeen(callerDeviceId)
                val aesKey = aesEncryptionKey(callerSecret)
                val encryptedResponse = base64Encode(encryptBody(
                    json.encodeToString(response), aesKey
                ))
                call.respondText(encryptedResponse, ContentType.Application.Json)
            }

            webSocket(SyncEndpoints.SYNC_WS) {
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

                // Contract section a.3: every authenticated handshake is a NEW
                // EPOCH for this device. Discard all prior sequence state so a
                // stale/replayed seq from an earlier connection can never
                // reject a frame of this one (and client reboot resets that
                // used to stall sync forever after nanoTime restarts).
                wsSeqState.remove(callerDeviceId)

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
                                    doses = msg.doses,
                                    substances = msg.substances,
                                    effects = msg.effects,
                                    interactions = msg.interactions,
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
                                    // Contract section b row 2: same cursor LWW rule as
                                    // the HTTP push path. Transport choice must not
                                    // change delete semantics (the old unconditional 0
                                    // let an older WS delete wipe a newer local edit).
                                    // Older senders leave since at its 0 default, which
                                    // yields the conservative "no local entity" rule.
                                    tombstoneCutoff = msg.since
                                )
                                // Contract section c: sessions and notes take the SAME
                                // shared-merge routes as the HTTP push path. The note
                                // merge is the repo's upsertNoteWithConflict, never a
                                // platform-local branch.
                                val conflicts =
                                    handlers.applySessionsWithConflict(msg.sessions, msg.deletedSessionIds, callerDeviceId) +
                                        handlers.applyNotesWithConflict(msg.notes, msg.deletedNoteIds, callerDeviceId)
                                // Persist before acking the delta (same rule as
                                // the push route; see audit D1).
                                persistAfterApply?.invoke()
                                if (conflicts > 0) onConnection("$conflicts conflict(s)")
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

    /**
     * Close every live server-side WebSocket session for [deviceId].
     * Called on revocation so a revoked device drops immediately, including
     * idle connections with no frames in flight (the per-frame trust recheck
     * in the WS route covers connections with traffic).
     */
    suspend fun closeDeviceSessions(deviceId: String) {
        // Contract section a.5: evict the sequence state together with the
        // sessions, so the per-device map cannot grow forever after
        // revocations and disconnects.
        wsSeqState.remove(deviceId)
        val sessions = wsSessions.remove(deviceId) ?: return
        for (session in sessions.toList()) {
            try {
                session.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Device revoked"))
            } catch (_: Exception) {
            }
        }
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
}
