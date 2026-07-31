package app.journal.data

import app.journal.NepentheApp
import app.journal.log.Log
import app.journal.model.*
import app.journal.util.PlatformLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.io.File

actual class JournalStore actual constructor(private val repo: JournalRepository) {

    /** Serializes save/load so concurrent saves cannot interleave writes to the shared tmp file. */
    private val saveLock = PlatformLock()

    private val baseDir: File
        get() = File(NepentheApp.appContext.filesDir, ".psychonautica")

    actual fun dataPath(): String {
        return File(baseDir, "journal-data.json").absolutePath
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    actual fun load() = saveLock.withLock {
        val target = File(dataPath())
        val tmp = File(tempPath())

        if (tmp.exists()) {
            Log.withTag("JournalStore").w { "Cleaning orphaned temp file from prior save" }
            tmp.delete()
        }

        if (!target.exists()) return@withLock
        if (target.length() > 50_000_000) {
            Log.withTag("JournalStore").e { "Journal file too large (${target.length()} bytes), refusing to load" }
            return@withLock
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

    /**
     * Best-effort recovery when the whole-file parse fails: decode each top-level
     * list field on its own so a single malformed record only drops that record,
     * not the entire category. Uses proper [JsonElement] parsing — not string splitting.
     */
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

    actual fun save(fullBackup: Boolean) = saveLock.withLock {
        val target = File(dataPath())
        val tmp = File(tempPath())
        val backup = File(backupPath())

        try {
            baseDir.mkdirs()

            // Locked snapshot — see JournalStoreDesktop.save() (audit S1).
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
                Log.withTag("JournalStore").w { "Atomic rename failed, falling back to direct write" }
                target.writeText(text)
                tmp.delete() // stale temp from the failed rename must not linger
            }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to save journal data: ${e.message}" }
        }
    }

    actual fun restoreFromBackup(): Boolean {
        Log.withTag("JournalStore").w { "restoreFromBackup not implemented on Android" }
        return false
    }

    actual fun triggerAutoBackup() {
        val target = File(dataPath())
        if (!target.exists()) return
        try {
            val autoDir = File(baseDir, ".auto")
            autoDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
            val backupFile = File(autoDir, "$timestamp.json")
            target.copyTo(backupFile, overwrite = false)
            Log.withTag("JournalStore").i { "Auto-backup created: ${backupFile.absolutePath}" }
        } catch (e: Exception) {
            Log.withTag("JournalStore").e(e) { "Failed to create auto-backup: ${e.message}" }
        }
    }

    @Volatile
    actual var lastLoadHadIssues: Boolean = false
        private set

    @Volatile
    actual var lastLoadIssueSummary: String = ""
        private set
}
