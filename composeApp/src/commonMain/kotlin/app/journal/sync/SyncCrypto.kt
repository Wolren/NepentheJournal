package app.journal.sync

// The generic primitives (secureRandomBytes, sha256, base64Encode,
// base64Decode) live in app.journal.util.crypto (util/crypto/Crypto.kt);
// this file keeps only the sync-protocol-specific expect funs below.

/**
 * Derive an AES-256 key from a [password] string using PBKDF2-HMAC-SHA256.
 *
 * The salt and iteration count are fixed (embedded in the implementation)
 * so all platforms produce the same key for the same password.
 */
expect fun aesEncryptionKey(password: String): ByteArray

/**
 * Encrypt [body] with AES-256-GCM using [key] (must be 32 bytes).
 *
 * Output format: 12-byte random IV || encrypted payload (ciphertext || 16-byte GCM tag).
 * A new random IV is generated on every call, so the same plaintext produces
 * different ciphertext each time.
 */
expect fun encryptBody(body: String, key: ByteArray): ByteArray

/**
 * Decrypt a ciphertext produced by [encryptBody] back to the original string.
 *
 * Input format: 12-byte IV || encrypted payload (ciphertext || 16-byte GCM tag).
 * @throws IllegalArgumentException if [data] is too short or the GCM tag fails verification
 */
expect fun decryptBody(data: ByteArray, key: ByteArray): String
