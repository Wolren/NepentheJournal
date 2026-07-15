package app.journal.sync

import kotlin.test.*
import java.io.File

class TlsIdentityManagerTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "nepenthe-test-tls-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        testDir.resolve("identity.p12").delete()
        testDir.delete()
    }

    @Test
    fun ensureIdentityCreatesKeystore() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        val fp = mgr.ensureIdentity()
        assertTrue(fp.length == 64, "fingerprint should be 64 hex chars, got ${fp.length}")
        assertTrue(fp.all { it in "0123456789abcdef" }, "fingerprint should be hex")
        assertTrue(File(testDir, "identity.p12").exists())
    }

    @Test
    fun ensureIdentityIsIdempotent() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        val fp1 = mgr.ensureIdentity()
        val fp2 = mgr.ensureIdentity()
        assertEquals(fp1, fp2, "same manager should return same fingerprint")
    }

    @Test
    fun fingerprintIsDeterministicAcrossLoads() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val fp1 = mgr.fingerprint

        val mgr2 = TlsIdentityManager(testDir.absolutePath)
        val fp2 = mgr2.ensureIdentity()
        assertEquals(fp1, fp2, "reloading same keystore should produce same fingerprint")
    }
}
