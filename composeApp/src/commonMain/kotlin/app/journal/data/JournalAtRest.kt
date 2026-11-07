package app.journal.data

/**
 * File envelope for the journal: the ASCII magic "NEPE1" then a 12-byte IV and
 * the AES-256-GCM ciphertext with its tag. A file without the magic is a
 * pre-encryption journal and still loads, so existing installs migrate on the
 * next save.
 */
internal object JournalAtRest {

    private val MAGIC = "NEPE1".encodeToByteArray()

    fun looksEncrypted(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size + 12 + 16 && MAGIC.indices.all { bytes[it] == MAGIC[it] }

    fun seal(plainText: String): ByteArray = MAGIC + journalSeal(plainText.encodeToByteArray())

    fun open(bytes: ByteArray): String =
        journalOpen(bytes.copyOfRange(MAGIC.size, bytes.size)).decodeToString()
}

/** AES-256-GCM under the device-held journal key; returns the 12-byte IV followed by ciphertext and tag. */
internal expect fun journalSeal(plain: ByteArray): ByteArray

/** Inverse of [journalSeal]; throws when the tag does not verify. */
internal expect fun journalOpen(sealed: ByteArray): ByteArray
