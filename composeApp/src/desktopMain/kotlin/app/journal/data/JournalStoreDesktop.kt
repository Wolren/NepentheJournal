package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import app.journal.serde.AppJson
import app.journal.util.PlatformLock
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual class JournalStore actual constructor(private val repo: IJournalRepository) {

    private val saveLock = JournalSaveLock.instance

    @Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    @Volatile
    actual var lastLoadIssueSummary: String = ""
        private set

    actual fun dataPath(): String {
        val home = System.getProperty("user.home") ?: "."
        return "$home${File.separator}.nepenthe${File.separator}journal-data.json"
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    private fun libraryPath(): String {
        val parent = File(dataPath()).parentFile ?: File(".")
        return File(parent, "journal-library.json").absolutePath
    }

    private fun libraryBackupPath(): String = libraryPath() + ".bak"

    /** Library version this store last persisted; -1 forces a write on the next save. */
    private var savedLibraryVersion: Long = -1L

    private fun versionedBackupPath(index: Int): String = backupPath() + ".$index"

    actual fun load() = saveLock.withLock {
        val target = File(dataPath())
        val tmp = File(tempPath())

        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        if (!target.exists()) {
            // Missing-target recovery (desktop parity with JournalStoreIos and JournalStoreAndroid.recoverMissingMainFile, audit C3)
            recoverMissingMainFile(tmp, readLibraryText())
            return@withLock
        }

        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (target.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${target.length()} bytes), refusing to load" }
            quarantineUnreadable(RuntimeException("journal file exceeds 50 MB"))
            return@withLock
        }
        try {
            val raw = target.readBytes()
            val text = if (JournalAtRest.looksEncrypted(raw)) JournalAtRest.open(raw) else raw.decodeToString()
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
            quarantineUnreadable(e)
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

    /** Renames the unreadable journal and its .bak so autosave cannot overwrite them. */
    private fun quarantineUnreadable(cause: Exception) {
        val stamp = System.currentTimeMillis()
        val kept = mutableListOf<String>()
        val dir = File(dataPath()).absoluteFile.parentFile
        val candidates = listOf(
            File(dataPath()) to "journal-data.json",
            File(backupPath()) to "journal-data.json.bak",
        )
        for ((file, name) in candidates) {
            if (!file.exists()) continue
            val keptFile = File(dir, "$name.unreadable-$stamp")
            val moved = file.renameTo(keptFile) || file.copyTo(keptFile, overwrite = true).exists()
            if (moved) kept.add(keptFile.name)
        }
        lastLoadHadIssues = true
        lastLoadIssueSummary = buildString {
            append("This device can no longer decrypt the journal (${cause.message ?: cause::class.simpleName}). ")
            if (kept.isEmpty()) append("No sealed copy could be set aside.")
            else append("Sealed copies are kept as ${kept.joinToString()}.")
        }
    }

    /**
     * Called by [load] when the main journal file does not exist: try .bak
     * first, then .tmp, then the per-field salvage, then empty (the exact
     * chain JournalStoreIos.recoverMissingMainFile and
     * JournalStoreAndroid.recoverMissingMainFile implement, sharing the
     * same SnapshotRecovery decode helpers so the three platforms cannot
     * drift). [tmp] is passed in because the caller's orphan cleanup must
     * not run before recovery gets its chance.
     *
     * Flag semantics (wave5 task pin, deliberately narrower than the two
     * mobile stores): a candidate that decodes as a WHOLE file is a
     * complete restore, so [lastLoadHadIssues] stays FALSE; only a
     * candidate that needs partial salvage sets the issue flags. With no
     * usable candidate at all the store starts empty WITHOUT flags, which
     * is also the first-ever-run shape (no journal, no backups yet) and
     * must not raise the recovery banner on a fresh install. The mobile
     * stores flag even a clean backup restore; that divergence is reported,
     * not hidden.
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
            target.parentFile.mkdirs()

            // Stamp before the snapshot: a concurrent library edit forces a rewrite next save.
            val libraryVersion = repo.libraryVersion.value
            // AppJson.snapshot(repo) directly isn't safe here (audit S1).
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

            writeSealed(tmp, text)
            if (!tmp.exists()) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tmp.absolutePath}" }
                return@withLock
            }

            if (target.exists()) {
                if (fullBackup) {
                    // Rotate versioned backups: .bak.4 → .bak.5, .bak.3 → .bak.4, .bak.2 → .bak.3, .bak.1 → .bak.2
                    for (i in 4 downTo 1) {
                        val from = File(versionedBackupPath(i))
                        if (from.exists()) {
                            val to = File(versionedBackupPath(i + 1))
                            if (to.exists()) to.delete()
                            from.renameTo(to)
                        }
                    }
                    // Copy current file to .bak.1 (newest versioned backup)
                    copyDurably(target, File(versionedBackupPath(1)))
                }
                // A light save only recreates a missing recovery copy; full saves refresh it.
                if (fullBackup || !backup.exists()) {
                    copyDurably(target, backup)
                }
            }

            // Never write in place: a torn write leaves a sealed file that fails the tag check.
            if (!replaceWithMove(tmp, target)) {
                Log.withTag("JournalStore").e { "Atomic rename failed; previous journal kept, save dropped" }
                tmp.delete()
                return@withLock
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
            library.parentFile?.mkdirs()
            writeSealed(tmp, text)
            if (fullBackup && library.exists()) {
                copyDurably(library, File(libraryBackupPath()))
            }
            if (!replaceWithMove(tmp, library)) {
                Log.withTag("JournalStore").e { "Atomic rename failed for the library; previous file kept" }
                tmp.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to write journal library: ${e.message}" }
            tmp.delete()
            false
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
            val autoDir = File(target.parentFile, ".auto")
            autoDir.mkdirs()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
            val backupFile = File(autoDir, "$timestamp.json")
            val text = AppJson.json.encodeToString(repo.fullSnapshot())
            writeSealed(backupFile, text)
            Log.withTag("JournalStore").i { "Auto-backup created: ${backupFile.absolutePath}" }

            // Rotate auto-backups: keep only the 10 most recent
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
            copyDurably(backup, target)
            val libraryBackup = File(libraryBackupPath())
            if (libraryBackup.exists()) {
                copyDurably(libraryBackup, File(libraryPath()))
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

    /** Writes [text] sealed and flushed, so the rename that follows is the only atomic step. */
    private fun writeSealed(file: File, text: String) {
        val sealed = JournalAtRest.seal(text)
        FileOutputStream(file).use { out ->
            out.write(sealed)
            out.fd.sync()
        }
    }

    /** Copies [from] over [to] through a temp file, so a crash cannot leave a torn backup. */
    private fun copyDurably(from: File, to: File): Boolean {
        return try {
            val tmp = File(to.absoluteFile.parentFile, "${to.name}.tmp")
            FileInputStream(from).use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            replaceWithMove(tmp, to)
        } catch (e: Exception) {
            Log.withTag("JournalStore").w { "Durable copy failed: ${e.message}" }
            false
        }
    }

    /**
     * Moves [from] over [to], replacing an existing target; ATOMIC_MOVE where the filesystem
     * allows it. Retries because a scanner or a reader can hold the target for a moment.
     */
    private fun replaceWithMove(from: File, to: File): Boolean {
        for (attempt in 1..4) {
            try {
                java.nio.file.Files.move(
                    from.toPath(), to.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
                return true
            } catch (_: Exception) {
                try {
                    java.nio.file.Files.move(
                        from.toPath(), to.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                    return true
                } catch (_: Exception) {
                    if (attempt == 4) return false
                    Thread.sleep(100L)
                }
            }
        }
        return false
    }
}
