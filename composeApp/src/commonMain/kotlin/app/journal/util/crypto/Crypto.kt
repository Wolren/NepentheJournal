package app.journal.util.crypto

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
