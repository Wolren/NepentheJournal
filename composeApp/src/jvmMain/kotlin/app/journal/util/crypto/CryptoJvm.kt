package app.journal.util.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

private val secureRandom = SecureRandom()

/** Cryptographically secure random bytes via java.security.SecureRandom. */
actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also { secureRandom.nextBytes(it) }

/** SHA-256 via java.security.MessageDigest. */
actual fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)

/** Standard (non-URL-safe) Base64 via java.util.Base64. */
actual fun base64Encode(data: ByteArray): String =
    Base64.getEncoder().encodeToString(data)

actual fun base64Decode(str: String): ByteArray =
    Base64.getDecoder().decode(str)
