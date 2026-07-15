package app.journal.data

import app.journal.log.Log
import app.journal.util.currentTimeMillis
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import platform.Foundation.*

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

    actual fun load() {
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
                    recoverSnapshot(text)
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
                Log.withTag("JournalStore").e { "Failed to write temp file: $tempPath" }
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
