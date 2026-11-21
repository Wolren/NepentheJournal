package app.journal.sync

import app.journal.util.crypto.sha256
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.Foundation.*
import platform.Security.*

/**
 * Darwin client that accepts a self-signed host certificate only when the SHA-256
 * of its leaf matches [expectedFingerprint], the fingerprint learned at pairing.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun pinnedDarwinClient(expectedFingerprint: String): HttpClient = HttpClient(Darwin) {
    engine {
        handleChallenge { _, _, challenge, completionHandler ->
            val space = challenge.protectionSpace
            if (space.authenticationMethod != NSURLAuthenticationMethodServerTrust) {
                completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
                return@handleChallenge
            }
            val trust = space.serverTrust
            val actual = trust?.let { leafFingerprint(it) }
            if (trust != null && actual != null && actual.equals(expectedFingerprint, ignoreCase = true)) {
                completionHandler(
                    NSURLSessionAuthChallengeUseCredential,
                    NSURLCredential.credentialForTrust(trust)
                )
            } else {
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun leafFingerprint(trust: SecTrustRef): String? {
    if (SecTrustGetCertificateCount(trust) < 1) return null
    val certificate = SecTrustGetCertificateAtIndex(trust, 0) ?: return null
    val data = SecCertificateCopyData(certificate) ?: return null
    val der = try {
        CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt())
    } finally {
        CFRelease(data)
    }
    return der?.let { sha256(it).toHexLower() }
}

/** "https://host:tlsPort" when the host advertised a TLS port, otherwise plain HTTP. */
internal fun syncBaseUrl(host: String, port: Int, tlsPort: Int): String =
    if (tlsPort > 0) "https://$host:$tlsPort" else "http://$host:$port"

private const val HEX = "0123456789abcdef"

internal fun ByteArray.toHexLower(): String = buildString(size * 2) {
    for (b in this@toHexLower) {
        val v = b.toInt() and 0xFF
        append(HEX[v shr 4])
        append(HEX[v and 0xF])
    }
}
