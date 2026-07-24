package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import app.journal.util.currentTimeMillis
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class)
actual class JournalStore actual constructor(private val repo: JournalRepository) {

    private val fileManager = NSFileManager.defaultManager
    private val baseDir: String
        get() {
            val docs = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory, NSUserDomainMask, true
            ).firstOrNull() as? String ?: return "."
            return "$docs/.psychonautica"
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

    actual fun load() {
        lastLoadHadIssues = false
        lastLoadIssueSummary = ""

        if (fileManager.fileExistsAtPath(tempPath())) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            fileManager.removeItemAtPath(tempPath(), null)
        }

        if (!fileManager.fileExistsAtPath(dataPath())) return

        // Size check via file attributes
        val attrs = fileManager.attributesOfItemAtPath(dataPath(), null)
        val fileSize = (attrs?.get(NSFileSize) as? NSNumber)?.longValue ?: 0
        if (fileSize > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${fileSize} bytes), refusing to load" }
            return
        }

        try {
            val text = NSString.stringWithContentsOfFile(dataPath(), encoding = NSUTF8StringEncoding, error = null) ?: return
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

    actual fun triggerAutoBackup() {
        try {
            if (!fileManager.fileExistsAtPath(dataPath())) {
                Log.withTag("JournalStore").w { "Cannot auto-backup: no journal file exists" }
                return
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
                return
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

    actual fun restoreFromBackup(): Boolean {
        return try {
            val backup = backupPath()
            if (!fileManager.fileExistsAtPath(backup)) {
                Log.withTag("JournalStore").w { "Cannot restore from backup: no .bak file exists" }
                return false
            }
            // Remove current file if exists
            if (fileManager.fileExistsAtPath(dataPath())) {
                fileManager.removeItemAtPath(dataPath(), null)
            }
            val success = fileManager.copyItemAtPath(backup, toPath = dataPath(), error = null)
            if (success) {
                Log.withTag("JournalStore").i { "Restored journal from .bak backup" }
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
            savedAt = currentTimeMillis(),
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
        try {
            fileManager.createDirectoryAtPath(baseDir, withIntermediateDirectories = true, attributes = null, error = null)

            val snapshot = AppJson.snapshot(repo)
            val text = AppJson.json.encodeToString(snapshot)

            if (text.length > 50_000_000) {
                Log.withTag("JournalStore").e { "Serialized journal too large (${text.length} chars), refusing to save" }
                return
            }

            // Write temp, then rename atomically
            val tmpWritten = (text as NSString).writeToFile(tempPath(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
            if (!tmpWritten) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tempPath()}" }
                return
            }

            if (fileManager.fileExistsAtPath(dataPath())) {
                // Remove existing backup, copy current to backup, remove current
                if (fileManager.fileExistsAtPath(backupPath())) {
                    fileManager.removeItemAtPath(backupPath(), null)
                }
                fileManager.copyItemAtPath(dataPath(), toPath = backupPath(), error = null)
                fileManager.removeItemAtPath(dataPath(), null)
            }

            if (!fileManager.moveItemAtPath(tempPath(), toPath = dataPath(), error = null)) {
                Log.withTag("JournalStore").w { "Atomic rename failed, falling back to direct write" }
                (text as NSString).writeToFile(dataPath(), atomically = true, encoding = NSUTF8StringEncoding, error = null)
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }
}
