package app.journal.sync

import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.*
import java.security.cert.X509Certificate
import java.util.*
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.security.cert.Certificate

/**
 * Resolve this machine's site-local (LAN) IPv4 address for sync discovery.
 *
 * Enumerates all network interfaces, picks the first site-local IPv4 address
 * (192.168.x.x, 10.x.x.x, 172.16-31.x.x), falling back to any non-loopback
 * IPv4 address. Returns null if no suitable address is found.
 */
fun resolveLocalIpV4(): String? {
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
    val candidates = mutableListOf<String>()
    while (interfaces.hasMoreElements()) {
        val iface = interfaces.nextElement()
        if (iface.isLoopback || !iface.isUp) continue
        val addresses = iface.inetAddresses
        while (addresses.hasMoreElements()) {
            val addr = addresses.nextElement()
            if (addr is Inet4Address && !addr.isLoopbackAddress) {
                val host = addr.hostAddress ?: continue
                if (addr.isSiteLocalAddress) {
                    // Site-local is the best candidate — return immediately
                    return host
                }
                candidates.add(host)
            }
        }
    }
    // Fall back to any non-loopback IPv4
    return candidates.firstOrNull()
}

actual fun generateSelfSignedP12(
    storePath: String,
    alias: String,
    password: CharArray
) {
    // BouncyCastle X.509 cert builder works without registering the provider.
    // On Android, BC is already bundled by the OS; registering a second copy
    // causes conflicts. On Desktop JVM, the JDK's built-in BC suffices for
    // the cert building APIs (JcaX509v3CertificateBuilder, JcaContentSignerBuilder).

    // Generate RSA 2048-bit key pair using default provider
    val keyGen = KeyPairGenerator.getInstance("RSA")
    keyGen.initialize(2048, SecureRandom())
    val keyPair = keyGen.generateKeyPair()

    // Build self-signed X.509 certificate
    val dn = X500Name("CN=Nepenthe Journal, OU=Self-Hosted, O=User, L=Home, ST=Local, C=XX")
    val serial = BigInteger(64, SecureRandom())
    val notBefore = Date(System.currentTimeMillis() - 86400000L) // 1 day in past
    val notAfter = Date(System.currentTimeMillis() + 3650L * 86400000L) // 10 years

    val certBuilder = JcaX509v3CertificateBuilder(
        dn,
        serial,
        notBefore,
        notAfter,
        dn,
        keyPair.public
    )

    // Add Subject Alternative Name so Java SSL won't reject connections to localhost
    val san = GeneralNames(GeneralName(GeneralName.dNSName, "localhost"))
    certBuilder.addExtension(Extension.subjectAlternativeName, false, san)

    val signer = JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.private)

    val certHolder = certBuilder.build(signer)
    val cert = JcaX509CertificateConverter().getCertificate(certHolder)

    // Store in PKCS12 keystore
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, password)
    ks.setKeyEntry(alias, keyPair.private, password, arrayOf<Certificate>(cert))

    FileOutputStream(storePath).use { out ->
        ks.store(out, password)
    }
}
