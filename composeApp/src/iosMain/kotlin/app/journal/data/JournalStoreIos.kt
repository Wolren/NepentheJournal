package app.journal.data

import app.journal.log.Log
import app.journal.serde.AppJson
import app.journal.util.PlatformLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.*
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual class JournalStore actual constructor(private val repo: IJournalRepository) {

    /** Serializes save/load so concurrent saves cannot interleave writes to the shared tmp file. */
    private val saveLock = PlatformLock()

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

    /** Set to true when [load] had to use [recoverSnapshot] (partial recovery). */
    @kotlin.concurrent.Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    /** Human-readable summary of what was recovered during the last [load]. */
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
            // Audit C3: a crash inside save()'s backup rotation can leave the main
            // file missing while .bak / .tmp still hold complete copies. Recover
            // automatically instead of starting the session blank.
            recoverMissingMainFile()
            return@withLock
        }

        // Clean the orphaned temp file only now that the main file is known to exist.
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
            val text = NSString.stringWithContentsOfFile(dataPath(), encoding = NSUTF8StringEncoding, error = null) ?: return@withLock
            val decoded = decodeSnapshotWithRecovery(text)
            if (decoded.parseError != null) {
                lastLoadHadIssues = true
                lastLoadIssueSummary = "Recovered from parse failure: ${decoded.parseError}"
            }
            AppJson.apply(repo, decoded.snapshot)
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to load journal data: ${e.message}" }
        }
    }

    /**
     * Called by [load] when the main journal file does not exist: try .bak first,
     * then .tmp, before giving up and starting empty (audit C3 fix). A candidate
     * that decodes as a whole file is used as-is; only when every candidate is
     * corrupt does the per-field recovery salvage what it can.
     */
    private fun recoverMissingMainFile() {
        val candidates = listOf(backupPath() to ".bak", tempPath() to ".tmp")
        var partialText: String? = null
        var partialLabel: String? = null
        for ((path, label) in candidates) {
            if (!fileManager.fileExistsAtPath(path)) continue
            val size = fileSize(path)
            if (size < 0 || size > 50_000_000) continue
            val text = NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null) ?: continue
            val clean = runCatching { AppJson.json.decodeFromString<JournalSnapshot>(text) }.getOrNull()
            if (clean != null) {
                Log.withTag("JournalStore").w { "Journal file missing; recovered from $label backup" }
                lastLoadHadIssues = true
                lastLoadIssueSummary = "Main journal file was missing; restored from $label"
                AppJson.apply(repo, clean)
                return
            }
            if (partialText == null) {
                partialText = text
                partialLabel = label
            }
        }
        if (partialText != null) {
            Log.withTag("JournalStore").w { "Journal file missing; partially recovering from $partialLabel" }
            val decoded = decodeSnapshotWithRecovery(partialText)
            lastLoadHadIssues = true
            lastLoadIssueSummary = "Main journal file was missing; partially recovered from $partialLabel: ${decoded.parseError ?: "ok"}"
            AppJson.apply(repo, decoded.snapshot)
            return
        }
        Log.withTag("JournalStore").w { "Journal file missing and no usable .bak/.tmp backup found; starting empty" }
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
        // Newest versioned backup slot: copy of the file we are about to replace.
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

            if (fileManager.fileExistsAtPath(backupFile)) {
                fileManager.removeItemAtPath(backupFile, null)
            }
            val success = fileManager.copyItemAtPath(dataPath(), toPath = backupFile, error = null)
            if (success) {
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
            // Remove current file if exists
            if (fileManager.fileExistsAtPath(dataPath())) {
                fileManager.removeItemAtPath(dataPath(), null)
            }
            val success = fileManager.copyItemAtPath(backup, toPath = dataPath(), error = null)
            if (success) {
                Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
                // The backup is authoritative: clear live state first so entities
                // created after the backup was taken are not merged back on top.
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

            // Locked snapshot - see JournalStoreDesktop.save() (audit S1).
            val snapshot = repo.fullSnapshot()
            val text = AppJson.json.encodeToString(snapshot)

            if (text.length > 50_000_000) {
                Log.withTag("JournalStore").e { "Serialized journal too large (${text.length} chars), refusing to save" }
                return@withLock
            }

            // Write temp first: nothing below starts until the new payload is a
            // complete file on disk.
            val tmpWritten = (text as NSString).writeToFile(tempPath(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!tmpWritten) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tempPath()}" }
                return@withLock
            }

            if (fullBackup && fileManager.fileExistsAtPath(dataPath())) {
                // Versioned chain .bak.1..5 (desktop parity; audit "iOS backup rotation").
                rotateVersionedBackups()
                // Promote the current file to .bak by MOVING it so dataPath's name is
                // free for the tmp promote. Never delete dataPath outright: through
                // every step of this rotation at least two of {dataPath, .bak, .tmp}
                // hold a complete copy, so no crash can leave zero journal files
                // (audit C3).
                if (fileManager.fileExistsAtPath(backupPath())) {
                    fileManager.removeItemAtPath(backupPath(), null)
                }
                if (!fileManager.moveItemAtPath(dataPath(), toPath = backupPath(), error = null)) {
                    // Copy fallback keeps dataPath in place (also window-free).
                    fileManager.copyItemAtPath(dataPath(), toPath = backupPath(), error = null)
                }
            }

            if (!fileManager.moveItemAtPath(tempPath(), toPath = dataPath(), error = null)) {
                // Destination still exists (light save, or the .bak promote fell back
                // to a copy): NSString's atomic write replaces it in place with no
                // missing-file window.
                Log.withTag("JournalStore").w { "Atomic rename failed, falling back to direct write" }
                val direct = (text as NSString).writeToFile(dataPath(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
                if (direct) {
                    if (fileManager.fileExistsAtPath(tempPath())) {
                        fileManager.removeItemAtPath(tempPath(), null)
                    }
                } else {
                    Log.withTag("JournalStore").e { "Failed to write journal data: ${dataPath()}" }
                }
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }
}
