package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.model.SyncConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS transport, hosted and paired against itself over loopback: the real
 * IosSyncServerRouter, the real ECDH flight and the real pull. iOS sync had no
 * runtime evidence at all before this test.
 */
@OptIn(ExperimentalForeignApi::class)
class IosSyncLoopbackTest {

    private val root = NSTemporaryDirectory() + "nep-ios-loopback-" + NSUUID().UUIDString()

    @AfterTest
    fun tearDown() {
        platform.Foundation.NSFileManager.defaultManager.removeItemAtPath(root, null)
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
        val hostRepo = JournalRepository()
        val clientRepo = JournalRepository()
        val host = IosSyncTransport(hostRepo, "$root/host")
        val client = IosSyncTransport(clientRepo, "$root/client")
        val port = 51234

        hostRepo.upsertSession(
            Session(
                id = "s:ios-loopback",
                createdAt = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L,
                deviceOrigin = "host",
                title = "LOOPBACK-ARRIVED",
                startTime = 1_700_000_000_000L,
            )
        )

        val hosting = host.startHosting(config("ios-loopback-host", port))
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
