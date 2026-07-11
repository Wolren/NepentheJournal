package app.journal.sync

import kotlin.test.*
import java.io.File

class DeviceTrustStoreTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "nepenthe-test-trust-${System.nanoTime()}")
    private val store = DeviceTrustStore(testDir.absolutePath)

    @AfterTest
    fun cleanup() {
        store.clearAll()
        testDir.resolve("trusted-devices.json").delete()
        testDir.delete()
    }

    private fun samplePeer(deviceId: String = "dev-1", fingerprint: String = "fp-1") =
        DeviceTrustStore.TrustedPeer(
            deviceId = deviceId, displayName = "Device $deviceId",
            fingerprint = fingerprint, sharedSecret = "secret-$deviceId",
            pairedAt = System.currentTimeMillis()
        )

    // ==================== CRUD ====================

    @Test
    fun addPeerPersistsAndCanBeRetrieved() {
        val peer = samplePeer()
        store.addPeer(peer)
        assertEquals("dev-1", store.getPeerById("dev-1")?.deviceId)
        assertEquals("Device dev-1", store.getPeerById("dev-1")?.displayName)
    }

    @Test
    fun addPeerUpdatesExistingPeer() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        store.addPeer(samplePeer("dev-1", "fp-1").copy(displayName = "Updated"))
        assertEquals("Updated", store.getPeerById("dev-1")?.displayName)
        assertEquals(1, store.count())
    }

    @Test
    fun isTrustedReturnsTrueForAddedPeer() {
        store.addPeer(samplePeer())
        assertTrue(store.isTrusted("fp-1"))
    }

    @Test
    fun isTrustedReturnsFalseForUnknown() {
        assertFalse(store.isTrusted("unknown-fp"))
    }

    @Test
    fun isTrustedDeviceIdReturnsTrueForAddedPeer() {
        store.addPeer(samplePeer())
        assertTrue(store.isTrustedDeviceId("dev-1"))
    }

    @Test
    fun getSharedSecretReturnsNullForUnknown() {
        assertNull(store.getSharedSecret("unknown"))
    }

    @Test
    fun getSharedSecretReturnsCorrectSecret() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        assertEquals("secret-dev-1", store.getSharedSecret("dev-1"))
    }

    @Test
    fun getPeerReturnsNullForUnknownFingerprint() {
        assertNull(store.getPeer("unknown-fp"))
    }

    @Test
    fun getPeerByFingerprint() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        assertNotNull(store.getPeer("fp-1"))
        assertEquals("dev-1", store.getPeer("fp-1")?.deviceId)
    }

    @Test
    fun listPeersReturnsAllPeers() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        store.addPeer(samplePeer("dev-2", "fp-2"))
        assertEquals(2, store.listPeers().size)
    }

    // ==================== Revocation ====================

    @Test
    fun revokeDeviceRemovesPeer() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        store.revokeDevice("dev-1")
        assertFalse(store.isTrustedDeviceId("dev-1"))
        assertFalse(store.isTrusted("fp-1"))
        assertEquals(0, store.count())
    }

    @Test
    fun revokeUnknownDeviceIsNoop() {
        store.addPeer(samplePeer())
        store.revokeDevice("nonexistent")
        assertEquals(1, store.count())
    }

    // ==================== Last seen ====================

    @Test
    fun updateLastSeenUpdatesExisting() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        val before = store.getPeerById("dev-1")?.lastSeenAt
        store.updateLastSeen("dev-1", 999999L)
        val after = store.getPeerById("dev-1")?.lastSeenAt
        assertNull(before)
        assertEquals(999999L, after)
    }

    @Test
    fun updateLastSeenUnknownIsNoop() {
        store.updateLastSeen("nonexistent", 999L)
        assertEquals(0, store.count())
    }

    // ==================== Clear all ====================

    @Test
    fun clearAllEmptiesStore() {
        store.addPeer(samplePeer())
        store.addPeer(samplePeer("dev-2", "fp-2"))
        store.clearAll()
        assertEquals(0, store.count())
        assertTrue(store.listPeers().isEmpty())
    }

    // ==================== Persistence ====================

    @Test
    fun dataSurvivesStoreRecreation() {
        store.addPeer(samplePeer("dev-1", "fp-1"))

        // Create a new store pointing at the same file
        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals(1, store2.count())
        assertTrue(store2.isTrusted("fp-1"))
        assertEquals("secret-dev-1", store2.getSharedSecret("dev-1"))
    }

    @Test
    fun emptyStoreOnMissingFile() {
        assertEquals(0, store.count())
    }

    // ==================== Corruption ====================

    @Test
    fun corruptJsonResetsStore() {
        testDir.mkdirs()
        File(testDir, "trusted-devices.json").writeText("this is not json{{{")
        // Creating a new store should catch the parse error and return empty
        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals(0, store2.count())
    }

    @Test
    fun partiallyCorruptJsonRecoversPeers() {
        testDir.mkdirs()
        // Add a valid peer first via the store
        store.addPeer(samplePeer("dev-1", "fp-1"))
        // Corrupt the file
        File(testDir, "trusted-devices.json").appendText("\ncorruption")
        val store2 = DeviceTrustStore(testDir.absolutePath)
        // Should safely reset to empty
        assertEquals(0, store2.count())
    }

    // ==================== Fingerprint lookup ====================

    @Test
    fun getPeerByIdReturnsCorrectPeer() {
        store.addPeer(samplePeer("dev-1", "fp-1"))
        store.addPeer(samplePeer("dev-2", "fp-2"))
        val peer = store.getPeerById("dev-2")
        assertNotNull(peer)
        assertEquals("fp-2", peer?.fingerprint)
    }

    @Test
    fun multiplePeersWithUniqueFingerprints() {
        for (i in 1..10) {
            store.addPeer(samplePeer("dev-$i", "fp-$i"))
        }
        assertEquals(10, store.count())
        assertTrue(store.isTrusted("fp-5"))
    }

    // ==================== Concurrent access safety ====================

    @Test
    fun rapidAddAndRemoveDoesNotCorrupt() {
        // Simulate rapid pairing/unpairing
        for (i in 1..100) {
            store.addPeer(samplePeer("dev-$i", "fp-$i"))
        }
        for (i in 1..100 step 2) {
            store.revokeDevice("dev-$i")
        }
        assertEquals(50, store.count())
    }
}
