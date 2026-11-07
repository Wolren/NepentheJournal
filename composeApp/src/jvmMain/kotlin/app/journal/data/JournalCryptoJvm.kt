package app.journal.data

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM body shared by the JVM key holders (desktop key file, Android keystore). */
internal fun aesGcmSeal(plain: ByteArray, key: ByteArray): ByteArray {
    val iv = ByteArray(12)
    SecureRandom().nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
    return iv + cipher.doFinal(plain)
}

internal fun aesGcmOpen(sealed: ByteArray, key: ByteArray): ByteArray {
    require(sealed.size > 12 + 16) { "Sealed journal is too short (${sealed.size} bytes)" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(
        Cipher.DECRYPT_MODE,
        SecretKeySpec(key, "AES"),
        GCMParameterSpec(128, sealed, 0, 12)
    )
    return cipher.doFinal(sealed, 12, sealed.size - 12)
}
