package app.journal.sync

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.usePinned
import platform.Security.CCHmac
import platform.Security.CCHmacAlgorithm
import platform.Security.kCCHmacAlgSHA256

/**
 * iOS HMAC-SHA256 using CommonCrypto (CCHmac).
 * The CCHmac function writes 32 bytes (SHA-256 digest length) into macOut.
 */
actual fun hmacSha256Hex(secret: ByteArray, data: ByteArray): String {
    val macOut = ByteArray(32) // CC_SHA256_DIGEST_LENGTH = 32
    secret.usePinned { secretPinned ->
        data.usePinned { dataPinned ->
            macOut.usePinned { macPinned ->
                CCHmac(
                    kCCHmacAlgSHA256,
                    secretPinned.addressOf(0) as CPointer<CPointed>,
                    secret.size.toULong(),
                    dataPinned.addressOf(0) as CPointer<CPointed>,
                    data.size.toULong(),
                    macPinned.addressOf(0) as CPointer<CPointed>
                )
            }
        }
    }
    return macOut.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
