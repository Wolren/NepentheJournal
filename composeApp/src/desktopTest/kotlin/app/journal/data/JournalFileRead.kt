package app.journal.data

import java.io.File

/** Reads the user journal file the way the store does, sealed or plaintext. */
internal fun File.readJournal(): String {
    if (!exists()) return ""
    val bytes = readBytes()
    return if (JournalAtRest.looksEncrypted(bytes)) JournalAtRest.open(bytes) else bytes.decodeToString()
}

/** Reads the library file the split store writes beside the user journal. */
internal fun File.readLibrary(): String {
    val library = File(parentFile, "journal-library.json")
    if (!library.exists()) return ""
    val bytes = library.readBytes()
    return if (JournalAtRest.looksEncrypted(bytes)) JournalAtRest.open(bytes) else bytes.decodeToString()
}

/** The full snapshot as the store rebuilds it: user file plus library file. */
internal fun File.readMergedJournal(): JournalSnapshot {
    val library = readLibrary().takeIf { it.isNotEmpty() }
    return JournalSplit.merge(readJournal(), library).snapshot
}
