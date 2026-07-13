package app.journal.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.*
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Generates and manages a self-signed TLS identity for the sync server.
 *
 * On first run, generates an RSA 2048-bit key pair + self-signed X.509 cert
 * stored in a PKCS12 keystore at [dataDir]/identity.p12.
 *
 * Uses BouncyCastle for cross-platform cert generation (no keytool.exe).
 *
 * The keystore password is derived from device identity + a fixed salt
 * to avoid hardcoding credentials in the binary. Override via env var
 * NEPENTHE_TLS_PASSWORD for testing/debugging.
 *
 * The fingerprint (SHA-256 of the DER-encoded cert) is used as the device ID
 * and for certificate pinning during pairing.
 */
class TlsIdentityManager(private val dataDir: String = platformSyncDataDir()) {

    private val storeFile: File get() = File(dataDir, "identity.p12")
    val alias: String = "nepenthe"

    /**
     * Keystore password derived from device identity + salt.
     * Override with NEPENTHE_TLS_PASSWORD env var for testing.
     */
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
        val md = MessageDigest.getInstance("SHA-256")
        md.update(seed.toByteArray())
        md.update("::nepenthe-tls-v1::".toByteArray())
        val hash = md.digest()
        // Base64url-encode the first 192 bits for a 32-char password
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
        return loadFingerprint()
    }

    /** Load the key store. */
    fun loadKeyStore(): KeyStore {
        val ks = KeyStore.getInstance("PKCS12")
        FileInputStream(storeFile).use { ks.load(it, password) }
        return ks
    }

    /** Create an SSLContext from the identity for server-side TLS. */
    fun serverSSLContext(): SSLContext {
        val ks = loadKeyStore()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(kmf.keyManagers, null, SecureRandom())
        return sslContext
    }

    /** Create an SSLContext that pins this specific certificate (client side). */
    fun pinnedSSLContext(): SSLContext {
        val cert = loadCertificate()
        val pinTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {
                check(certs.any { it.encoded.contentEquals(cert.encoded) }) {
                    "Client cert not in pinned trust"
                }
            }
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {
                check(certs.any { it.encoded.contentEquals(cert.encoded) }) {
                    "Server cert not pinned: expected ${fingerprint(cert)}"
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(cert)
        }
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(pinTrustManager), SecureRandom())
        return sslContext
    }

    /** Create an SSLContext that accepts any certificate (TOFU mode for pairing). */
    fun tofuSSLContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLSv1.2")
        sslContext.init(null, arrayOf(trustAll), SecureRandom())
        return sslContext
    }

    /** SHA-256 fingerprint of the certificate hex string. */
    fun fingerprint(): String = fingerprint(loadCertificate())

    val certificate: X509Certificate get() = loadCertificate()

    /** Expose the actual key pair for signing. */
    val keyPair: KeyPair get() {
        val ks = loadKeyStore()
        val key = ks.getKey(alias, password) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate
        return KeyPair(cert.publicKey, key)
    }

    // ---- Private ----

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
