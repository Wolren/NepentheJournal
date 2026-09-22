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

class KtorSyncServer(
    private val repo: IJournalRepository,
    private val port: Int,
    private val tlsIdentity: TlsIdentityManager,
    private val ecdhIdentity: EcdhIdentityManager,
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

    /** Port the server actually bound. With the configured port 0 (ephemeral,
     *  used by tests to avoid sibling-JVM collisions) this is NOT the
     *  configured value, so [start] records the resolved connector port. */
    val actualPort: Int get() = boundPort ?: port
    private var boundPort: Int? = null

    suspend fun start(): HostingInfo {
        try {
            val fp = fingerprint
            val name = deviceName

            val router = SyncServerRouter(
                repo = repo,
                trustStore = trustStore,
                authenticator = authenticator,
                ecdhIdentity = ecdhIdentity,
                onConnection = onConnection,
                deviceId = deviceId,
                deviceName = name,
                fingerprint = fp,
                persistAfterApply = persistAfterApply
            )
            this.router = router

            runInterruptible {
                server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
                    router.installRouting(this)
                }
                server!!.start(wait = false)
            }
            // Report what Netty really bound: identical to the configured
            // port in production, the ephemeral port when port == 0.
            val resolved = try {
                server!!.engine.resolvedConnectors().firstOrNull()?.port
            } catch (e: Exception) {
                Log.withTag("KtorSyncServer").w { "Could not resolve bound port (${e.message}); using configured $port" }
                null
            }
            boundPort = resolved ?: port
            val lanIp = resolveLocalIpV4() ?: "127.0.0.1"
            Log.withTag("KtorSyncServer").i { "Server started on $lanIp:$boundPort (fingerprint=$fp)" }
            return HostingInfo(lanIp, boundPort!!, fp)
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
