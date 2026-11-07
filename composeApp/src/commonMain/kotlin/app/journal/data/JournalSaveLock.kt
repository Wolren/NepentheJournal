package app.journal.data

import app.journal.util.PlatformLock

/**
 * One lock for every journal file write in the process.
 *
 * Each JournalStore used to own its own lock, so the autosave store and the
 * sync store wrote the same tmp/bak paths concurrently: the live run hit
 * "Failed to write temp file" and "Atomic rename failed" mid-sync.
 */
internal object JournalSaveLock {
    val instance = PlatformLock()
}
