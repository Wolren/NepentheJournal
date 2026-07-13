package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.SyncConfig
import kotlin.test.*
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

class SyncTransportTest {

    private val testDir = File(
        System.getProperty("java.io.tmpdir"),
        "nepenthe-test-transport-${System.nanoTime()}"
    )

    private val repo = JournalRepository()

    private fun testConfig(port: Int = 18426) = SyncConfig(
        id = "test-config", createdAt = 1, updatedAt = 1,
        deviceOrigin = "test", deviceId = "test-device",
        displayName = "Test", listenerPort = port
    )

    @AfterTest
    fun cleanup() {
        testDir.resolve("identity.p12").delete()
        testDir.resolve("trusted-devices.json").delete()
        testDir.delete()
    }

    @Test
    fun startHostingReturnsSuccessWithAddress() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            try {
                val result = transport.startHosting(testConfig())
                assertTrue(result.isSuccess, "startHosting should succeed")
                val info = result.getOrThrow()
                assertTrue(info.address.isNotEmpty(), "should have an address")
                assertTrue(info.port > 0, "port should be > 0, got ${info.port}")
                assertTrue(info.fingerprint.length == 64, "fingerprint should be 64 hex chars")
            } finally {
                transport.stopHosting()
            }
        }
    }

    @Test
    fun startHostingGeneratesPairingToken() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            try {
                transport.startHosting(testConfig())
                val status = transport.observeStatus().first()
                assertNotNull(status.pairingToken, "pairing token should be generated on start")
                assertTrue(status.pairingToken!!.length in 6..12,
                    "token should be 6-12 chars, got ${status.pairingToken!!.length}")
            } finally {
                transport.stopHosting()
            }
        }
    }

    @Test
    fun stopHostingResetsStatus() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            transport.startHosting(testConfig())
            transport.stopHosting()
            val status = transport.observeStatus().first()
            assertFalse(status.isHosting)
            assertNull(status.hostAddress)
            assertNull(status.pairingToken)
        }
    }

    @Test
    fun trustedDevicesInitiallyEmpty() {
        val transport = SyncTransport(repo, testDir.absolutePath)
        assertTrue(transport.trustedDevices().isEmpty())
    }

    @Test
    fun revokeDeviceDoesNotThrowWhenEmpty() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            transport.revokeDevice("nonexistent")
        }
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
            try {
                val result = transport.connectManually(
                    host = "127.0.0.1", port = 9999, token = "ABC123"
                )
                assertTrue(result.isFailure)
            } finally {
                transport.stopHosting()
            }
        }
    }

    @Test
    fun startHostingTracksStatusFields() {
        runBlocking {
            val transport = SyncTransport(repo, testDir.absolutePath)
            try {
                transport.startHosting(testConfig())
                val status = transport.observeStatus().first()
                assertEquals(0, status.pairedDeviceCount)
                assertNull(status.lastError)
                assertTrue(status.isHosting)
                assertNotNull(status.hostAddress)
            } finally {
                transport.stopHosting()
            }
        }
    }
}
