package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.serde.AppJson
import app.journal.log.Log
import app.journal.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.journal.util.crypto.base64Decode
import app.journal.util.crypto.base64Encode
import java.io.IOException

/**
 * LAN sync client with AES-256-GCM body encryption + HMAC-SHA256 request signing.
 *
 * All sync bodies are encrypted with AES-256-GCM before being sent over the wire.
 * Wire format: base64(encryptBody(json, aesKey)). HMAC signs the base64 ciphertext.
 *
 * Modes:
 *   - PAIRING mode (sharedSecret = null): no auth, used to exchange pairing tokens
 *   - AUTHENTICATED mode (sharedSecret != null): AES-256-GCM + HMAC-SHA256
 *
 * WebSocket continuous sync uses the same HMAC scheme for connection auth.
 *
 * Contract notes (docs/HARDENING-CONTRACTS-2026-09.md):
 *   - Pushes are CHUNKED through buildPushSlices and sent sequentially; the
 *     cursor only moves when every slice acks success=true (section d).
 *   - Pairing resolves the secret ONLY from ecdhSecretB64 (section g).
 *   - Pull responses pass the shared validateSyncResponse guard before they
 *     reach the store (client-response guard).
 */
class KtorSyncClient(
    private val repo: IJournalRepository,
    private val tlsIdentity: TlsIdentityManager? = null,
    private val deviceId: String = "unknown",
    private val deviceFingerprint: String? = null,
    private val deviceName: String = "Desktop (Windows)",
    private val sharedSecret: String? = null,
    trustedFingerprint: String? = null,
    private val persistAfterApply: (() -> Unit)? = null
) {
    private val json = AppJson.json
    private val canSign: Boolean get() = sharedSecret != null

    // Plain HTTP client. Encryption + HMAC secures data on LAN; the scheme
    // is built from SyncEndpoints.URL_SCHEME so a future TLS phase flips one
    // constant (contract section g).
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    /** "http://host:port" for every HTTP sync endpoint. */
    private fun endpoint(host: String, port: Int): String =
        "${SyncEndpoints.URL_SCHEME}://$host:$port"

    // ---- Pairing endpoints (no auth needed) ----

    suspend fun requestHostInfo(host: String, port: Int): Result<HostInfo> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.get("${endpoint(host, port)}${SyncEndpoints.PAIRING_START}")
                val info = response.body<HostInfo>()
                Result.success(info)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Complete pairing with a host using a user-entered token.
     *
     * Contract section g (ECDH): generates an EPHEMERAL P-256 keypair for
     * this attempt, sends it in clientEcdhPublicKeyB64, and resolves the
     * shared secret ONLY by unwrapping ecdhSecretB64 with the ECDH-derived
     * key. When the host sends no ecdhSecretB64 (pre-contract host) pairing
     * FAILS CLOSED; the legacy plaintext sharedSecret and PBKDF2
     * encSecretB64 fields are never read here. No key material and no
     * sealed secret is ever logged (the debug log replays to the UI viewer).
     *
     * @param hostEcdhPublicKeyB64 the host's static public key from HostInfo
     * (fetched by the caller when it already holds it); fetched here only
     * when absent.
     */
    suspend fun completePairing(
        host: String, port: Int,
        token: String,
        clientDeviceId: String,
        clientDeviceName: String,
        clientFingerprint: String,
        hostEcdhPublicKeyB64: String? = null
    ): Result<DevicePairingResult> = withContext(Dispatchers.IO) {
        try {
            val ephemeral = EcdhIdentityManager.generateEphemeralKeyPair()
            val clientPublicKeyB64 = EcdhIdentityManager.uncompressedPointB64(ephemeral.public)
            val hostPublicKeyB64 = hostEcdhPublicKeyB64
                ?: requestHostInfo(host, port).getOrNull()?.ecdhPublicKeyB64
            if (hostPublicKeyB64 == null) {
                return@withContext Result.failure(
                    Exception("Host does not support ECDH pairing; upgrade the host")
                )
            }

            val response = client.post("${endpoint(host, port)}${SyncEndpoints.PAIRING_VERIFY}") {
                contentType(ContentType.Application.Json)
                setBody(PairingVerifyRequest(
                    token = token,
                    clientDeviceId = clientDeviceId,
                    clientDeviceName = clientDeviceName,
                    clientFingerprint = clientFingerprint,
                    clientEcdhPublicKeyB64 = clientPublicKeyB64
                ))
            }
            val result = response.body<PairingResultResponse>()
            if (!result.success) {
                return@withContext Result.failure(Exception(result.error ?: "Pairing failed"))
            }

            val sealed = result.ecdhSecretB64
                ?: return@withContext Result.failure(
                    Exception("Host does not support ECDH pairing; upgrade the host")
                )
            val ecdhSharedSecret = try {
                EcdhIdentityManager.agree(ephemeral.private, hostPublicKeyB64)
            } catch (e: Exception) {
                return@withContext Result.failure(
                    Exception("Host advertised an invalid ECDH public key")
                )
            }
            val secret = try {
                PairingEcdh.unwrapSharedSecret(ecdhSharedSecret, sealed)
            } catch (e: Exception) {
                // Fail closed: wrong key, tampered ciphertext, or a host that
                // only populated the legacy fields. Never fall back to them.
                return@withContext Result.failure(
                    Exception("ECDH pairing secret could not be unwrapped; refusing legacy fields")
                )
            } finally {
                ecdhSharedSecret.fill(0)
            }
            Result.success(DevicePairingResult(
                deviceId = result.deviceId ?: "",
                sharedSecret = secret,
                hostDeviceId = result.hostDeviceId ?: "",
                hostDeviceName = result.hostDeviceName ?: "",
                hostFingerprint = result.hostFingerprint ?: ""
            ))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Authenticated sync endpoints ----

    /** Max pull pages per sync. Guards against a peer that reports truncated forever. */
    private val maxPullPages = 20

    private suspend fun fetchPullPage(host: String, port: Int, cursor: Long): SyncResponse {
        val uri = "${SyncEndpoints.SYNC_PULL}?since=$cursor"
        val authHeader = authenticateRequest(deviceId, uri)
        val httpResponse = client.get("${endpoint(host, port)}$uri") {
            header(SyncAuthenticator.DEVICE_ID_HEADER, deviceId)
            header(SyncAuthenticator.AUTH_HEADER, authHeader)
        }
        val rawBody = httpResponse.bodyAsText()
        val aesKey = aesEncryptionKey(sharedSecret!!)
        val decrypted = decryptBody(base64Decode(rawBody), aesKey)
        return json.decodeFromString<SyncResponse>(decrypted)
    }

    /** Apply the first page, then keep pulling while truncated. Follows nextSince. */
    private suspend fun applyAndDrain(host: String, port: Int, first: SyncResponse, firstSince: Long): SyncResponse {
        var page = first
        var cursor = firstSince
        applyPull(page, cursor)
        var pages = 1
        while (page.truncated && pages < maxPullPages) {
            val next = if (page.nextSince > 0L) page.nextSince else maxOf(page.maxUpdatedAt(), cursor)
            if (next <= cursor) {
                Log.withTag("SyncClient").w { "pull cursor stalled, stopping drain" }
                break
            }
            cursor = next
            val r = retryWithBackoff { fetchPullPage(host, port, cursor) }
            if (r.isFailure) break
            page = r.getOrThrow()
            if (!page.success) break
            applyPull(page, cursor)
            pages++
        }
        return page
    }

    /**
     * Outcome of one chunked push cycle (contract section d).
     *
     * [acks] holds exactly one response per slice, in send order. [cycleStart]
     * is the ONLY wall-clock value of the cycle: captured before the batch was
     * built, so no change made during the round trip can be skipped, and it is
     * the only value the caller may advance its cursor to, and only through
     * [advanceCursorIfAllSucceeded] with [acks].
     */
    data class ChunkedPushResult(
        val acks: List<SyncResponse>,
        val cycleStart: Long,
        val finalResponse: SyncResponse
    )

    /**
     * Push everything changed since [since], sliced into validator-sized
     * chunks (contract section d, audit C1):
     *
     * 1. buildPushSlices caps every collection so a 2015-interaction seed
     *    (cap 100) becomes 21 slices instead of one rejected batch.
     * 2. Slices are sent strictly sequentially; slice N+1 leaves only after
     *    slice N received its response.
     * 3. The first failure (transport error, success=false, decode failure,
     *    zero acks) aborts with Result.failure and NO cursor advice happens
     *    upstream: the caller keeps its previous cursor and resends from
     *    there next cycle.
     * 4. On full success the caller advances the cursor to [ChunkedPushResult.cycleStart]
     *    via advanceCursorIfAllSucceeded(previous, cycleStart, acks).
     */
    suspend fun pushChanges(
        host: String, port: Int,
        deviceId: String, deviceName: String,
        since: Long
    ): Result<ChunkedPushResult> = withContext(Dispatchers.IO) {
        if (!canSign) return@withContext Result.failure(Exception("Not paired"))

        // Contract d.4: captured BEFORE the batch is built. This is the only
        // wall-clock value allowed to become the push cursor.
        val cycleStart = System.currentTimeMillis()

        val deleted = repo.deletedIdsSince(since)
        val batch = SyncBatch(
            deviceId = deviceId,
            deviceName = deviceName,
            since = since,
            sessions = changed(repo.sessions.value, since) { it.updatedAt },
            doses = changed(repo.doses.value, since) { it.updatedAt },
            substances = changed(repo.substances.value, since) { it.updatedAt },
            effects = changed(repo.effects.value, since) { it.updatedAt },
            interactions = changed(repo.interactions.value, since) { it.updatedAt },
            notes = changed(repo.notes.value, since) { it.updatedAt },
            timelineEvents = changed(repo.timelineEvents.value, since) { it.updatedAt },
            customUnits = changed(repo.customUnits.value, since) { it.updatedAt },
            deletedSessionIds = deleted.deletedSessionIds,
            deletedDoseIds = deleted.deletedDoseIds,
            deletedNoteIds = deleted.deletedNoteIds,
            deletedSubstanceIds = deleted.deletedSubstanceIds,
            deletedEffectIds = deleted.deletedEffectIds,
            deletedInteractionIds = deleted.deletedInteractionIds,
            deletedTimelineEventIds = deleted.deletedTimelineEventIds,
            deletedCustomUnitIds = deleted.deletedCustomUnitIds
        )
        val slices = buildPushSlices(batch)
        val aesKey = aesEncryptionKey(sharedSecret!!)
        val acks = mutableListOf<SyncResponse>()

        for ((index, slice) in slices.withIndex()) {
            val bodyText = json.encodeToString(slice)
            // Encrypt body with AES-256-GCM, then base64-encode for transport
            val encryptedBody = base64Encode(encryptBody(bodyText, aesKey))
            val authHeader = authenticateRequest(deviceId, encryptedBody)

            val sliceResult = retryWithBackoff {
                val httpResponse = client.post("${endpoint(host, port)}${SyncEndpoints.SYNC_PUSH}") {
                    contentType(ContentType.Application.Json)
                    header(SyncAuthenticator.DEVICE_ID_HEADER, deviceId)
                    header(SyncAuthenticator.AUTH_HEADER, authHeader)
                    setBody(encryptedBody)
                }
                // Read raw body, base64-decode, decrypt, then deserialize
                val rawBody = httpResponse.bodyAsText()
                val decrypted = decryptBody(base64Decode(rawBody), aesKey)
                json.decodeFromString<SyncResponse>(decrypted)
            }
            val ack = sliceResult.getOrElse { error ->
                Log.withTag("SyncClient").w { "push slice $index/${slices.size} failed: ${error.message}" }
                return@withContext Result.failure(error)
            }
            acks += ack
            if (!ack.success) {
                // Stop immediately: later slices must not be sent, and the
                // cursor stays where it was so everything not confirmed is
                // resent next cycle.
                Log.withTag("SyncClient").w { "push slice $index/${slices.size} rejected: ${ack.error}" }
                return@withContext Result.failure(Exception(ack.error ?: "Push slice rejected"))
            }
        }

        // Every slice acked success=true. Apply the exchange data: the final
        // ack is the freshest snapshot of the same since cursor, and
        // applyAndDrain follows its pagination from there.
        try {
            val last = applyAndDrain(host, port, acks.last(), since)
            if (!last.success) {
                return@withContext Result.failure(Exception(last.error ?: "Push failed"))
            }
            Result.success(ChunkedPushResult(acks = acks, cycleStart = cycleStart, finalResponse = last))
        } catch (e: Exception) {
            Log.withTag("SyncClient").e(e) { "applyPull failed after successful push" }
            Result.failure(e)
        }
    }

    /**
     * Prove the host knows [secret] before re-using a stored pairing secret.
     * Sends a fresh random challenge to /auth/verify and checks the HMAC
     * response. A fingerprint-spoofed host (fake mDNS service) cannot answer
     * correctly, so a stored secret is never handed to an impostor.
     * Auth travels in headers; the query form is kept for older hosts.
     */
    suspend fun verifyHostIdentity(host: String, port: Int, callerDeviceId: String, secret: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val rawChallenge = generateNonce()
                val challenge = rawChallenge.take(128)
                val resp = client.get("${endpoint(host, port)}${SyncEndpoints.AUTH_VERIFY}") {
                    header(SyncAuthenticator.DEVICE_ID_HEADER, callerDeviceId)
                    header("X-Sync-Challenge", challenge)
                }
                if (resp.status != HttpStatusCode.OK) {
                    val legacy = client.get("${endpoint(host, port)}${SyncEndpoints.AUTH_VERIFY}?deviceId=$callerDeviceId&challenge=$challenge")
                    if (legacy.status != HttpStatusCode.OK) return@withContext false
                    return@withContext checkChallenge(legacy.bodyAsText(), callerDeviceId, challenge, secret)
                }
                checkChallenge(resp.bodyAsText(), callerDeviceId, challenge, secret)
            } catch (e: Exception) {
                false
            }
        }

    private fun checkChallenge(body: String, callerDeviceId: String, challenge: String, secret: String): Boolean {
        val data = json.decodeFromString<HostChallengeResponse>(body)
        if (data.signature.isBlank()) return false
        if (kotlin.math.abs(System.currentTimeMillis() - data.timestamp) > SyncAuth.TIMESTAMP_WINDOW_MS) {
            return false
        }
        val payload = "challenge:$callerDeviceId:${data.timestamp}:$challenge"
        val expected = hmacSha256Hex(secret.encodeToByteArray(), payload.encodeToByteArray())
        return constantTimeEquals(data.signature, expected)
    }

    private suspend fun applyPull(response: SyncResponse, since: Long) {
        // Client-response guard (contract): the shared validateSyncResponse
        // filters the raw lists before they touch the store, and the skip
        // counts are logged with their totals instead of being applied blind.
        val applied = applySyncResponse(repo, response, since)
        if (applied.skippedEntities > 0 || applied.skippedTombstones > 0) {
            Log.withTag("SyncClient").w {
                "Pull response guard skipped ${applied.skippedEntities} invalid entities and " +
                    "${applied.skippedTombstones} invalid tombstone IDs (since=$since)"
            }
        }
        // Persist pulled data immediately (audit D1): the pull cursor advances
        // after this response, so a crash before the debounced autosave would
        // skip re-fetching this data on the next sync.
        persistAfterApply?.invoke()
    }

    /**
     * Build the shared auth header: "timestamp:nonce:signature" from the
     * commonMain builders (single HMAC implementation for every platform,
     * shared SecureRandom-backed nonce; audit: HMAC triplication).
     */
    private fun authenticateRequest(deviceId: String, body: String): String {
        val secret = this.sharedSecret ?: throw IllegalStateException("No shared secret")
        return buildAuthHeader(
            deviceId = deviceId,
            body = body,
            secret = secret.encodeToByteArray(),
            timestamp = System.currentTimeMillis(),
            nonce = generateNonce()
        )
    }

    /** Constant-time comparison to avoid timing side channels on HMAC checks. */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) result = result or (a[i].code xor b[i].code)
        return result == 0
    }

    suspend fun connectWs(
        host: String,
        port: Int,
        callerDeviceId: String = this.deviceId,
        onDelta: (WsDelta) -> Unit
    ): WebSocketSession {
        if (!canSign) throw IllegalStateException("No shared secret, pair this device first")

        val authHeader = authenticateRequest(callerDeviceId, "ws")
        // Header-only auth: query strings leak into access logs and crash
        // reports. webSocketSession takes a request builder, so headers ride
        // the handshake instead of the URL. The ws scheme is fixed here:
        // SyncEndpoints.URL_SCHEME is the HTTP scheme used by every REST
        // endpoint above.
        return client.webSocketSession({
            url("ws://$host:$port${SyncEndpoints.SYNC_WS}")
            header(SyncAuthenticator.DEVICE_ID_HEADER, callerDeviceId)
            header(SyncAuthenticator.AUTH_HEADER, authHeader)
        })
    }

    suspend fun sendDelta(session: WebSocketSession, delta: WsDelta) {
        // Encode polymorphically: the server decodes frames as WsMessage and
        // needs the #type discriminator (concrete encoding silently failed
        // server-side decode; WS protocol bug fixed 2026-07-31).
        val text = wsJson.encodeToString(WsMessage.serializer(), delta)
        if (sharedSecret != null) {
            val aesKey = aesEncryptionKey(sharedSecret)
            val encrypted = base64Encode(encryptBody(text, aesKey))
            session.send(Frame.Text(encrypted))
        } else {
            session.send(Frame.Text(text))
        }
    }

    fun close() { client.close() }

    private suspend fun <T> retryWithBackoff(
        maxAttempts: Int = 3,
        operation: suspend () -> T
    ): Result<T> {
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                return Result.success(operation())
            } catch (e: Exception) {
                lastException = e
                if (e !is IOException && e !is TimeoutCancellationException) {
                    return Result.failure(e)
                }
                if (attempt < maxAttempts) {
                    val delayMs = 1000L * (1L shl (attempt - 1))
                    Log.withTag("SyncClient").w { "Attempt $attempt/$maxAttempts failed: ${e.message}, retrying in ${delayMs}ms" }
                    delay(delayMs)
                }
            }
        }
        return Result.failure(lastException ?: Exception("Retry exhausted"))
    }

    companion object {
        inline fun <reified T> changed(
            items: List<T>,
            since: Long,
            crossinline timestamp: (T) -> Long
        ): List<T> = items.filter { timestamp(it) > since }
    }
}
