package app.journal.data

import app.journal.NepentheApp
import app.journal.log.Log
import app.journal.model.*
import java.io.File

/**
 * Android actual: stores journal data as a JSON file in app internal storage.
 *
 * Path: [appContext.filesDir]/.psychonautica/journal-data.json
 *
 * Uses atomic write pattern to prevent data loss on crash:
 * 1. Write to journal-data.json.tmp (temp file, same filesystem)
 * 2. Rename tmp -> target (atomic on the same filesystem)
 * 3. Keep journal-data.json.bak as the last successful save
 * 4. On load, clean up orphaned .tmp files from prior crashes
 */
actual class JournalStore actual constructor(private val repo: JournalRepository) {

    private val baseDir: File
        get() = File(NepentheApp.appContext.filesDir, ".psychonautica")

    actual fun dataPath(): String {
        return File(baseDir, "journal-data.json").absolutePath
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"
    actual fun load() {
        val target = File(dataPath())
        val tmp = File(tempPath())

        // Clean up orphaned temp file from a prior crash
        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (!target.exists()) return
        try {
            val text = target.readText()
            // Decode each entity list independently. A corrupt field in one list
            // must not wipe the entire journal — recover what we can.
            val snapshot = runCatching { JournalJson.json.decodeFromString<JournalSnapshot>(text) }
                .getOrElse { e ->
                    Log.withTag("JournalStore").w { "Journal data failed full parse, attempting per-list recovery: ${e.message}" }
                    recoverSnapshot(text)
                }
            JournalJson.apply(repo, snapshot)
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to load journal data: ${e.message}" }
        }
    }

    /**
     * Best-effort recovery when the whole-file parse fails: decode each top-level
     * list field on its own so a single malformed record only drops that record,
     * not the entire category. Falls back to an empty list per field rather than
     * losing everything.
     */
    private fun recoverSnapshot(text: String): JournalSnapshot {
        fun <T : Any> decodeList(path: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
            val marker = "\"$path\""
            val start = text.indexOf(marker)
            if (start < 0) return emptyList()
            val arrStart = text.indexOf('[', start)
            if (arrStart < 0) return emptyList()
            val arrEnd = text.indexOf(']', arrStart)
            if (arrEnd < 0) return emptyList()
            val arrText = text.substring(arrStart, arrEnd + 1)
            // Split flat array of objects on "},{" boundaries. Objects here are
            // flat (no nested arrays), so this is safe.
            val inner = arrText.removeSurrounding("[", "]").trim()
            if (inner.isEmpty()) return emptyList()
            val rawItems = inner.split("},{")
            val items = rawItems.mapNotNull { chunk ->
                val trimmed = chunk.trim().removePrefix("{").removeSuffix("}").trim()
                if (trimmed.isEmpty()) return@mapNotNull null
                val obj = "{$trimmed}"
                runCatching { JournalJson.json.decodeFromString(serializer, obj) }.getOrNull()
            }
            return items
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
            // Ensure directory exists
            baseDir.mkdirs()

            // Build snapshot
            val snapshot = JournalJson.snapshot(repo)
            val text = JournalJson.json.encodeToString(snapshot)

            // Atomic write: write to temp, then rename
            tmp.writeText(text)
            if (!tmp.exists()) {
                Log.withTag("JournalStore").e { "Failed to write temp file: ${tmp.absolutePath}" }
                return
            }

            // Rotate backup: keep previous good save
            if (target.exists()) {
                target.copyTo(backup, overwrite = true)
            }

            // Atomic rename (same filesystem)
            tmp.renameTo(target)
            if (!target.exists()) {
                Log.withTag("JournalStore").w { "Atomic rename failed, falling back to direct write" }
                target.writeText(text)
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }
}
