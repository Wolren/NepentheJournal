@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.journal.util.crypto

import kotlinx.cinterop.*
import platform.CoreCrypto.*
import platform.Foundation.NSData
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Security.SecRandomCopyBytes
import platform.posix.memcpy

/**
 * iOS actual implementations for the generic crypto expect funs declared
 * in commonMain util/crypto/Crypto.kt (secureRandomBytes, sha256,
 * base64Encode, base64Decode), using CommonCrypto and Foundation:
 *
 * secureRandomBytes -> Security SecRandomCopyBytes
 * sha256            -> CommonCrypto CC_SHA256
 * base64            -> Foundation NSData
 *
 * They produce identical output to the JVM implementations in
 * jvmMain util/crypto/CryptoJvm.kt. The sync-specific expects
 * (aesEncryptionKey, encryptBody, decryptBody) actuals stay in
 * app.journal.sync.SyncCryptoIos.
 */

// ========================================================================
// Secure random
// ========================================================================

/** Cryptographically secure random bytes via SecRandomCopyBytes. */
actual fun secureRandomBytes(size: Int): ByteArray {
    val bytes = ByteArray(size)
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            check(SecRandomCopyBytes(null, size.toULong(), pinned.addressOf(0)) == 0) {
                "SecRandomCopyBytes failed"
            }
        }
    }
    return bytes
}

// ========================================================================
// SHA-256
// ========================================================================

actual fun sha256(data: ByteArray): ByteArray {
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { src ->
        digest.usePinned { dst ->
            CC_SHA256(src.addressOf(0), data.size.toUInt(), dst.addressOf(0))
        }
    }
    return digest
}

// ========================================================================
// Base64
// ========================================================================

actual fun base64Encode(data: ByteArray): String {
    val nsData = data.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = data.size.toULong())
    }
    return nsData.base64EncodedStringWithOptions(0u)
}

actual fun base64Decode(str: String): ByteArray {
    val nsData = NSData.create(base64EncodedString = str, options = 0u)
        ?: throw IllegalArgumentException("Invalid Base64 string: $str")
    val result = ByteArray(nsData.length.toInt())
    if (result.isNotEmpty()) {
        result.usePinned { dest ->
            memcpy(dest.addressOf(0), nsData.bytes, nsData.length)
        }
    }
    return result
}
