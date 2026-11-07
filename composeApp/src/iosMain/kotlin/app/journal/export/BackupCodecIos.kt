package app.journal.export

/**
 * iOS has no zstd implementation linked yet, so the sealed backup reports
 * itself unavailable there instead of writing a file nobody can restore.
 */
internal actual object BackupCodec {

    actual val available: Boolean = false

    actual fun compress(data: ByteArray): ByteArray = unsupported()

    actual fun decompress(data: ByteArray): ByteArray = unsupported()

    actual fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray = unsupported()

    actual fun encrypt(plain: ByteArray, key: ByteArray): ByteArray = unsupported()

    actual fun decrypt(sealed: ByteArray, key: ByteArray): ByteArray = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Encrypted backups need zstd, which iOS does not bundle yet")
}
