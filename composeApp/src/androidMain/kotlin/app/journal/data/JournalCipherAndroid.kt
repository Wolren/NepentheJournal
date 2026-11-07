package app.journal.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.journal.log.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val ALIAS = "nepenthe-journal-at-rest"

private fun journalKey(): SecretKey {
    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
    val existing = runCatching { store.getKey(ALIAS, null) as? SecretKey }.getOrNull()
    if (existing != null) return existing

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
    generator.init(
        KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
    )
    return generator.generateKey().also {
        Log.withTag("JournalAtRest").i { "Created $ALIAS in $KEYSTORE" }
    }
}

internal actual fun journalSeal(plain: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, journalKey())
    return cipher.iv + cipher.doFinal(plain)
}

internal actual fun journalOpen(sealed: ByteArray): ByteArray {
    require(sealed.size > 12 + 16) { "Sealed journal is too short (${sealed.size} bytes)" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, journalKey(), GCMParameterSpec(128, sealed, 0, 12))
    return cipher.doFinal(sealed, 12, sealed.size - 12)
}
