package app.journal.sync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.*
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Generates and manages a self-signed TLS identity for the sync server.
 *
 * On first run, generates an RSA 2048-bit key pair + self-signed X.509 cert
 * stored in a PKCS12 keystore at ~/.psychonautica/identity.p12.
 *
 * The fingerprint (SHA-256 of the DER-encoded cert) is used as the device ID
 * and for certificate pinning during pairing.
 */
class TlsIdentityManager(private val dataDir: String = defaultDataDir()) {

    private val storeFile: File get() = File(dataDir, "identity.p12")
    val alias: String = "nepenthe"
    val password: CharArray = "nepenthe-identity".toCharArray()

    /** Ensure identity exists, generating it if needed. Returns the fingerprint. */
    fun ensureIdentity(): String {
        if (!storeFile.exists()) {
            storeFile.parentFile.mkdirs()
            generateKeyStore()
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
        val sslContext = SSLContext.getInstance("TLS")
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
        val sslContext = SSLContext.getInstance("TLS")
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
        val sslContext = SSLContext.getInstance("TLS")
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

    private fun generateKeyStore() {
        val userHome = System.getProperty("user.home") ?: "."
        val dname = "CN=Nepenthe Journal, OU=Self-Hosted, O=User, L=Home, ST=Local, C=XX"
        val cmd = listOfNotNull(
            findKeytool(),
            "-genkeypair",
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "3650",
            "-storetype", "PKCS12",
            "-keystore", storeFile.absolutePath,
            "-storepass", String(password),
            "-keypass", String(password),
            "-dname", dname,
            "-ext", "SAN=dns:localhost,ip:127.0.0.1",
            "-J\"-Duser.home=$userHome\""
        )

        val proc = ProcessBuilder(cmd)
            .directory(File(dataDir))
            .redirectErrorStream(true)
            .start()

        val output = proc.inputStream.bufferedReader().readText()
        val exited = proc.waitFor(30, TimeUnit.SECONDS)

        if (!exited || proc.exitValue() != 0) {
            throw RuntimeException("keytool failed (exit=${proc.exitValue()}): $output")
        }
    }

    private fun findKeytool(): String {
        val javaHome = System.getProperty("java.home") ?: throw RuntimeException("java.home not set")
        val bin = File(javaHome, "bin")
        val name = if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool"
        return File(bin, name).absolutePath.also {
            if (!File(it).exists()) throw RuntimeException("keytool not found at $it")
        }
    }

    companion object {
        fun defaultDataDir(): String {
            val home = System.getProperty("user.home") ?: "."
            return "$home${File.separator}.psychonautica"
        }

        fun fingerprint(cert: X509Certificate): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(cert.encoded)
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
