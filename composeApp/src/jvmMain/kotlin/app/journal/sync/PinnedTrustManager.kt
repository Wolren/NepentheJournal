package app.journal.sync

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Trust manager that pins the host's self-signed certificate by its SHA-256 fingerprint.
 *
 * A null [expectedFingerprint] means "capture on first use": the first handshake records
 * the leaf fingerprint so the caller can compare it with the authenticated HostInfo before
 * pinning. Every later handshake must match, which is why the pin is set as soon as the
 * HostInfo names the host's fingerprint.
 */
class PinnedTrustManager(initialFingerprint: String? = null) : X509TrustManager {

    @Volatile
    var expectedFingerprint: String? = initialFingerprint

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
        val actual = fingerprint(leaf)
        val expected = expectedFingerprint
        if (expected == null) {
            expectedFingerprint = actual
            return
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            throw CertificateException("Certificate pinning failed: expected $expected, got $actual")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        /** SHA-256 of the DER-encoded certificate as lowercase hex, the same shape as HostInfo.fingerprint. */
        fun fingerprint(cert: X509Certificate): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}
