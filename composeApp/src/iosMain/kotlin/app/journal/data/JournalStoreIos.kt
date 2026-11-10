package app.journal.data

import app.journal.log.Log
import app.journal.serde.AppJson
import app.journal.util.PlatformLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.*
import platform.Foundation.*

/** Reads a journal file, decrypting it when it carries the at-rest envelope. */
@OptIn(ExperimentalForeignApi::class)
private fun readMaybeEncrypted(path: String): String? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    val bytes = ByteArray(data.length.toInt())
    if (bytes.isNotEmpty()) {
        bytes.usePinned { pinned ->
            platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
    }
    return if (JournalAtRest.looksEncrypted(bytes)) JournalAtRest.open(bytes) else bytes.decodeToString()
}

/** Writes the journal sealed at rest; Foundation's atomically flag keeps the temp-then-rename guarantee. */
@OptIn(ExperimentalForeignApi::class)
private fun writeSealed(text: String, path: String): Boolean {
    val sealed = JournalAtRest.seal(text)
    val data = sealed.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = sealed.size.toULong())
    }
    return data.writeToFile(path, atomically = true)
}

@OptIn(ExperimentalForeignApi::class)
actual class JournalStore actual constructor(private val repo: IJournalRepository) {

    private val saveLock = JournalSaveLock.instance

    private val fileManager = NSFileManager.defaultManager
    private val baseDir: String
        get() {
            val docs = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true
            ).firstOrNull() as? String ?: return "."
            return "$docs/.nepenthe"
        }

    actual fun dataPath(): String = "$baseDir/journal-data.json"
    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"
    private fun versionedBackupPath(index: Int): String = backupPath() + ".$index"

    private fun libraryPath(): String = "$baseDir/journal-library.json"
    private fun libraryBackupPath(): String = libraryPath() + ".bak"

    /** Library version this store last persisted; -1 forces a write on the next save. */
    private var savedLibraryVersion: Long = -1L

    @kotlin.concurrent.Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    @kotlin.concurrent.Volatile
    actual var lastLoadIssueSummary: String = ""
        private set

    /** File size in bytes via file attributes; -1 when the file does not exist. */
    private fun fileSize(path: String): Long {
        if (!fileManager.fileExistsAtPath(path)) return -1L
        val attrs = fileManager.attributesOfItemAtPath(path, null)
        return (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: -1L
    }

    actual fun load() = saveLock.withLock {
        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        if (!fileManager.fileExistsAtPath(dataPath())) {
            // Audit C3: a crash inside save()'s backup rotation can leave the main file missing
            recoverMissingMainFile(readLibraryText())
            return@withLock
        }

        if (fileManager.fileExistsAtPath(tempPath())) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            fileManager.removeItemAtPath(tempPath(), null)
        }

        val size = fileSize(dataPath())
        if (size > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large ($size bytes), refusing to load" }
            return@withLock
        }

        try {
            val text = readMaybeEncrypted(dataPath()) ?: return@withLock
            val loaded = JournalSplit.merge(text, readLibraryText())
            if (loaded.userParseError != null) {
                lastLoadHadIssues = true
                lastLoadIssueSummary = "Recovered from parse failure: ${loaded.userParseError}"
            }
            if (loaded.libraryParseError != null) {
                lastLoadHadIssues = true
                val note = "Library file recovered from parse failure: ${loaded.libraryParseError}"
                lastLoadIssueSummary =
                    if (lastLoadIssueSummary.isEmpty()) note else "$lastLoadIssueSummary; $note"
            }
            AppJson.apply(repo, loaded.snapshot)
            savedLibraryVersion = if (loaded.needsLibraryWrite) -1L else repo.libraryVersion.value
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to load journal data: ${e.message}" }
        }
    }

    private fun readLibraryText(): String? {
        val path = libraryPath()
        if (!fileManager.fileExistsAtPath(path)) return null
        if (fileSize(path) > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal library too large, ignoring" }
            return null
        }
        return try {
            readMaybeEncrypted(path)
        } catch (e: Exception) {
            Log.withTag("JournalStore").w { "Unreadable journal library: ${e.message}" }
            null
        }
    }

    /**
     * Called by [load] when the main journal file does not exist: try .bak first,
     * then .tmp, before giving up and starting empty (audit C3 fix). A candidate
     * that decodes as a whole file is used as-is; only when every candidate is
     * corrupt does the per-field recovery salvage what it can.
     */
    private fun recoverMissingMainFile(libraryText: String?) {
        val candidates = listOf(backupPath() to ".bak", tempPath() to ".tmp")
        var partialText: String? = null
        var partialLabel: String? = null
        for ((path, label) in candidates) {
            if (!fileManager.fileExistsAtPath(path)) continue
            val size = fileSize(path)
            if (size < 0 || size > 50_000_000) continue
            val text = readMaybeEncrypted(path) ?: continue
            val loaded = JournalSplit.merge(text, libraryText)
            if (loaded.userParseError == null) {
                Log.withTag("JournalStore").w { "Journal file missing; recovered from $label backup" }
                lastLoadHadIssues = true
                lastLoadIssueSummary = "Main journal file was missing; restored from $label"
                AppJson.apply(repo, loaded.snapshot)
                savedLibraryVersion = if (loaded.needsLibraryWrite) -1L else repo.libraryVersion.value
                return
            }
            if (partialText == null) {
                partialText = text
                partialLabel = label
            }
        }
        if (partialText != null) {
            Log.withTag("JournalStore").w { "Journal file missing; partially recovering from $partialLabel" }
            val loaded = JournalSplit.merge(partialText, libraryText)
            lastLoadHadIssues = true
            lastLoadIssueSummary = "Main journal file was missing; partially recovered from $partialLabel: ${loaded.userParseError ?: "ok"}"
            AppJson.apply(repo, loaded.snapshot)
            savedLibraryVersion = if (loaded.needsLibraryWrite) -1L else repo.libraryVersion.value
            return
        }
        if (libraryText != null) {
            Log.withTag("JournalStore").w { "No usable backup; starting with the library file only" }
            val loaded = JournalSplit.merge("{}", libraryText)
            AppJson.apply(repo, loaded.snapshot)
            savedLibraryVersion = repo.libraryVersion.value
            return
        }
        Log.withTag("JournalStore").w { "Journal file missing and no usable .bak/.tmp backup found; starting empty" }
        savedLibraryVersion = -1L
    }

    /**
     * Rotates the versioned backup chain .bak.1..5 one slot older
     * (.bak.4 -> .bak.5 ... .bak.1 -> .bak.2), matching JournalStoreDesktop.
     * Foundation's move fails when the destination exists, so each slot is
     * removed first. Only the versioned chain is touched here: the main file
     * and .bak stay intact through this loop.
     */
    private fun rotateVersionedBackups() {
        for (i in 4 downTo 1) {
            val from = versionedBackupPath(i)
            val to = versionedBackupPath(i + 1)
            if (fileManager.fileExistsAtPath(from)) {
                if (fileManager.fileExistsAtPath(to)) {
                    fileManager.removeItemAtPath(to, null)
                }
                fileManager.moveItemAtPath(from, toPath = to, error = null)
            }
        }
        if (fileManager.fileExistsAtPath(versionedBackupPath(1))) {
            fileManager.removeItemAtPath(versionedBackupPath(1), null)
        }
        fileManager.copyItemAtPath(dataPath(), toPath = versionedBackupPath(1), error = null)
    }

    actual fun triggerAutoBackup() = saveLock.withLock {
        try {
            if (!fileManager.fileExistsAtPath(dataPath())) {
                Log.withTag("JournalStore").w { "Cannot auto-backup: no journal file exists" }
                return@withLock
            }
            val autoDir = "$baseDir/.auto"
            fileManager.createDirectoryAtPath(autoDir, withIntermediateDirectories = true, attributes = null, error = null)

            val dateFormatter = NSDateFormatter()
            dateFormatter.dateFormat = "yyyyMMdd_HHmmss"
            val timestamp = dateFormatter.stringFromDate(NSDate())
            val backupFile = "$autoDir/$timestamp.json"

            val text = AppJson.json.encodeToString(repo.fullSnapshot())
            if (writeSealed(text, backupFile)) {
                Log.withTag("JournalStore").i { "Auto-backup created: $backupFile" }
            } else {
                Log.withTag("JournalStore").e { "Failed to create auto-backup at $backupFile" }
                return@withLock
            }

            // Rotate auto-backups: keep only the 10 most recent
            val contents = fileManager.contentsOfDirectoryAtPath(autoDir, error = null)
                ?.filterIsInstance<NSString>()
                ?.map { it as String }
                ?.filter { it.endsWith(".json") }
                ?.sortedDescending()
                ?: emptyList()
            if (contents.size > 10) {
                val toDelete = contents.drop(10)
                for (old in toDelete) {
                    fileManager.removeItemAtPath("$autoDir/$old", null)
                    Log.withTag("JournalStore").i { "Removed old auto-backup: $old" }
                }
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to create auto-backup: ${e.message}" }
        }
    }

    actual fun restoreFromBackup(): Boolean = saveLock.withLock {
        try {
            val backup = backupPath()
            if (!fileManager.fileExistsAtPath(backup)) {
                Log.withTag("JournalStore").w { "Cannot restore from backup: no .bak file exists" }
                return@withLock false
            }
            if (fileManager.fileExistsAtPath(dataPath())) {
                fileManager.removeItemAtPath(dataPath(), null)
            }
            val success = fileManager.copyItemAtPath(backup, toPath = dataPath(), error = null)
            if (success) {
                val libraryBackup = libraryBackupPath()
                if (fileManager.fileExistsAtPath(libraryBackup)) {
                    if (fileManager.fileExistsAtPath(libraryPath())) {
                        fileManager.removeItemAtPath(libraryPath(), null)
                    }
                    fileManager.copyItemAtPath(libraryBackup, toPath = libraryPath(), error = null)
                }
                Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
                repo.clearAll()
                load()
                true
            } else {
                Log.withTag("JournalStore").e { "Failed to restore from backup" }
                false
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to restore from backup: ${e.message}" }
            false
        }
    }

    actual fun save(fullBackup: Boolean) = saveLock.withLock {
        try {
            fileManager.createDirectoryAtPath(baseDir, withIntermediateDirectories = true, attributes = null, error = null)

            // Stamp before the snapshot: a concurrent library edit forces a rewrite next save.
            val libraryVersion = repo.libraryVersion.value
            // Locked snapshot: see JournalStoreDesktop.save() (audit S1).
            val snapshot = repo.fullSnapshot()
            val libraryDirty = savedLibraryVersion != libraryVersion
            if (libraryDirty && !writeLibrary(snapshot, fullBackup)) {
                Log.withTag("JournalStore").w { "Library write failed; skipping this save so both files stay consistent" }
                return@withLock
            }
            val text = JournalSplit.userText(snapshot)

            if (text.length > 50_000_000) {
                Log.withTag("JournalStore").e { "Serialized journal too large (${text.length} chars), refusing to save" }
                return@withLock
            }

            val tmpWritten = writeSealed(text, tempPath())
            if (!tmpWritten) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tempPath()}" }
                return@withLock
            }

            if (fileManager.fileExistsAtPath(dataPath())) {
                if (fullBackup) {
                    // Versioned chain .bak.1..5 (desktop parity; audit "iOS backup rotation").
                    rotateVersionedBackups()
                }
                // .bak is the recovery copy, so refresh it on every save.
                if (fileManager.fileExistsAtPath(backupPath())) {
                    fileManager.removeItemAtPath(backupPath(), null)
                }
                if (fullBackup) {
                    if (!fileManager.moveItemAtPath(dataPath(), toPath = backupPath(), error = null)) {
                        // Copy fallback keeps dataPath in place (also window-free).
                        fileManager.copyItemAtPath(dataPath(), toPath = backupPath(), error = null)
                    }
                } else {
                    fileManager.copyItemAtPath(dataPath(), toPath = backupPath(), error = null)
                }
            }

            if (!fileManager.moveItemAtPath(tempPath(), toPath = dataPath(), error = null)) {
                Log.withTag("JournalStore").w { "Atomic rename failed, falling back to direct write" }
                val direct = writeSealed(text, dataPath())
                if (direct) {
                    if (fileManager.fileExistsAtPath(tempPath())) {
                        fileManager.removeItemAtPath(tempPath(), null)
                    }
                } else {
                    Log.withTag("JournalStore").e { "Failed to write journal data: ${dataPath()}" }
                }
            }

            if (libraryDirty) savedLibraryVersion = libraryVersion
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }

    /** Writes the library file when the library changed; returns true on success. */
    private fun writeLibrary(snapshot: JournalSnapshot, fullBackup: Boolean): Boolean {
        val text = JournalSplit.libraryText(snapshot)
        if (text.length > 50_000_000) {
            Log.withTag("JournalStore").e { "Serialized library too large (${text.length} chars), refusing to save" }
            return false
        }
        return try {
            if (fullBackup && fileManager.fileExistsAtPath(libraryPath())) {
                val libraryBackup = libraryBackupPath()
                if (fileManager.fileExistsAtPath(libraryBackup)) {
                    fileManager.removeItemAtPath(libraryBackup, null)
                }
                fileManager.copyItemAtPath(libraryPath(), toPath = libraryBackup, error = null)
            }
            writeSealed(text, libraryPath())
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to write journal library: ${e.message}" }
            false
        }
    }
}
