package app.journal.sync

import app.journal.data.AppJson
import app.journal.data.JournalRepository
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
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 */
class KtorSyncClient(
    private val repo: JournalRepository,
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

    // Plain HTTP client — no TLS. Encryption + HMAC secures data on LAN.
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    // ---- Pairing endpoints (no auth needed) ----

    suspend fun requestHostInfo(host: String, port: Int): Result<HostInfo> =
        withContext(Dispatchers.IO) {
            try {
                val response = client.get("http://$host:$port/pairing/start")
                val info = response.body<HostInfo>()
                Result.success(info)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Complete pairing with a host using a user-entered token. */
    suspend fun completePairing(
        host: String, port: Int,
        token: String,
        clientDeviceId: String,
        clientDeviceName: String,
        clientFingerprint: String
    ): Result<DevicePairingResult> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("http://$host:$port/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody(PairingVerifyRequest(
                    token = token,
                    clientDeviceId = clientDeviceId,
                    clientDeviceName = clientDeviceName,
                    clientFingerprint = clientFingerprint
                ))
            }
            val result = response.body<PairingResultResponse>()
            if (result.success) {
                Result.success(DevicePairingResult(
                    deviceId = result.deviceId ?: "",
                    sharedSecret = result.sharedSecret ?: "",
                    hostDeviceId = result.hostDeviceId ?: "",
                    hostDeviceName = result.hostDeviceName ?: "",
                    hostFingerprint = result.hostFingerprint ?: ""
                ))
            } else {
                Result.failure(Exception(result.error ?: "Pairing failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ---- Authenticated sync endpoints ----

    suspend fun pushChanges(
        host: String, port: Int,
        deviceId: String, deviceName: String,
        since: Long
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        if (!canSign) return@withContext Result.failure(Exception("Not paired"))

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
            customUnits = changed(repo.customUnits.value, since) { it.updatedAt }
        )

        val bodyText = json.encodeToString(batch)
        // Encrypt body with AES-256-GCM, then base64-encode for transport
        val aesKey = aesEncryptionKey(sharedSecret!!)
        val encryptedBody = base64Encode(encryptBody(bodyText, aesKey))
        val authHeader = authenticateRequest(deviceId, encryptedBody)

        val response = retryWithBackoff {
            val httpResponse = client.post("http://$host:$port/sync/push") {
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

        response.fold(
            onSuccess = { syncResponse ->
                try {
                    applyPull(syncResponse)
                } catch (e: Exception) {
                    Log.withTag("SyncClient").e(e) { "applyPull failed after successful push" }
                    return@withContext Result.failure(e)
                }
                if (syncResponse.success) Result.success(syncResponse)
                else Result.failure(Exception(syncResponse.error ?: "Push failed"))
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun pullChanges(
        host: String, port: Int,
        since: Long
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        if (!canSign) return@withContext Result.failure(Exception("Not paired"))

        val uri = "/sync/pull?since=$since"
        val authHeader = authenticateRequest(deviceId, uri)

        val response = retryWithBackoff {
            val httpResponse = client.get("http://$host:$port$uri") {
                header(SyncAuthenticator.DEVICE_ID_HEADER, deviceId)
                header(SyncAuthenticator.AUTH_HEADER, authHeader)
            }
            val rawBody = httpResponse.bodyAsText()
            val aesKey = aesEncryptionKey(sharedSecret!!)
            val decrypted = decryptBody(base64Decode(rawBody), aesKey)
            json.decodeFromString<SyncResponse>(decrypted)
        }

        response.fold(
            onSuccess = { syncResponse ->
                try {
                    applyPull(syncResponse)
                } catch (e: Exception) {
                    Log.withTag("SyncClient").e(e) { "applyPull failed after successful pull" }
                    return@withContext Result.failure(e)
                }
                if (syncResponse.success) Result.success(syncResponse)
                else Result.failure(Exception(syncResponse.error ?: "Pull failed"))
            },
            onFailure = { Result.failure(it) }
        )
    }

    suspend fun fetchHostInfo(host: String, port: Int): Result<HostInfo> =
        withContext(Dispatchers.IO) {
            try {
                client.get("http://$host:$port/info").body<HostInfo>().let { Result.success(it) }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Prove the host knows [secret] before re-using a stored pairing secret.
     * Sends a fresh random challenge to /auth/verify and checks the HMAC
     * response. A fingerprint-spoofed host (fake mDNS service) cannot answer
     * correctly, so a stored secret is never handed to an impostor.
     */
    suspend fun verifyHostIdentity(host: String, port: Int, callerDeviceId: String, secret: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val challenge = generateNonce()
                val resp = client.get("http://$host:$port/auth/verify?deviceId=$callerDeviceId&challenge=$challenge")
                if (resp.status != HttpStatusCode.OK) return@withContext false
                val data = json.decodeFromString<HostChallengeResponse>(resp.bodyAsText())
                if (data.signature.isBlank()) return@withContext false
                if (kotlin.math.abs(System.currentTimeMillis() - data.timestamp) > SyncAuth.TIMESTAMP_WINDOW_MS) {
                    return@withContext false
                }
                val expected = hmac(secret, "challenge:$callerDeviceId:${data.timestamp}:$challenge")
                constantTimeEquals(data.signature, expected)
            } catch (e: Exception) {
                false
            }
        }

    private suspend fun applyPull(response: SyncResponse) {
        repo.applyBatch(
            sessions = response.sessions,
            doses = response.doses,
            substances = response.substances,
            effects = response.effects,
            interactions = response.interactions,
            notes = response.notes,
            timelineEvents = response.timelineEvents,
            customUnits = response.customUnits,
            lastWriterWins = true
        )
        // Persist pulled data immediately (audit D1): the pull cursor advances
        // after this response, so a crash before the debounced autosave would
        // skip re-fetching this data on the next sync.
        persistAfterApply?.invoke()
    }

    private fun authenticateRequest(deviceId: String, body: String): String {
        val secret = this.sharedSecret ?: throw IllegalStateException("No shared secret")
        val timestamp = System.currentTimeMillis()
        val nonce = generateNonce()
        val signature = hmac(secret, "$deviceId:$timestamp:$nonce:$body")
        return "$timestamp:$nonce:$signature"
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hmac(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
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
        if (!canSign) throw IllegalStateException("No shared secret — pair this device first")

        val authHeader = authenticateRequest(callerDeviceId, "ws")
        val wsUrl = "ws://$host:$port/sync/ws?deviceId=$callerDeviceId&auth=$authHeader"
        return client.webSocketSession(wsUrl)
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
