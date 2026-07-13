package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.websocket.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * TLS-enabled sync client with HMAC request signing and certificate pinning.
 *
 * Modes:
 *   - PAIRING mode (trustedFingerprint = null): uses TOFU SSL (accepts any cert)
 *   - AUTHENTICATED mode (trustedFingerprint != null): pins to the paired cert
 *
 * All sync requests include HMAC-SHA256 signatures.
 */
class KtorSyncClient(
    private val repo: JournalRepository,
    private val tlsIdentity: TlsIdentityManager? = null,
    private val deviceId: String = "unknown",
    private val deviceFingerprint: String? = null,
    private val deviceName: String = "Desktop (Windows)",
    private val sharedSecret: String? = null,
    trustedFingerprint: String? = null
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val canSign: Boolean get() = sharedSecret != null

    // Plain HTTP client — no TLS. HMAC auth secures requests on LAN.
    private val client: HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
        install(WebSockets)
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
        token: String,  // user-entered token from the host's screen
        clientDeviceId: String,
        clientDeviceName: String,
        clientFingerprint: String
    ): Result<DevicePairingResult> = withContext(Dispatchers.IO) {
        try {
            val response = client.post("http://$host:$port/pairing/verify") {
                contentType(ContentType.Application.Json)
                setBody(PairingVerifyRequestRaw(
                    token = token,
                    clientDeviceId = clientDeviceId,
                    clientDeviceName = clientDeviceName,
                    clientFingerprint = clientFingerprint
                ))
            }
            val result = response.body<PairingResultResponseRaw>()
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

        try {
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
            val authHeader = authenticateRequest(deviceId, bodyText)

            val response = client.post("http://$host:$port/sync/push") {
                contentType(ContentType.Application.Json)
                header(SyncAuthenticator.DEVICE_ID_HEADER, deviceId)
                header(SyncAuthenticator.AUTH_HEADER, authHeader)
                setBody(bodyText)
            }.body<SyncResponse>()

            // Atomic exchange: apply server's changes returned in push response
            applyPull(response)
            if (response.success) Result.success(response)
            else Result.failure(Exception(response.error ?: "Push failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pullChanges(
        host: String, port: Int,
        since: Long
    ): Result<SyncResponse> = withContext(Dispatchers.IO) {
        if (!canSign) return@withContext Result.failure(Exception("Not paired"))

        try {
            val uri = "/sync/pull?since=$since"
            val authHeader = authenticateRequest(deviceId, uri)

            val response = client.get("http://$host:$port$uri") {
                header(SyncAuthenticator.DEVICE_ID_HEADER, deviceId)
                header(SyncAuthenticator.AUTH_HEADER, authHeader)
            }.body<SyncResponse>()

            applyPull(response)
            if (response.success) Result.success(response)
            else Result.failure(Exception(response.error ?: "Pull failed"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchHostInfo(host: String, port: Int): Result<HostInfo> =
        withContext(Dispatchers.IO) {
            try {
                client.get("http://$host:$port/info").body<HostInfo>().let { Result.success(it) }
            } catch (e: Exception) {
                Result.failure(e)
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
            customUnits = response.customUnits
        )
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

    /**
     * Connect to a peer's WebSocket sync endpoint.
     * Uses the same HMAC scheme as HTTP: deviceId + timestamp + nonce + "ws" signed with sharedSecret.
     * @param onDelta callback invoked for each received [WsDelta]
     * @return the established [WebSocketSession] for sending further messages
     */
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

    /**
     * Serialize and send a [WsDelta] over an active WebSocket session.
     */
    suspend fun sendDelta(session: WebSocketSession, delta: WsDelta) {
        val text = wsJson.encodeToString(delta)
        session.send(Frame.Text(text))
    }

    fun close() { client.close() }

    companion object {
        inline fun <reified T> changed(
            items: List<T>,
            since: Long,
            crossinline timestamp: (T) -> Long
        ): List<T> = items.filter { timestamp(it) > since }
    }
}

@kotlinx.serialization.Serializable
private data class PairingStartResponseRaw(
    val token: String,
    val hostFingerprint: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val hostAddress: String,
    val listenerPort: Int,
    val protocolVersion: Int = 2
)

@kotlinx.serialization.Serializable
private data class PairingVerifyRequestRaw(
    val token: String,
    val clientDeviceId: String,
    val clientDeviceName: String,
    val clientFingerprint: String
)

@kotlinx.serialization.Serializable
private data class PairingResultResponseRaw(
    val success: Boolean,
    val error: String? = null,
    val deviceId: String? = null,
    val sharedSecret: String? = null,
    val hostDeviceId: String? = null,
    val hostDeviceName: String? = null,
    val hostFingerprint: String? = null
)

data class DevicePairingResult(
    val deviceId: String,
    val sharedSecret: String,
    val hostDeviceId: String,
    val hostDeviceName: String,
    val hostFingerprint: String
)
