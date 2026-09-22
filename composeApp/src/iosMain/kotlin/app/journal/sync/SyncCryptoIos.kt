package app.journal.sync

import kotlinx.cinterop.*
import platform.CommonCrypto.*
import platform.Security.SecRandomCopyBytes
import app.journal.util.PlatformLock

/**
 * iOS actual implementations for the sync-specific expect funs declared in
 * commonMain SyncCrypto.kt (aesEncryptionKey, encryptBody, decryptBody)
 * using CommonCrypto and Security.
 *
 * PBKDF2 key    -> CommonCrypto CCKeyDerivationPBKDF
 * AES-256-GCM   -> CommonCrypto CCCryptorGCMOneshot (iOS 10+)
 *
 * The generic primitives (secureRandomBytes, sha256, base64Encode,
 * base64Decode) were relocated to util/crypto/CryptoIos.kt (package
 * app.journal.util.crypto). All functions match their `expect`
 * declarations and produce identical output to the JVM implementations in
 * SyncCryptoJvm.kt.
 */

// ========================================================================
// PBKDF2 key derivation (with the JVM-mirrored derived-key cache)
// ========================================================================

/**
 * Derived AES key cache, mirroring jvmMain SyncCryptoJvm byte for byte in
 * behavior (audit: the 600k-iteration PBKDF2 derivation used to run on
 * EVERY authenticated push and pull, hundreds of ms of CPU per request).
 *
 * Keyed by the exact secret string, bounded at [MAX_CACHED_AES_KEYS]; at
 * the bound the whole map is cleared (same eviction as the JVM).
 *
 * Invalidation story, honestly: iOS has NO purge hook wired today. The
 * revoke path is IosSyncTransport.revokeTrustedDevice ->
 * IosDeviceTrustStore.revokeDevice, and IosSyncTransport is outside this
 * file's ownership scope, so `clearAesKeyCache()` below is provided but
 * not called yet, unlike the JVM where SyncTransport.revokeDevice calls
 * it. What keeps this safe in the meantime is the JVM's own argument:
 * entries are only ever looked up by their OWN secret string, so once a
 * device is revoked or re-paired its old secret is never queried again
 * and the stale key is unreachable, and the 64-entry bound caps resident
 * key material. When the iOS revoke path gains its hook (one line in
 * IosSyncTransport.revokeTrustedDevice), it must call
 * [clearAesKeyCache] to match the JVM exactly.
 */
private val aesKeyCache = mutableMapOf<String, ByteArray>()
private val aesKeyCacheLock = PlatformLock()
private const val MAX_CACHED_AES_KEYS = 64

/** Drop every cached AES key; see [aesKeyCache] for when to call this. */
fun clearAesKeyCache() {
    aesKeyCacheLock.withLock { aesKeyCache.clear() }
}

/**
 * Derives a 32-byte AES key from [password] using PBKDF2-HMAC-SHA256.
 *
 * Salt and iteration count are fixed to match the JVM implementation's
 * constants so both platforms produce the same key for the same password.
 * Derivation runs only on a cache miss; see [aesKeyCache].
 */
actual fun aesEncryptionKey(password: String): ByteArray {
    aesKeyCacheLock.withLock { aesKeyCache[password] }?.let { return it }
    val salt = "NepentheSync!".encodeToByteArray()
    val keyLen = 32UL
    val iterations = 600_000U

    val derivedKey = ByteArray(keyLen.toInt())
    password.encodeToByteArray().usePinned { pwPinned ->
        salt.usePinned { saltPinned ->
            derivedKey.usePinned { keyPinned ->
                val status = CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    pwPinned.addressOf(0),
                    password.length.toULong(),
                    saltPinned.addressOf(0),
                    salt.size.toULong(),
                    kCCPRFHmacAlgSHA256,
                    iterations,
                    keyPinned.addressOf(0),
                    keyLen
                )
                check(status == 0) { "CCKeyDerivationPBKDF failed with status $status" }
            }
        }
    }
    aesKeyCacheLock.withLock {
        if (aesKeyCache.size >= MAX_CACHED_AES_KEYS) aesKeyCache.clear()
        aesKeyCache[password] = derivedKey
    }
    return derivedKey
}

// ========================================================================
// AES-256-GCM encrypt
// ========================================================================

actual fun encryptBody(body: String, key: ByteArray): ByteArray {
    require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }

    val iv = ByteArray(12) // 96-bit GCM IV / nonce
    val plaintext = body.encodeToByteArray()
    val ciphertext = ByteArray(plaintext.size) // GCM output len == plaintext len
    val tag = ByteArray(16) // 128-bit GCM authentication tag

    // Generate random IV via Security framework (crypto-secure random).
    // Check the status exactly like secureRandomBytes does: an unfilled IV
    // is all zeros, and reusing one GCM nonce would be catastrophic.
    iv.usePinned { ivPinned ->
        check(SecRandomCopyBytes(null, iv.size.toULong(), ivPinned.addressOf(0)) == 0) {
            "SecRandomCopyBytes failed"
        }
    }

    // Oneshot AES-256-GCM encrypt
    key.usePinned { keyPinned ->
        iv.usePinned { ivPinned ->
            plaintext.usePinned { ptPinned ->
                ciphertext.usePinned { ctPinned ->
                    tag.usePinned { tagPinned ->
                        val status = CCCryptorGCMOneshotEncrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            keyPinned.addressOf(0), key.size.toULong(),
                            ivPinned.addressOf(0), iv.size.toULong(),
                            null, 0uL,       // no additional authenticated data
                            ptPinned.addressOf(0), plaintext.size.toULong(),
                            ctPinned.addressOf(0),
                            tagPinned.addressOf(0)
                        )
                        check(status == kCCSuccess) {
                            "AES-256-GCM encryption failed with status $status"
                        }
                    }
                }
            }
        }
    }

    // Output format: IV(12) || ciphertext || tag(16)
    return iv + ciphertext + tag
}

// ========================================================================
// AES-256-GCM decrypt
// ========================================================================

actual fun decryptBody(data: ByteArray, key: ByteArray): String {
    require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
    val minLen = 12 + 1 + 16 // IV(12) + min 1 byte ciphertext + tag(16)
    require(data.size >= minLen) {
        "Ciphertext must be at least $minLen bytes (12 IV + 1 ciphertext + 16 tag), got ${data.size}"
    }

    val iv = data.copyOfRange(0, 12)
    val ciphertext = data.copyOfRange(12, data.size - 16)
    val tag = data.copyOfRange(data.size - 16, data.size)
    val plaintext = ByteArray(ciphertext.size)

    // Oneshot AES-256-GCM decrypt: GCM mode also verifies the tag
    key.usePinned { keyPinned ->
        iv.usePinned { ivPinned ->
            tag.usePinned { tagPinned ->
                ciphertext.usePinned { ctPinned ->
                    plaintext.usePinned { ptPinned ->
                        val status = CCCryptorGCMOneshotDecrypt(
                            kCCDecrypt,
                            kCCAlgorithmAES,
                            keyPinned.addressOf(0), key.size.toULong(),
                            ivPinned.addressOf(0), iv.size.toULong(),
                            null, 0uL,       // no additional authenticated data
                            ctPinned.addressOf(0), ciphertext.size.toULong(),
                            ptPinned.addressOf(0),
                            tagPinned.addressOf(0), tag.size.toULong()
                        )
                        check(status == kCCSuccess || status == 0) {
                            "AES-256-GCM decryption / tag verification failed with status $status"
                        }
                    }
                }
            }
        }
    }

    return plaintext.decodeToString()
}
