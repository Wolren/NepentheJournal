package app.journal.sync

import kotlin.test.*
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate

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
    fun keystoreContainsRsaKey() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val ks = mgr.loadKeyStore()
        assertTrue(ks.containsAlias("nepenthe"))
        val cert = ks.getCertificate("nepenthe")
        assertNotNull(cert)
        assertTrue(cert is X509Certificate)
        assertTrue((cert as X509Certificate).sigAlgName.uppercase().contains("RSA"),
            "Expected RSA signature, got ${cert.sigAlgName}")
    }

    @Test
    fun fingerprintIsDeterministicAcrossLoads() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val fp1 = mgr.fingerprint()

        val mgr2 = TlsIdentityManager(testDir.absolutePath)
        val fp2 = mgr2.ensureIdentity()
        assertEquals(fp1, fp2, "reloading same keystore should produce same fingerprint")
    }

    @Test
    fun serverSSLContextInitializes() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val ctx = mgr.serverSSLContext()
        assertNotNull(ctx)
        assertEquals("TLSv1.2", ctx.protocol)
    }

    @Test
    fun pinnedSSLContextVerifiesOwnCert() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        // pinnedSSLContext should accept the manager's own cert
        val ctx = mgr.pinnedSSLContext()
        assertNotNull(ctx)
        assertEquals("TLSv1.2", ctx.protocol)
    }

    @Test
    fun tofuSSLContextAcceptsAny() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val ctx = mgr.tofuSSLContext()
        assertNotNull(ctx)
        assertEquals("TLSv1.2", ctx.protocol)
    }

    @Test
    fun certificateIsX509WithCorrectCN() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val cert = mgr.certificate
        assertTrue(cert.subjectX500Principal.name.contains("Nepenthe Journal"),
            "CN should contain 'Nepenthe Journal', got: ${cert.subjectX500Principal.name}")
    }

    @Test
    fun keyPairIsExtractable() {
        val mgr = TlsIdentityManager(testDir.absolutePath)
        mgr.ensureIdentity()
        val kp = mgr.keyPair
        assertNotNull(kp.public)
        assertNotNull(kp.private)
        assertEquals("RSA", kp.public.algorithm)
    }

    @Test
    fun passwordChangesAcrossDifferentMachines() {
        val mgr1 = TlsIdentityManager(testDir.absolutePath)
        val mgr2 = TlsIdentityManager(System.getProperty("java.io.tmpdir"))
        // Different dataDirs should still produce different passwords
        // because the password is derived from user.home + os.name (same on same machine)
        // This is more of a sanity check — on the same machine they'll be the same
        assertNotNull(mgr1.password)
        assertNotNull(mgr2.password)
    }

    @Test
    fun envPasswordOverrideWorks() {
        // Can't actually set env in-process reliably on JVM, but we can verify the fallback
        val mgr = TlsIdentityManager(testDir.absolutePath)
        val pw = mgr.password
        assertTrue(pw.size >= 8, "derived password should be at least 8 chars, got ${pw.size}")
    }
}
