package app.journal.data

import app.journal.NepentheApp
import app.journal.log.Log
import app.journal.serde.AppJson
import app.journal.util.PlatformLock
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

actual class JournalStore actual constructor(private val repo: IJournalRepository) {

    /** Serializes save/load so concurrent saves cannot interleave writes to the shared tmp file. */
    private val saveLock = PlatformLock()

    private val baseDir: File
        get() = File(NepentheApp.appContext.filesDir, ".nepenthe")

    actual fun dataPath(): String {
        return File(baseDir, "journal-data.json").absolutePath
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    /** Set to true when [load] had to use [recoverSnapshot] (partial recovery). */
    @Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    /** Human-readable summary of what was recovered during the last [load]. */
    @Volatile
    actual var lastLoadIssueSummary: String = ""
        private set

    actual fun load() = saveLock.withLock {
        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        val target = File(dataPath())
        val tmp = File(tempPath())

        if (!target.exists()) {
            // Audit C3 mirror (the iOS JournalStoreIos.load recovery): a crash
            // inside save()'s backup rotation can leave the main file missing
            // while .bak / .tmp still hold complete copies. Recover
            // automatically instead of starting the session blank.
            recoverMissingMainFile(tmp)
            return@withLock
        }

        // Clean the orphaned temp file only now that the main file is known to exist.
        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (target.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${target.length()} bytes), refusing to load" }
            return@withLock
        }
        try {
            val text = target.readText()
            // Same recovery path + issue flags as JournalStoreDesktop so the
            // App.kt recovery banner works on Android too (audit HIGH).
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
     * Called by [load] when the main journal file does not exist: try .bak
     * first, then .tmp, before giving up and starting empty. Mirrors
     * JournalStoreIos.recoverMissingMainFile exactly (audit C3): a candidate
     * that decodes as a whole file is used as-is; only when every candidate
     * is corrupt does the per-field recovery salvage what it can. Decoding
     * goes through the shared SnapshotRecovery helpers so Android's salvage
     * rules cannot drift from desktop/iOS. [tmp] is passed in because the
     * caller's orphan cleanup must not run before recovery gets its chance.
     */
    private fun recoverMissingMainFile(tmp: File) {
        val candidates = listOf(File(backupPath()) to ".bak", tmp to ".tmp")
        var partialText: String? = null
        var partialLabel: String? = null
        for ((file, label) in candidates) {
            if (!file.exists()) continue
            val size = file.length()
            if (size < 0 || size > 50_000_000) continue
            val text = try {
                file.readText()
            } catch (e: Exception) {
                Log.withTag("JournalStore").w { "Unreadable $label candidate: ${e.message}" }
                continue
            }
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
            lastLoadIssueSummary =
                "Main journal file was missing; partially recovered from $partialLabel: ${decoded.parseError ?: "ok"}"
            AppJson.apply(repo, decoded.snapshot)
            return
        }
        Log.withTag("JournalStore").w { "Journal file missing and no usable .bak/.tmp backup found; starting empty" }
    }

    actual fun save(fullBackup: Boolean) = saveLock.withLock {
        val target = File(dataPath())
        val tmp = File(tempPath())
        val backup = File(backupPath())

        try {
            baseDir.mkdirs()

            // Locked snapshot - see JournalStoreDesktop.save() (audit S1).
            val snapshot = repo.fullSnapshot()
            val text = AppJson.json.encodeToString(snapshot)

            if (text.length > 50_000_000) {
                Log.withTag("JournalStore").e { "Serialized journal too large (${text.length} chars), refusing to save" }
                return@withLock
            }

            tmp.writeText(text)
            if (!tmp.exists()) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tmp.absolutePath}" }
                return@withLock
            }

            if (fullBackup && target.exists()) {
                target.copyTo(backup, overwrite = true)
            }

            if (!tmp.renameTo(target)) {
                Log.withTag("JournalStore").w { "Atomic rename failed (target may be locked), falling back to direct write with retry" }
                // Retry up to 3 times total (initial + 2) with 100ms delay between attempts
                var success = false
                for (attempt in 1..3) {
                    try {
                        if (attempt > 1) {
                            Thread.sleep(100L)
                        }
                        target.writeText(text)
                        success = true
                        break
                    } catch (e: IOException) {
                        Log.withTag("JournalStore").w { "Direct write attempt $attempt failed: ${e.message}" }
                    }
                }
                if (success) {
                    tmp.delete() // stale temp from the failed rename must not linger
                } else {
                    Log.withTag("JournalStore").e { "Failed to write journal data after 3 attempts" }
                }
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }

    /**
     * Replaces the main journal file from the .bak backup and reloads.
     * Returns true if restore succeeded, false if no .bak was available.
     */
    actual fun restoreFromBackup(): Boolean = saveLock.withLock {
        try {
            val target = File(dataPath())
            val backup = File(backupPath())
            if (!backup.exists()) {
                Log.withTag("JournalStore").w { "Cannot restore from backup: no .bak file exists" }
                return@withLock false
            }
            backup.copyTo(target, overwrite = true)
            Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
            // The backup is authoritative: clear live state first so entities created
            // after the backup was taken are not merged back on top of the restore.
            repo.clearAll()
            // Reload the restored data
            load()
            return@withLock true
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to restore from backup: ${e.message}" }
            return@withLock false
        }
    }

    /**
     * Creates a timestamped backup copy in the .auto/ subdirectory next to the journal data file,
     * named YYYYMMDD_HHmmss.json. Intended to be called on app close from the UI layer.
     * After creating the new backup, rotates old backups keeping only the 10 most recent.
     */
    actual fun triggerAutoBackup() = saveLock.withLock {
        try {
            val target = File(dataPath())
            if (!target.exists()) {
                Log.withTag("JournalStore").w { "Cannot auto-backup: no journal file exists" }
                return@withLock
            }
            val autoDir = File(baseDir, ".auto")
            autoDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val backupFile = File(autoDir, "$timestamp.json")
            target.copyTo(backupFile, overwrite = false)
            Log.withTag("JournalStore").i { "Auto-backup created: ${backupFile.absolutePath}" }

            // Rotate auto-backups: keep only the 10 most recent (desktop/iOS parity;
            // triggerAutoBackup runs on every close so the directory was unbounded).
            val allAutoBackups = autoDir.listFiles()
                ?.filter { it.name.endsWith(".json") }
                ?.sortedByDescending { it.name }
                ?: emptyList()
            if (allAutoBackups.size > 10) {
                val toDelete = allAutoBackups.drop(10)
                for (old in toDelete) {
                    if (old.delete()) {
                        Log.withTag("JournalStore").i { "Removed old auto-backup: ${old.name}" }
                    } else {
                        Log.withTag("JournalStore").w { "Failed to remove old auto-backup: ${old.name}" }
                    }
                }
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to create auto-backup: ${e.message}" }
        }
    }
}
