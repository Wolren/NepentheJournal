package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

actual class JournalStore actual constructor(private val repo: JournalRepository) {

    /** Set to true when [load] had to use [recoverSnapshot] (partial recovery). */
    @Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    /** Human-readable summary of what was recovered during the last [load]. */
    @Volatile
    actual var lastLoadIssueSummary: String = ""
        private set

    actual fun dataPath(): String {
        val home = System.getProperty("user.home") ?: "."
        return "$home${File.separator}.psychonautica${File.separator}journal-data.json"
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    private fun versionedBackupPath(index: Int): String = backupPath() + ".$index"

    actual fun load() {
        val target = File(dataPath())
        val tmp = File(tempPath())

        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (!target.exists()) return
        if (target.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${target.length()} bytes), refusing to load" }
            return
        }
        try {
            val text = target.readText()
            val snapshot = runCatching { AppJson.json.decodeFromString<JournalSnapshot>(text) }
                .getOrElse { e ->
                    Log.withTag("JournalStore").w { "Journal data failed full parse, attempting per-list recovery: ${e.message}" }
                    val recovered = recoverSnapshot(text)
                    lastLoadHadIssues = true
                    lastLoadIssueSummary = "Recovered from parse failure: ${e.message}"
                    recovered
                }
            AppJson.apply(repo, snapshot)
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to load journal data: ${e.message}" }
        }
    }

    private fun recoverSnapshot(text: String): JournalSnapshot {
        fun <T : Any> decodeList(key: String, serializer: KSerializer<T>): List<T> {
            return try {
                val root = Json.parseToJsonElement(text).jsonObject
                val arr = root[key]?.jsonArray ?: return emptyList()
                arr.mapNotNull { element ->
                    runCatching {
                        AppJson.json.decodeFromJsonElement(serializer, element)
                    }.getOrNull()
                }
            } catch (_: Exception) { emptyList() }
        }
        val sessions = decodeList("sessions", Session.serializer())
        val substances = decodeList("substances", Substance.serializer())
        val doses = decodeList("doses", Dose.serializer())
        val notes = decodeList("notes", Note.serializer())
        val timelineEvents = decodeList("timelineEvents", TimelineEvent.serializer())
        val interactions = decodeList("interactions", Interaction.serializer())
        val effects = decodeList("effects", Effect.serializer())
        val customUnits = decodeList("customUnits", CustomUnit.serializer())
        Log.withTag("JournalStore").i {
            "Recovered journal: ${sessions.size} sessions, ${substances.size} substances, " +
            "${doses.size} doses, ${notes.size} notes, ${timelineEvents.size} events, " +
            "${interactions.size} interactions, ${effects.size} effects, ${customUnits.size} units"
        }
        return JournalSnapshot(
            savedAt = app.journal.util.currentTimeMillis(),
            sessions = sessions,
            substances = substances,
            doses = doses,
            notes = notes,
            timelineEvents = timelineEvents,
            interactions = interactions,
            effects = effects,
            customUnits = customUnits
        )
    }

    actual fun save() {
        val target = File(dataPath())
        val tmp = File(tempPath())
        val backup = File(backupPath())

        try {
            target.parentFile.mkdirs()

            val snapshot = AppJson.snapshot(repo)
            val text = AppJson.json.encodeToString(snapshot)

            if (text.length > 50_000_000) {
                Log.withTag("JournalStore").e { "Serialized journal too large (${text.length} chars), refusing to save" }
                return
            }

            tmp.writeText(text)
            if (!tmp.exists()) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tmp.absolutePath}" }
                return
            }

            if (target.exists()) {
                // Rotate versioned backups: .bak.4 → .bak.5, .bak.3 → .bak.4, .bak.2 → .bak.3, .bak.1 → .bak.2
                for (i in 4 downTo 1) {
                    val from = File(versionedBackupPath(i))
                    if (from.exists()) {
                        from.renameTo(File(versionedBackupPath(i + 1)))
                    }
                }
                // Copy current file to .bak.1 (newest versioned backup)
                target.copyTo(File(versionedBackupPath(1)), overwrite = true)
                // Keep .bak as immediate-previous snapshot
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
                if (!success) {
                    Log.withTag("JournalStore").e { "Failed to write journal data after 3 attempts" }
                }
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }

    /**
     * Creates a timestamped backup copy in the .auto/ subdirectory next to the journal data file,
     * named YYYYMMDD_HHmmss.json. Intended to be called on app close from the UI layer.
     * After creating the new backup, rotates old backups keeping only the 10 most recent.
     */
    actual fun triggerAutoBackup() {
        try {
            val target = File(dataPath())
            if (!target.exists()) {
                Log.withTag("JournalStore").w { "Cannot auto-backup: no journal file exists" }
                return
            }
            val autoDir = File(target.parentFile, ".auto")
            autoDir.mkdirs()
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val backupFile = File(autoDir, "$timestamp.json")
            target.copyTo(backupFile, overwrite = false)
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
    actual fun restoreFromBackup(): Boolean {
        try {
            val target = File(dataPath())
            val backup = File(backupPath())
            if (!backup.exists()) {
                Log.withTag("JournalStore").w { "Cannot restore from backup: no .bak file exists" }
                return false
            }
            backup.copyTo(target, overwrite = true)
            Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
            // Reload the restored data
            load()
            return true
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to restore from backup: ${e.message}" }
            return false
        }
    }
}
