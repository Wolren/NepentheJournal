package app.journal.sync

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CommonCrypto.CCKeyDerivationPBKDF
import platform.CommonCrypto.kCCPBKDF2
import platform.CommonCrypto.kCCPRFHmacAlgSHA256

/**
 * iOS side of the C2 pairing-secret wrap (shared contract in
 * commonMain SyncContract.kt, [PairingSecretCrypto]).
 *
 * Byte-identical params to the JVM SyncAuthenticator:
 * key = PBKDF2WithHmacSHA256(password = pairing token UTF-8 bytes,
 * salt = effective client deviceId UTF-8 bytes, iterations = 100000,
 * output = 256 bit); payload = base64(nonce || ciphertext || tag)
 * with AES-256-GCM (12 byte nonce, 16 byte tag).
 *
 * The AES-GCM wire bytes reuse [encryptBody]/[decryptBody], whose output
 * format (12 byte random IV || ciphertext || tag) is exactly the C2
 * payload layout.
 */
@OptIn(ExperimentalForeignApi::class)
object IosPairingSecretCrypto {

    /**
     * Wrap a freshly minted pairing secret (host side). Throws on any
     * crypto failure; callers send the legacy field only in that case.
     */
    fun encrypt(token: String, clientDeviceId: String, sharedSecret: String): String {
        val key = pairingKey(token, clientDeviceId)
        return try {
            base64Encode(encryptBody(sharedSecret, key))
        } finally {
            key.fill(0)
        }
    }

    /**
     * Unwrap the C2 encrypted pairing secret (client side). Throws on any
     * failure (bad base64, wrong length, GCM tag mismatch); callers fall
     * back to the legacy plaintext field.
     */
    fun decrypt(token: String, clientDeviceId: String, encSecretB64: String): String {
        val key = pairingKey(token, clientDeviceId)
        return try {
            decryptBody(base64Decode(encSecretB64), key)
        } finally {
            key.fill(0)
        }
    }

    /**
     * Resolve the pairing secret from a verify response: try [encSecretB64]
     * first (derived from the user-entered token and our own client
     * deviceId), fall back to the legacy [sharedSecret] field. Returns null
     * when neither yields a secret.
     */
    fun resolveSecret(
        token: String,
        clientDeviceId: String,
        encSecretB64: String?,
        sharedSecret: String?
    ): String? {
        if (!encSecretB64.isNullOrEmpty()) {
            runCatching { decrypt(token, clientDeviceId, encSecretB64) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        return sharedSecret?.takeIf { it.isNotEmpty() }
    }

    /**
     * PBKDF2-HMAC-SHA256 key derivation matching the JVM pairingKey:
     * password = token UTF-8 bytes (tokens are ASCII, so this is identical
     * to the JVM char[] handling), salt = client deviceId UTF-8 bytes,
     * 100000 iterations, 256 bit output.
     */
    private fun pairingKey(token: String, clientDeviceId: String): ByteArray {
        val password = token.encodeToByteArray()
        val salt = clientDeviceId.encodeToByteArray()
        val derived = ByteArray(PairingSecretCrypto.KEY_LENGTH_BITS / 8)
        try {
            password.usePinned { pwPinned ->
                salt.usePinned { saltPinned ->
                    derived.usePinned { keyPinned ->
                        val status = CCKeyDerivationPBKDF(
                            kCCPBKDF2,
                            pwPinned.addressOf(0),
                            password.size.toULong(),
                            saltPinned.addressOf(0),
                            salt.size.toULong(),
                            kCCPRFHmacAlgSHA256,
                            PairingSecretCrypto.PBKDF2_ITERATIONS.toUInt(),
                            keyPinned.addressOf(0),
                            derived.size.toULong()
                        )
                        check(status == 0) { "CCKeyDerivationPBKDF failed with status $status" }
                    }
                }
            }
        } finally {
            password.fill(0)
        }
        return derived
    }
}
