package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JVM host serves the same routes on a pinned HTTPS connector next to the legacy
 * HTTP port. A client with a trusted fingerprint uses only TLS; an unpaired client
 * reads HostInfo over HTTP and upgrades for every later call.
 */
class TlsSyncIntegrationTest {

    private val testDir = File(
        System.getProperty("java.io.tmpdir"),
        "nepenthe-test-tls-sync-${System.nanoTime()}",
    )
    private val repo = JournalRepository()
    private val trustStore = DeviceTrustStore(testDir.absolutePath)
    private val authenticator = SyncAuthenticator(trustStore)

    @BeforeTest
    fun setUp() {
        testDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        testDir.deleteRecursively()
    }

    private suspend fun startServer(): Pair<KtorSyncServer, HostingInfo> {
        val server = KtorSyncServer(
            repo = repo,
            port = 0,
            tlsIdentity = TlsIdentityManager(testDir.absolutePath),
            ecdhIdentity = EcdhIdentityManager(testDir.absolutePath),
            trustStore = trustStore,
            authenticator = authenticator,
            onConnection = {},
        )
        return server to server.start()
    }

    @Test
    fun hostInfoAdvertisesTheTlsPortAndFingerprint() = runBlocking {
        val (server, info) = startServer()
        try {
            assertTrue(info.tlsPort > 0, "a JVM host must bind a TLS connector")
            val client = KtorSyncClient(repo = JournalRepository(), deviceId = "tls-client")
            val host = client.requestHostInfo("127.0.0.1", info.port).getOrThrow()
            assertEquals(info.tlsPort, host.tlsPort)
            assertEquals(info.fingerprint, host.fingerprint)
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aPinnedClientTalksToTheTlsConnector() = runBlocking {
        val (server, info) = startServer()
        try {
            val client = KtorSyncClient(
                repo = JournalRepository(), deviceId = "tls-client",
                trustedFingerprint = info.fingerprint, tlsPort = info.tlsPort,
            )
            val host = client.requestHostInfo("127.0.0.1", info.port)
            assertTrue(host.isSuccess, "pinned HTTPS must reach the host: ${host.exceptionOrNull()?.message}")
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun aWrongPinIsRejected() = runBlocking {
        val (server, info) = startServer()
        try {
            val client = KtorSyncClient(
                repo = JournalRepository(), deviceId = "tls-client",
                trustedFingerprint = "0".repeat(64), tlsPort = info.tlsPort,
            )
            val host = client.requestHostInfo("127.0.0.1", info.port)
            assertTrue(host.isFailure, "a certificate with a different fingerprint must be rejected")
            val message = host.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("Certificate pinning failed"), "got: $message")
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun anUnpinnedBootstrapUpgradesToTls() = runBlocking {
        val (server, info) = startServer()
        try {
            val client = KtorSyncClient(repo = JournalRepository(), deviceId = "tls-client")
            assertTrue(client.requestHostInfo("127.0.0.1", info.port).isSuccess,
                "the first read bootstraps over the advertised HTTP port")
            assertTrue(client.requestHostInfo("127.0.0.1", info.port).isSuccess,
                "the adopted TLS port must serve the repeat read")
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun pairingAndPushRunOverTls() = runBlocking {
        val (server, info) = startServer()
        try {
            val clientRepo = JournalRepository()
            clientRepo.upsertSession(Session(
                id = "s:tls", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
                deviceOrigin = "tls-client", title = "TLS session", startTime = 1_700_000_000_000L,
            ))
            val client = KtorSyncClient(
                repo = clientRepo, deviceId = "tls-client", deviceFingerprint = "client-fp",
                deviceName = "TLS Client", trustedFingerprint = info.fingerprint, tlsPort = info.tlsPort,
            )
            val host = client.requestHostInfo("127.0.0.1", info.port).getOrThrow()
            val pairing = client.completePairing(
                host = "127.0.0.1", port = info.port,
                token = authenticator.generatePairingToken(),
                clientDeviceId = "tls-client", clientDeviceName = "TLS Client",
                clientFingerprint = "client-fp",
                hostEcdhPublicKeyB64 = host.ecdhPublicKeyB64,
            )
            assertTrue(pairing.isSuccess, "pairing over TLS must succeed: ${pairing.exceptionOrNull()?.message}")
            val secret = pairing.getOrThrow().sharedSecret
            client.close()

            val syncClient = KtorSyncClient(
                repo = clientRepo, deviceId = "tls-client", deviceFingerprint = "client-fp",
                deviceName = "TLS Client", sharedSecret = secret,
                trustedFingerprint = info.fingerprint, tlsPort = info.tlsPort,
            )
            val push = syncClient.pushChanges(
                host = "127.0.0.1", port = info.port,
                deviceId = "tls-client", deviceName = "TLS Client", since = 0L,
            )
            assertTrue(push.isSuccess, "push over TLS must succeed: ${push.exceptionOrNull()?.message}")
            assertTrue(repo.sessions.value.any { it.id == "s:tls" },
                "the host must store the session pushed over TLS")
            syncClient.close()
        } finally {
            server.stop()
        }
    }
}
