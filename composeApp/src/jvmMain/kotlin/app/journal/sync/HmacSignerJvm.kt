package app.journal.sync

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JVM HMAC-SHA256 using javax.crypto.Mac (available on all JDKs).
 */
actual fun hmacSha256Hex(secret: ByteArray, data: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret, "HmacSHA256"))
    return mac.doFinal(data).joinToString("") { "%02x".format(it) }
}
