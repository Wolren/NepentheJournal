package app.journal.data

import app.journal.sync.IosAtRestKey
import app.journal.util.crypto.secureRandomBytes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CCCryptoGcm.CCCryptorGCMOneshotDecrypt
import platform.CCCryptoGcm.CCCryptorGCMOneshotEncrypt
import platform.CoreCrypto.kCCAlgorithmAES

/**
 * Key lives in the iOS Keychain (IosAtRestKey), so the journal file alone is
 * useless off-device. Cipher: CommonCrypto oneshot AES-256-GCM, the same
 * primitive SyncCryptoIos uses.
 */
@OptIn(ExperimentalForeignApi::class)
private fun journalKey(): ByteArray =
    IosAtRestKey(service = "app.journal.nepenthe", account = "journal-at-rest-key").loadOrCreate()

@OptIn(ExperimentalForeignApi::class)
internal actual fun journalSeal(plain: ByteArray): ByteArray {
    val key = journalKey()
    val iv = secureRandomBytes(12)
    val ciphertext = ByteArray(plain.size)
    val tag = ByteArray(16)
    key.usePinned { keyPinned ->
        iv.usePinned { ivPinned ->
            plain.usePinned { ptPinned ->
                ciphertext.usePinned { ctPinned ->
                    tag.usePinned { tagPinned ->
                        val status = CCCryptorGCMOneshotEncrypt(
                            kCCAlgorithmAES,
                            keyPinned.addressOf(0), key.size.toULong(),
                            ivPinned.addressOf(0), iv.size.toULong(),
                            null, 0uL,
                            ptPinned.addressOf(0), plain.size.toULong(),
                            ctPinned.addressOf(0),
                            tagPinned.addressOf(0), tag.size.toULong()
                        )
                        check(status == 0) { "CCCryptorGCMOneshotEncrypt failed: $status" }
                    }
                }
            }
        }
    }
    return iv + ciphertext + tag
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun journalOpen(sealed: ByteArray): ByteArray {
    require(sealed.size > 12 + 16) { "Sealed journal is too short (${sealed.size} bytes)" }
    val key = journalKey()
    val iv = sealed.copyOfRange(0, 12)
    val ciphertext = sealed.copyOfRange(12, sealed.size - 16)
    val tag = sealed.copyOfRange(sealed.size - 16, sealed.size)
    val plain = ByteArray(ciphertext.size)
    key.usePinned { keyPinned ->
        iv.usePinned { ivPinned ->
            tag.usePinned { tagPinned ->
                ciphertext.usePinned { ctPinned ->
                    plain.usePinned { ptPinned ->
                        val status = CCCryptorGCMOneshotDecrypt(
                            kCCAlgorithmAES,
                            keyPinned.addressOf(0), key.size.toULong(),
                            ivPinned.addressOf(0), iv.size.toULong(),
                            null, 0uL,
                            ctPinned.addressOf(0), ciphertext.size.toULong(),
                            tagPinned.addressOf(0), tag.size.toULong(),
                            ptPinned.addressOf(0)
                        )
                        check(status == 0) { "CCCryptorGCMOneshotDecrypt failed: $status" }
                    }
                }
            }
        }
    }
    return plain
}
