package app.journal.sync

import kotlinx.cinterop.*
import platform.CommonCrypto.*
import platform.Security.SecRandomCopyBytes

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
// PBKDF2 key derivation
// ========================================================================

/**
 * Derives a 32-byte AES key from [password] using PBKDF2-HMAC-SHA256.
 *
 * Salt and iteration count are fixed to match the JVM implementation's
 * constants so both platforms produce the same key for the same password.
 */
actual fun aesEncryptionKey(password: String): ByteArray {
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
