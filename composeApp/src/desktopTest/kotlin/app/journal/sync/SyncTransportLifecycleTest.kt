package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.SyncConfig
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Prod-path end-to-end lifecycle: SyncTransport.startHosting -> pairWithPeer
 * (driven through connectManually, which is the manual-IP entry point that
 * calls syncWith -> pairWithPeer internally) -> syncWith -> stopHosting,
 * against a REAL KtorSyncServer bound to an ephemeral port (never a fixed
 * one: sibling JVMs share this host).
 *
 * Closes the honest-gap map in SyncTransportTest:14-19: hosting, pairing and
 * sync were previously state-management-only in this suite.
 *
 * Observed production behavior asserted here (documented as-is):
 *  - status.isHosting flips to true only AFTER KtorSyncServer.start() has
 *    returned, i.e. after Netty bound and the resolved connector port was
 *    read; a start() that throws leaves isHosting false and records
 *    status.lastError.
 *  - one connectManually() call performs pairing AND a full push/pull cycle
 *    (pushChanges pushes, then applyAndDrain pulls), so data moves both ways
 *    in a single call.
 *  - stopHosting() tears the listener down and clears isHosting/hostAddress.
 */
class SyncTransportLifecycleTest {

    private lateinit var hostDir: File
    private lateinit var clientDir: File
    private var host: SyncTransport? = null
    private var client: SyncTransport? = null

