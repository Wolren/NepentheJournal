package app.journal.data

/**
 * zstd payload codec for the at-rest envelope. iOS reports unavailable and the
 * envelope then stores the plain payload, so every platform can read its own file.
 */
internal expect object JournalCompression {
    val available: Boolean

    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
}
