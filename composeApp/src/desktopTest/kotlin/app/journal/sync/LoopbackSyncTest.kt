package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import app.journal.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two transports, one process, one port: a real host serves the real router and
 * a real client pairs over ECDH and pulls. This is the closest an in-process
 * test gets to two devices talking, and it runs on every JVM target.
 */
class LoopbackSyncTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "nep-loopback-${System.nanoTime()}")

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun config(deviceId: String, port: Int) = SyncConfig(
        id = "cfg:$deviceId",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = deviceId,
        deviceId = deviceId,
        displayName = deviceId,
        hostingEnabled = true,
        listenerPort = port,
    )

    @Test
    fun aClientPairsOverLoopbackAndPullsWhatTheHostWrote() = runBlocking {
        val hostDir = File(root, "host").apply { mkdirs() }
        val clientDir = File(root, "client").apply { mkdirs() }
        val hostRepo = JournalRepository()
        val clientRepo = JournalRepository()
        val host = SyncTransport(hostRepo, hostDir.absolutePath)
        val client = SyncTransport(clientRepo, clientDir.absolutePath)
        val port = 51234

        hostRepo.upsertSession(
            Session(
                id = "s:loopback",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                deviceOrigin = "host",
                title = "LOOPBACK-ARRIVED",
                startTime = 1_700_000_000_000L,
            )
        )

        val hosting = host.startHosting(config("loopback-host", port))
        assertTrue(hosting.isSuccess, "hosting failed: ${hosting.exceptionOrNull()}")
        try {
            val token = host.observeStatus().first().pairingToken
            assertTrue(!token.isNullOrBlank(), "the host must publish a pairing token")

            val paired = client.connectManually("127.0.0.1", port, token)
            assertTrue(paired.isSuccess, "pairing and sync failed: ${paired.exceptionOrNull()}")

            withTimeout(20_000) {
                clientRepo.sessions.first { sessions -> sessions.any { it.title == "LOOPBACK-ARRIVED" } }
            }
            assertEquals(1, host.trustedDevices().size, "the host must trust exactly one peer")
        } finally {
            host.stopHosting()
            client.stopHosting()
        }
    }
}