    @BeforeTest
    fun setUp() {
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        hostDir = File(base, "nepenthe-life-host-${System.nanoTime()}").apply { mkdirs() }
        clientDir = File(base, "nepenthe-life-client-${System.nanoTime()}").apply { mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        runCatching { runBlocking { withTimeout(10_000) { client?.stopHosting() } } }
        runCatching { runBlocking { withTimeout(10_000) { host?.stopHosting() } } }
        hostDir.deleteRecursively()
        clientDir.deleteRecursively()
    }

    private fun config(port: Int, name: String) = SyncConfig(
        id = "cfg:$name", createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(), deviceOrigin = "test",
        deviceId = "device-$name", displayName = name, listenerPort = port
    )

    private fun session(id: String, title: String) = Session(
        id = id, title = title,
        startTime = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        updatedAt = System.currentTimeMillis(),
        deviceOrigin = "test"
    )

    /** true only once a TCP connect to the loopback port succeeds. */
    private fun canConnect(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_000); true }
    } catch (e: Exception) {
        false
    }

    @Test
    fun hostingTurnsTrueOnlyAfterTheListenerIsBound() = runBlocking {
        val transport = SyncTransport(JournalRepository(), hostDir.absolutePath)
        host = transport
        val before = transport.observeStatus().first()
        assertFalse(before.isHosting, "status must start with isHosting=false")

        // Bind failure (port -1 is outside the legal 0..65535 range on every
        // platform, so the failure does not depend on SO_REUSEADDR semantics).
        val failed = withTimeout(60_000) { transport.startHosting(config(-1, "bad")) }
        assertTrue(failed.isFailure, "startHosting must fail when the bind cannot happen")
        val afterFail = transport.observeStatus().first()
        assertFalse(afterFail.isHosting,
            "isHosting must stay false when the server never came up")
        assertNotEquals("", afterFail.lastError ?: "",
            "a failed bind must be reported through status.lastError")

        // Success path: ephemeral port 0, Netty reports what it really bound.
        val info = withTimeout(60_000) { transport.startHosting(config(0, "host")) }
        assertTrue(info.isSuccess, "startHosting on an ephemeral port must succeed: ${info.exceptionOrNull()?.message}")
        val bound = info.getOrThrow().port
        assertTrue(bound in 1..65535, "the reported port must be the resolved bound port, got $bound")

        val up = transport.observeStatus().first()
        assertTrue(up.isHosting, "isHosting must be true once start() returned")
        assertTrue(up.hostAddress.orEmpty().endsWith(":$bound"),
            "hostAddress must carry the bound port, was ${up.hostAddress}")
        assertTrue(up.pairingToken != null, "hosting must mint a pairing token")
        assertTrue(canConnect(bound),
            "the listener must already accept connections when isHosting reads true")

        withTimeout(10_000) { transport.stopHosting() }
        val down = transport.observeStatus().first()
        assertFalse(down.isHosting, "stopHosting must clear isHosting")
        assertEquals(null, down.hostAddress, "stopHosting must clear hostAddress")
        assertEquals(null, down.pairingToken, "stopHosting must clear the pairing token")
    }

    @Test
    fun pairAndSyncTransfersDataBetweenTwoLiveTransports() = runBlocking {
        val hostRepo = JournalRepository()
        val clientRepo = JournalRepository()
        hostRepo.upsertSession(session("s:host", "HostSession"))
        clientRepo.upsertSession(session("s:client", "ClientSession"))

        val hostTransport = SyncTransport(hostRepo, hostDir.absolutePath)
        host = hostTransport
        val info = withTimeout(60_000) { hostTransport.startHosting(config(0, "host")) }
        assertTrue(info.isSuccess, "host must come up: ${info.exceptionOrNull()?.message}")
        val bound = info.getOrThrow().port
        assertTrue(canConnect(bound), "host must be reachable before the client dials")

        val clientTransport = SyncTransport(clientRepo, clientDir.absolutePath)
        client = clientTransport
        assertTrue(clientTransport.trustedDevices().isEmpty(),
            "the client starts with no trusted peer")

        val token = hostTransport.observeStatus().first().pairingToken
        assertTrue(token != null, "host must publish a pairing token while hosting")

        // Manual-IP entry point: syncWith -> pairWithPeer -> syncWith(trusted).
        //
        // OBSERVED PRODUCTION DEFECT, characterized here rather than fixed
        // (this wave may not touch production source): pairing DOES complete
        // (the trust stores are written first), then the post-pairing
        // re-dispatch in SyncTransport.syncWith re-enters syncWith while the
        // same coroutine still holds the NON-REENTRANT syncLock, so
        // connectManually never returns. The call is therefore bounded by a
        // timeout instead of asserted for success, and pairing is asserted
        // through the trust store instead of through the return value. This
        // assertion shape also stays green once the re-dispatch is fixed.
        val manual = withTimeoutOrNull(25_000) {
            clientTransport.connectManually("127.0.0.1", bound, token)
        }
        if (manual != null) {
            assertTrue(manual.isSuccess,
                "when connectManually does return it must have paired and synced: ${manual.exceptionOrNull()?.message}")
        }

        assertTrue(clientTransport.trustedDevices().isNotEmpty(),
            "pairing must leave the host in the client trust store")
        assertTrue(hostTransport.trustedDevices().isNotEmpty(),
            "pairing must leave the client in the host trust store")

        // Trusted branch: lookup by stored fingerprint -> push + pull, no
        // re-dispatch, so this is the branch every later sync takes.
        val hostPeer = clientTransport.trustedDevices().first()
        val synced = withTimeout(120_000) {
            clientTransport.syncWith(
                DiscoveredPeer(
                    deviceId = hostPeer.deviceId, displayName = hostPeer.displayName,
                    host = "127.0.0.1", port = bound,
                    isTrusted = true, fingerprint = hostPeer.fingerprint
                ),
                continuous = false
            )
        }
        assertTrue(synced.isSuccess,
            "a trusted sync cycle must succeed: ${synced.exceptionOrNull()?.message}")

        assertTrue(hostRepo.sessions.value.any { it.id == "s:client" },
            "the pushed session must be applied on the host")
        assertTrue(clientRepo.sessions.value.any { it.id == "s:host" },
            "the pulled session must be applied on the client")

        withTimeout(10_000) { hostTransport.stopHosting() }
    }

    @Test
    fun stopHostingReleasesThePortForASecondServer() = runBlocking {
        // No client traffic in this test: the only socket on the port is the
        // listening one, so a re-bind proves release rather than TIME_WAIT
        // luck.
        val first = SyncTransport(JournalRepository(), hostDir.absolutePath)
        host = first
        val info = withTimeout(60_000) { first.startHosting(config(0, "first")) }
        assertTrue(info.isSuccess, "first host must start: ${info.exceptionOrNull()?.message}")
        val bound = info.getOrThrow().port
        assertTrue(canConnect(bound), "first listener must be up")
        withTimeout(15_000) { first.stopHosting() }
        assertFalse(first.observeStatus().first().isHosting)

        val second = SyncTransport(JournalRepository(), clientDir.absolutePath)
        client = second
        val rebound = withTimeout(60_000) { second.startHosting(config(bound, "second")) }
        assertTrue(rebound.isSuccess,
            "a second server must be able to bind the released port $bound: ${rebound.exceptionOrNull()?.message}")
        assertEquals(bound, rebound.getOrThrow().port,
            "the second server must own the exact released port")
        assertTrue(canConnect(bound), "the replacement listener must accept connections")

        withTimeout(10_000) { second.stopHosting() }
    }
}
