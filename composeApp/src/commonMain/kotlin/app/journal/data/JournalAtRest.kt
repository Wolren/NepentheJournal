package app.journal.data

/**
 * File envelope for the journal: the ASCII magic "NEPE2", a flags byte, then a
 * 12-byte IV and the AES-256-GCM ciphertext with its tag. Bit 0 of the flags marks
 * a zstd-compressed payload. The legacy "NEPE1" envelope (no flags, no compression)
 * and a plaintext file still load, so existing installs migrate on the next save.
 */
internal object JournalAtRest {

    private val MAGIC_V1 = "NEPE1".encodeToByteArray()
    private val MAGIC_V2 = "NEPE2".encodeToByteArray()
    private const val HEADER_V2 = 6
    private const val FLAG_ZSTD = 1
    private const val TAG_SIZE = 12 + 16

    fun looksEncrypted(bytes: ByteArray): Boolean =
        (bytes.size >= MAGIC_V1.size + TAG_SIZE && startsWith(bytes, MAGIC_V1)) ||
            (bytes.size >= HEADER_V2 + TAG_SIZE && startsWith(bytes, MAGIC_V2))

    fun seal(plainText: String): ByteArray {
        val compressed = JournalCompression.available
        val payload = if (compressed) {
            JournalCompression.compress(plainText.encodeToByteArray())
        } else {
            plainText.encodeToByteArray()
        }
        val flag = if (compressed) FLAG_ZSTD else 0
        return MAGIC_V2 + byteArrayOf(flag.toByte()) + journalSeal(payload)
    }

    fun open(bytes: ByteArray): String {
        require(looksEncrypted(bytes)) { "Not a sealed journal" }
        if (startsWith(bytes, MAGIC_V1)) {
            return journalOpen(bytes.copyOfRange(MAGIC_V1.size, bytes.size)).decodeToString()
        }
        val flags = bytes[MAGIC_V2.size].toInt()
        val payload = journalOpen(bytes.copyOfRange(HEADER_V2, bytes.size))
        val raw = if (flags and FLAG_ZSTD != 0) {
            require(JournalCompression.available) { "Journal was compressed on another platform" }
            JournalCompression.decompress(payload)
        } else {
            payload
        }
        return raw.decodeToString()
    }

    private fun startsWith(bytes: ByteArray, magic: ByteArray): Boolean =
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
}

/** AES-256-GCM under the device-held journal key; returns the 12-byte IV followed by ciphertext and tag. */
internal expect fun journalSeal(plain: ByteArray): ByteArray

/** Inverse of [journalSeal]; throws when the tag does not verify. */
internal expect fun journalOpen(sealed: ByteArray): ByteArray
