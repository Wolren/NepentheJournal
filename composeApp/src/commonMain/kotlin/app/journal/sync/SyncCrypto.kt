package app.journal.sync

/**
 * Fill [size] bytes with cryptographically secure random data.
 * Used for nonces and challenges; MUST NOT be a plain PRNG.
 */
expect fun secureRandomBytes(size: Int): ByteArray

/**
 * SHA-256 hash of [data].
 */
expect fun sha256(data: ByteArray): ByteArray

/**
 * Base64-encode [data] to a standard (non-URL-safe) Base64 string.
 */
expect fun base64Encode(data: ByteArray): String

/**
 * Decode a standard Base64 [str] back to bytes.
 * @throws IllegalArgumentException if the string is not valid Base64
 */
expect fun base64Decode(str: String): ByteArray

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
