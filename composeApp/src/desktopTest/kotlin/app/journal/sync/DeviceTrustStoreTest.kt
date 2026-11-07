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


    @Test
    fun clearAllEmptiesStore() {
        store.addPeer(samplePeer())
        store.addPeer(samplePeer("dev-2", "fp-2"))
        store.clearAll()
        assertEquals(0, store.count())
        assertTrue(store.listPeers().isEmpty())
    }


    @Test
    fun dataSurvivesStoreRecreation() {
        store.addPeer(samplePeer("dev-1", "fp-1"))

        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals(1, store2.count())
        assertTrue(store2.isTrusted("fp-1"))
        assertEquals("secret-dev-1", store2.getSharedSecret("dev-1"))
    }

    @Test
    fun emptyStoreOnMissingFile() {
        assertEquals(0, store.count())
    }


    @Test
    fun corruptJsonFailsClosed() {
        testDir.mkdirs()
        File(testDir, "trusted-devices.json").writeText("this is not json{{{")
        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertFailsWith<IllegalStateException> { store2.count() }
    }

    @Test
    fun corruptPrimaryRestoresFromBackup() {
        testDir.mkdirs()
        store.addPeer(samplePeer("dev-1", "fp-1"))
        store.addPeer(samplePeer("dev-2", "fp-2"))
        File(testDir, "trusted-devices.json").appendText("\ncorruption")
        val store2 = DeviceTrustStore(testDir.absolutePath)
        // Backup holds the first write: dev-1 survives, nothing is lost silently
        assertEquals(1, store2.count())
        assertTrue(store2.isTrusted("fp-1"))
        assertEquals("secret-dev-1", store2.getSharedSecret("dev-1"))
    }


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


    @Test
    fun encryptionRoundtripPreservesSecret() {
        val secret = "super-secret-hmac-key-12345"
        val peer = samplePeer("dev-enc", "fp-enc").copy(sharedSecret = secret)
        store.addPeer(peer)

        val fileContent = File(testDir, "trusted-devices.json").readText()
        assertFalse(fileContent.contains(secret), "plaintext should NOT appear on disk")

        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals(secret, store2.getSharedSecret("dev-enc"))
    }

    @Test
    fun encryptionUsesRandomIvPerSave() {
        val peer = samplePeer("dev-iv", "fp-iv").copy(sharedSecret = "fixed-secret")
        store.addPeer(peer)
        val fileAfterFirst = File(testDir, "trusted-devices.json").readText()

        store.addPeer(peer)
        val fileAfterSecond = File(testDir, "trusted-devices.json").readText()

        // Every save must produce different ciphertext (GCM random iv)
        assertNotEquals(fileAfterFirst, fileAfterSecond,
            "same plaintext peer saved twice must produce different ciphertext (random IV)")
    }

    @Test
    fun pbkdf2SaltPersistsAcrossRestarts() {
        store.addPeer(samplePeer("dev-salt", "fp-salt").copy(sharedSecret = "test-secret"))

        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals("test-secret", store2.getSharedSecret("dev-salt"))

        val fileContent = File(testDir, "trusted-devices.json").readText()
        assertTrue(fileContent.contains("\"salt\""), "store JSON should contain salt field")
        assertTrue(fileContent.contains("\"version\":2"), "store should be version 2")

        val store3 = DeviceTrustStore(testDir.absolutePath)
        assertEquals("test-secret", store3.getSharedSecret("dev-salt"))
    }

    @Test
    fun plaintextFileFailsClosed() {
        testDir.mkdirs()
        val oldJson = """{"version":1,"peers":[{"deviceId":"legacy-dev","displayName":"Old Peer","fingerprint":"fp-old","sharedSecret":"old-plaintext-secret","pairedAt":1000}]}"""
        File(testDir, "trusted-devices.json").writeText(oldJson)

        assertFailsWith<IllegalStateException> {
            DeviceTrustStore(testDir.absolutePath).getSharedSecret("legacy-dev")
        }
    }

    @Test
    fun encryptionHandlesEmptySecret() {
        val peer = samplePeer("dev-empty", "fp-empty").copy(sharedSecret = "")
        store.addPeer(peer)

        val fileContent = File(testDir, "trusted-devices.json").readText()
        assertFalse(fileContent.contains(":\"\",") && fileContent.contains("sharedSecret"),
            "empty secret should also be encrypted on disk")

        val store2 = DeviceTrustStore(testDir.absolutePath)
        assertEquals("", store2.getSharedSecret("dev-empty"))
    }


    @Test
    fun rapidAddAndRemoveProducesCorrectCount() {
        for (i in 1..100) {
            store.addPeer(samplePeer("dev-$i", "fp-$i"))
        }
        for (i in 1..100 step 2) {
            store.revokeDevice("dev-$i")
        }
        assertEquals(50, store.count())
    }
}
