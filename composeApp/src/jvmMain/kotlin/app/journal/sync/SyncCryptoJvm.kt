package app.journal.sync

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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

/**
 * Derives a 32-byte AES key from [password] using PBKDF2-HMAC-SHA256.
 *
 * Salt is a fixed 8-byte value and iterations are high enough to deter
 * casual brute-force while remaining fast on desktop JVM.
 */
actual fun aesEncryptionKey(password: String): ByteArray {
    val salt = "NepentheSync!".encodeToByteArray()
    val spec = PBEKeySpec(password.toCharArray(), salt, 600_000, 256)
    val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    return factory.generateSecret(spec).encoded
}

/** AES-256-GCM encrypt: 12-byte IV || ciphertext+tag. */
actual fun encryptBody(body: String, key: ByteArray): ByteArray {
    require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    val iv = cipher.iv                                  // 12-byte random IV
    val encrypted = cipher.doFinal(body.encodeToByteArray()) // ciphertext || 16-byte tag
    return iv + encrypted                                // IV(12) + ciphertext + tag
}

/** AES-256-GCM decrypt from the format produced by [encryptBody]. */
actual fun decryptBody(data: ByteArray, key: ByteArray): String {
    require(key.size == 32) { "AES-256 key must be 32 bytes, got ${key.size}" }
    require(data.size >= 28) { "Ciphertext must be at least 28 bytes (12 IV + 16 tag), got ${data.size}" }

    val iv = data.copyOfRange(0, 12)
    val ciphertext = data.copyOfRange(12, data.size)

    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return cipher.doFinal(ciphertext).decodeToString()
}
