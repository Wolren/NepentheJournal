package app.journal.export

import app.journal.util.crypto.secureRandomBytes

/**
 * Passphrase-sealed backup file: the journal JSON compressed with zstd, then
 * encrypted with AES-256-GCM under a PBKDF2-HMAC-SHA256 key.
 *
 * Layout: "NEPB1" | salt (16) | IV (12) | ciphertext and tag. The salt and IV
 * are regenerated per file, so two backups of the same journal differ.
 */
object EncryptedBackup {

    const val EXTENSION = "nepbak"

    /** PBKDF2 work factor; raises the cost of a guessed-passphrase attack. */
    private const val ITERATIONS = 210_000

    private val MAGIC = "NEPB1".encodeToByteArray()

    val isSupported: Boolean get() = BackupCodec.available

    fun looksSealed(bytes: ByteArray): Boolean =
        bytes.size > MAGIC.size + 16 + 12 + 16 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    /** Compresses and seals [json]; the passphrase is cleared by the caller. */
    fun seal(json: String, passphrase: CharArray): ByteArray {
        require(passphrase.isNotEmpty()) { "A backup needs a passphrase" }
        val salt = secureRandomBytes(16)
        val key = BackupCodec.deriveKey(passphrase, salt, ITERATIONS)
        val packed = BackupCodec.compress(json.encodeToByteArray())
        return MAGIC + salt + BackupCodec.encrypt(packed, key)
    }

    /** Inverse of [seal]; throws when the passphrase is wrong or the file is damaged. */
    fun open(bytes: ByteArray, passphrase: CharArray): String {
        require(looksSealed(bytes)) { "Not an encrypted Nepenthe backup" }
        val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
        val sealed = bytes.copyOfRange(MAGIC.size + 16, bytes.size)
        val key = BackupCodec.deriveKey(passphrase, salt, ITERATIONS)
        return BackupCodec.decompress(BackupCodec.decrypt(sealed, key)).decodeToString()
    }
}

/** zstd, PBKDF2 and AES-GCM per platform; iOS has no zstd yet and reports unavailable. */
internal expect object BackupCodec {
    val available: Boolean
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray
    /** Returns IV (12) followed by ciphertext and tag. */
    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray, key: ByteArray): ByteArray
}
