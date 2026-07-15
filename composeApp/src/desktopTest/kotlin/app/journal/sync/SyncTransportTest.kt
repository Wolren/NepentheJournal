package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import kotlin.test.*
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Tests for SyncTransport state management.
 *
 * Server-starting tests (startHosting, syncWith, connectManually) are covered
 * by [KtorSyncServerIntegrationTest] which uses Ktor's in-process testApplication{}
 * — no real server or port binding needed. This class tests only the state
 * management layer of SyncTransport.
 */
class SyncTransportTest {

    private val testDir = File(
        System.getProperty("java.io.tmpdir"),
        "nepenthe-test-transport-${System.nanoTime()}"
    )

    private val repo = JournalRepository()

    @AfterTest
    fun cleanup() {
        testDir.resolve("trusted-devices.json").delete()
        testDir.delete()
    }

    @Test
    fun trustedDevicesInitiallyEmpty() {
        val transport = SyncTransport(repo, testDir.absolutePath)
        assertTrue(transport.trustedDevices().isEmpty())
    }

    @Test
    fun revokeDeviceDoesNotThrowWhenEmpty() {
        val transport = SyncTransport(repo, testDir.absolutePath)
        transport.revokeDevice("nonexistent")
    }

    @Test
    fun statusFlowStartsWithDefaults() {
        val status = runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            transport.observeStatus().first()
        }
        assertFalse(status.isHosting)
        assertNull(status.hostAddress)
        assertNull(status.lastSyncAt)
        assertEquals(0, status.pairedDeviceCount)
        assertEquals(0, status.activeConnections.size)
        assertEquals(0, status.pendingConflicts)
        assertNull(status.lastError)
    }

    @Test
    fun connectToUnreachableHostReturnsFailure() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            val result = transport.connectManually(
                host = "127.0.0.1", port = 9999, token = "ABC123"
            )
            assertTrue(result.isFailure)
        }
    }
}
