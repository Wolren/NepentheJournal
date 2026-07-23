package app.journal.data

import app.journal.model.*
import app.journal.log.Log
import app.journal.util.currentTimeMillis
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full serializable snapshot of the journal's data store.
 * Used for JSON file persistence and backup/restore.
 */
@Serializable
data class JournalSnapshot(
    val version: Int = CURRENT_VERSION,
    val savedAt: Long,
    val sessions: List<Session> = emptyList(),
    val substances: List<Substance> = emptyList(),
    val doses: List<Dose> = emptyList(),
    val notes: List<Note> = emptyList(),
    val timelineEvents: List<TimelineEvent> = emptyList(),
    val interactions: List<Interaction> = emptyList(),
    val effects: List<Effect> = emptyList(),
    val customUnits: List<CustomUnit> = emptyList(),
    val useShulginRating: Boolean = false,
    val useSubstanceColors: Boolean = true,
    val obsidianVaultPath: String = "",
    val obsidianAutoExport: Boolean = false,
    val obsidianSubfolder: String = "Nepenthe",
    val obsidianFileOrganization: String = "flat",
    val showSessionsTrendChart: Boolean = false
) {
    companion object {
        const val CURRENT_VERSION = 5
    }
}

/**
 * Handles loading/saving JournalRepository state to a JSON file.
 * Platform-specific path resolution via expect/actual.
 */
expect class JournalStore(repo: JournalRepository) {
    fun load()
    fun save()
    fun dataPath(): String
}

/**
 * Canonical JSON serialization config for the entire app.
 * Used by persistence, sync, and export.
 *
 * Config: ignoreUnknownKeys=true (forward compat), encodeDefaults=true (consistent wire format).
 * For human-readable output (export, Obsidian), use AppJson.pretty instead.
 */
object AppJson {
    val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Pretty-printed variant for user-facing export files. */
    val pretty = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun snapshot(repo: JournalRepository): JournalSnapshot {
        return JournalSnapshot(
            savedAt = currentTimeMillis(),
            sessions = repo.sessions.value,
            substances = repo.substances.value,
            doses = repo.doses.value,
            notes = repo.notes.value,
            timelineEvents = repo.timelineEvents.value,
            interactions = repo.interactions.value,
            effects = repo.effects.value,
            customUnits = repo.customUnits.value,
            useShulginRating = repo.useShulginRating.value,
            useSubstanceColors = repo.useSubstanceColors.value,
            obsidianVaultPath = repo.obsidianVaultPath.value,
            obsidianAutoExport = repo.obsidianAutoExport.value,
            obsidianSubfolder = repo.obsidianSubfolder.value,
            obsidianFileOrganization = repo.obsidianFileOrganization.value,
            showSessionsTrendChart = repo.showSessionsTrendChart.value
        )
    }

    fun apply(repo: JournalRepository, snapshot: JournalSnapshot) {
        if (snapshot.version != JournalSnapshot.CURRENT_VERSION) {
            Log.withTag("JournalStore").w { "JournalSnapshot version mismatch: file v${snapshot.version}, app v${JournalSnapshot.CURRENT_VERSION}. Data may not load correctly." }
        }
        repo.applyBatch(
            sessions = snapshot.sessions,
            substances = snapshot.substances,
            doses = snapshot.doses,
            notes = snapshot.notes,
            timelineEvents = snapshot.timelineEvents,
            interactions = snapshot.interactions,
            effects = snapshot.effects,
            customUnits = snapshot.customUnits
        )
        repo.setShulginRating(snapshot.useShulginRating)
        repo.setSubstanceColors(snapshot.useSubstanceColors)
        repo.setObsidianVaultPath(snapshot.obsidianVaultPath)
        repo.setObsidianAutoExport(snapshot.obsidianAutoExport)
        repo.setObsidianSubfolder(snapshot.obsidianSubfolder)
        repo.setObsidianFileOrganization(snapshot.obsidianFileOrganization)
        repo.setShowSessionsTrendChart(snapshot.showSessionsTrendChart)
    }
}
