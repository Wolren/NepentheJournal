package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.File

actual class JournalStore actual constructor(private val repo: JournalRepository) {

    actual fun dataPath(): String {
        val home = System.getProperty("user.home") ?: "."
        return "$home${File.separator}.psychonautica${File.separator}journal-data.json"
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    actual fun load() {
        val target = File(dataPath())
        val tmp = File(tempPath())

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
                target.copyTo(backup, overwrite = true)
            }

            if (!tmp.renameTo(target)) {
                Log.withTag("JournalStore").w { "Atomic rename failed (target may be locked), falling back to direct write" }
                target.writeText(text)
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }
}
