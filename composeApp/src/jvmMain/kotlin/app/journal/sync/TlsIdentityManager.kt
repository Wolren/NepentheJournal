package app.journal.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.*
import java.security.cert.X509Certificate
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.security.spec.KeySpec

/**
 * Generates and manages a unique device identity for the sync server.
 *
 * On first run, generates an RSA 2048-bit key pair + self-signed X.509 cert
 * stored in a PKCS12 keystore at [dataDir]/identity.p12.
 *
 * The fingerprint (SHA-256 of the DER-encoded cert) is used as the device ID
 * and for certificate pinning during pairing.
 *
 * NOTE: TLS encryption (SSLContext) is NOT wired into the sync server —
 * it uses HMAC auth over plain HTTP. The cert is kept for identity/fingerprint
 * generation only.
 */
class TlsIdentityManager(private val dataDir: String = platformSyncDataDir()) {

    private val storeFile: File get() = File(dataDir, "identity.p12")
    val alias: String = "nepenthe"

    /** Keystore password derived from device identity + salt. */
    val password: CharArray by lazy {
        val envPw = System.getenv("NEPENTHE_TLS_PASSWORD")
        if (envPw != null && envPw.length >= 8) {
            envPw.toCharArray()
        } else {
            derivePassword()
        }
    }

    private fun derivePassword(): CharArray {
        val seed = System.getProperty("user.home", "unknown") +
                    System.getProperty("os.name", "unknown")
        // PBKDF2 key stretching — 100k iterations matches DeviceTrustStore
        val spec: KeySpec = PBEKeySpec(
            seed.toCharArray(),
            "nepenthe-tls-v1".toByteArray(Charsets.UTF_8),
            100_000,
            256
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (0 until 32).map { chars[(hash[it % hash.size].toInt() and 0xFF) % chars.length] }
            .joinToString("").toCharArray()
    }

    /** Ensure identity exists, generating it if needed. Returns the fingerprint. */
    fun ensureIdentity(): String {
        if (!storeFile.exists()) {
            storeFile.parentFile.mkdirs()
            generateSelfSignedP12(
                storePath = storeFile.absolutePath,
                alias = alias,
                password = password
            )
        }
        try {
            return loadFingerprint()
        } catch (e: Exception) {
            // Password mismatch — old keystore used a different derivation.
            // Delete and regenerate.
            storeFile.delete()
            generateSelfSignedP12(
                storePath = storeFile.absolutePath,
                alias = alias,
                password = password
            )
            return loadFingerprint()
        }
    }

    /**
     * A random stable device fingerprint that's unique per installation.
     * Generated and cached from the cert's SHA-256 hash.
     */
    val fingerprint: String by lazy { loadFingerprint() }

    // ---- Private ----

    private fun loadKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(storeFile).use { ks.load(it, password) }
        return ks
    }

    private fun loadCertificate(): X509Certificate {
        val ks = loadKeyStore()
        return ks.getCertificate(alias) as X509Certificate
    }

    private fun loadFingerprint(): String = fingerprint(loadCertificate())

    companion object {
        fun fingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(cert.encoded)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
