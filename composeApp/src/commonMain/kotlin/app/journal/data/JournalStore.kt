package app.journal.data

import app.journal.model.*
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
    val version: Int = 4,
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
    val useSubstanceColors: Boolean = true
)

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
 * Serialization helper shared across persistence and export.
 */
object JournalJson {
    val json = Json {
        prettyPrint = false
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
            useShulginRating = repo.useShulginRating.value
        )
    }

    fun apply(repo: JournalRepository, snapshot: JournalSnapshot) {
        snapshot.sessions.forEach { repo.upsertSession(it) }
        snapshot.substances.forEach { repo.upsertSubstance(it) }
        snapshot.doses.forEach { repo.upsertDose(it) }
        snapshot.notes.forEach { repo.upsertNote(it) }
        snapshot.timelineEvents.forEach { repo.upsertTimelineEvent(it) }
        snapshot.interactions.forEach { repo.upsertInteraction(it) }
        snapshot.effects.forEach { repo.upsertEffect(it) }
        snapshot.customUnits.forEach { repo.upsertCustomUnit(it) }
        repo.setShulginRating(snapshot.useShulginRating)
        repo.setSubstanceColors(snapshot.useSubstanceColors)
    }
}
