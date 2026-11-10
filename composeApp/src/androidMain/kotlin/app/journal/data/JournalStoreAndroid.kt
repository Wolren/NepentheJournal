package app.journal.data

import app.journal.NepentheApp
import app.journal.log.Log
import app.journal.serde.AppJson
import app.journal.util.PlatformLock
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException

actual class JournalStore actual constructor(private val repo: IJournalRepository) {

    private val saveLock = JournalSaveLock.instance

    private val baseDir: File
        get() = File(NepentheApp.appContext.filesDir, ".nepenthe")

    actual fun dataPath(): String {
        return File(baseDir, "journal-data.json").absolutePath
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    private fun libraryPath(): String = File(baseDir, "journal-library.json").absolutePath

    private fun libraryBackupPath(): String = libraryPath() + ".bak"

    /** Library version this store last persisted; -1 forces a write on the next save. */
    private var savedLibraryVersion: Long = -1L

    @Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    @Volatile
    actual var lastLoadIssueSummary: String = ""
        private set

    actual fun load() = saveLock.withLock {
        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        val target = File(dataPath())
        val tmp = File(tempPath())

        if (!target.exists()) {
            // Audit C3 mirror (the iOS JournalStoreIos.load recovery)
            recoverMissingMainFile(tmp, readLibraryText())
            return@withLock
        }

        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (target.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${target.length()} bytes), refusing to load" }
            return@withLock
        }
        try {
            val raw = target.readBytes()
            val text = if (JournalAtRest.looksEncrypted(raw)) JournalAtRest.open(raw) else raw.decodeToString()
            // Same recovery path + issue flags as JournalStoreDesktop so the App.kt recovery banner works on Android too (audit high).
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
        val file = File(libraryPath())
        if (!file.exists()) return null
        if (file.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal library too large (${file.length()} bytes), ignoring" }
            return null
        }
        return try {
            val raw = file.readBytes()
            if (JournalAtRest.looksEncrypted(raw)) JournalAtRest.open(raw) else raw.decodeToString()
        } catch (e: Exception) {
            Log.withTag("JournalStore").w { "Unreadable journal library: ${e.message}" }
            null
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
    private fun recoverMissingMainFile(tmp: File, libraryText: String?) {
        val candidates = listOf(File(backupPath()) to ".bak", tmp to ".tmp")
        var partialText: String? = null
        var partialLabel: String? = null
        for ((file, label) in candidates) {
            if (!file.exists()) continue
            val size = file.length()
            if (size < 0 || size > 50_000_000) continue
            val text = try {
                val raw = file.readBytes()
                if (JournalAtRest.looksEncrypted(raw)) JournalAtRest.open(raw) else raw.decodeToString()
            } catch (e: Exception) {
                Log.withTag("JournalStore").w { "Unreadable $label candidate: ${e.message}" }
                continue
            }
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
            lastLoadIssueSummary =
                "Main journal file was missing; partially recovered from $partialLabel: ${loaded.userParseError ?: "ok"}"
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

    actual fun save(fullBackup: Boolean) = saveLock.withLock {
        val target = File(dataPath())
        val tmp = File(tempPath())
        val backup = File(backupPath())

        try {
            baseDir.mkdirs()

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

            tmp.writeBytes(JournalAtRest.seal(text))
            if (!tmp.exists()) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tmp.absolutePath}" }
                return@withLock
            }

            if (target.exists()) {
                // Always refresh .bak: it is the recovery copy, and 53 KB is cheap.
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
                        target.writeBytes(JournalAtRest.seal(text))
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
        val library = File(libraryPath())
        val tmp = File(libraryPath() + ".tmp")
        return try {
            tmp.writeBytes(JournalAtRest.seal(text))
            if (fullBackup && library.exists()) {
                library.copyTo(File(libraryBackupPath()), overwrite = true)
            }
            if (!tmp.renameTo(library)) {
                library.writeBytes(JournalAtRest.seal(text))
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to write journal library: ${e.message}" }
            tmp.delete()
            false
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
            val libraryBackup = File(libraryBackupPath())
            if (libraryBackup.exists()) {
                libraryBackup.copyTo(File(libraryPath()), overwrite = true)
            }
            Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
            repo.clearAll()
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
            val text = AppJson.json.encodeToString(repo.fullSnapshot())
            backupFile.writeBytes(JournalAtRest.seal(text))
            Log.withTag("JournalStore").i { "Auto-backup created: ${backupFile.absolutePath}" }

            // Rotate auto-backups: keep only the 10 most recent (desktop/iOS parity; triggerAutoBackup runs on every close so the directory was unbounded).
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
