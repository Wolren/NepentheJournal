package app.journal.data

import java.io.File

/**
 * Reads a journal file the way the store does: decrypting the NEPE1 envelope,
 * and passing plaintext through so pre-encryption fixtures still load.
 */
internal fun File.readJournal(): String {
    if (!exists()) return ""
    val bytes = readBytes()
    return if (JournalAtRest.looksEncrypted(bytes)) JournalAtRest.open(bytes) else bytes.decodeToString()
}
