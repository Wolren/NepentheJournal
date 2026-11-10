package app.journal.data

/**
 * iOS has no zstd implementation linked yet. The envelope writes the plain
 * payload with flag 0 and reads its own files; a journal compressed on desktop
 * or Android is not readable here.
 */
internal actual object JournalCompression {

    actual val available: Boolean = false

    actual fun compress(data: ByteArray): ByteArray = unsupported()

    actual fun decompress(data: ByteArray): ByteArray = unsupported()

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Journal compression needs zstd, which iOS does not bundle yet")
}
