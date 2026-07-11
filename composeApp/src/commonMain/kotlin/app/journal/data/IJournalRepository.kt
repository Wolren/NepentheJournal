package app.journal.data

import app.journal.ingest.IngestRepository
import app.journal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the journal data repository.
 *
 * All entity store operations follow the same pattern:
 * - upsert* — insert or update by ID
 * - get* — single entity lookup by ID (returns null if missing)
 * - delete* — remove by ID (no-op if missing)
 * - *ForSession / *ForSubstance — indexed children queries
 *
 * StateFlows emit fresh lists after every mutation.
 * Implementations must be thread-safe.
 */
interface IJournalRepository : IngestRepository {

    // ---- StateFlows (read-only snapshots after each mutation) ----
    val sessions: StateFlow<List<Session>>
    val doses: StateFlow<List<Dose>>
    val substances: StateFlow<List<Substance>>
    val notes: StateFlow<List<Note>>
    val timelineEvents: StateFlow<List<TimelineEvent>>
    val interactions: StateFlow<List<Interaction>>
    val effects: StateFlow<List<Effect>>
    val customUnits: StateFlow<List<CustomUnit>>

    val useShulginRating: StateFlow<Boolean>
    val useSubstanceColors: StateFlow<Boolean>
    val toleranceVersion: StateFlow<Int>
    val mutationCount: StateFlow<Long>

    // ---- Derived flows ----
    val recentSessions: Flow<List<Session>>
    val totalSessionCount: Flow<Int>
    val totalSubstanceCount: Flow<Int>
    val pendingConflictCount: Flow<Int>

    // ---- Bulk apply ----
    fun applySnapshot(snapshot: JournalSnapshot)

    // ---- Sessions ----
    fun upsertSession(session: Session)
    fun getSession(id: String): Session?
    fun deleteSession(id: String)

    // ---- Doses ----
    fun upsertDose(dose: Dose)
    fun dosesForSession(sessionId: String): List<Dose>
    fun deleteDose(id: String)

    // ---- Substances (implements IngestRepository.upsertSubstance) ----
    override fun upsertSubstance(substance: Substance)
    fun getSubstance(id: String): Substance?
    fun searchSubstances(query: String): List<Substance>
    fun deleteSubstance(id: String)

    // ---- Interactions ----
    override fun upsertInteraction(interaction: Interaction)

    // ---- Effects ----
    override fun upsertEffect(effect: Effect)
    fun getEffect(id: String): Effect?
    fun effectsForSubstance(substanceId: String): List<Effect>

    // ---- Custom Units ----
    fun upsertCustomUnit(unit: CustomUnit)
    fun deleteCustomUnit(id: String)
    fun customUnitsForSubstance(substanceId: String): List<CustomUnit>

    // ---- Preferences ----
    fun setShulginRating(enabled: Boolean)
    fun setSubstanceColors(enabled: Boolean)

    // ---- Notes ----
    fun upsertNote(note: Note)
    fun notesForSession(sessionId: String): List<Note>

    // ---- Timeline Events ----
    fun upsertTimelineEvent(event: TimelineEvent)
    fun eventsForSession(sessionId: String): List<TimelineEvent>

    // ---- Query indices ----
    fun sessionIdsOnDateRange(fromDate: String? = null, toDate: String? = null): List<String>
    fun sessionIdsForSubstance(substanceId: String): List<String>
    fun sessionIdsWithTag(tag: String): List<String>
    fun sessionIdsWithAnyTag(tags: List<String>): Set<String>
    fun rebuildIndices()

    // ---- DataFrame export ----
    fun sessionsDataFrame(): List<SessionDataRow>
    fun dosesDataFrame(): List<DoseDataRow>
    fun substancesDataFrame(): List<SubstanceDataRow>

    // ---- Bulk insert (batch ops) ----
    fun bulkInsert(
        sessions: List<Session> = emptyList(),
        doses: List<Dose> = emptyList(),
        timelineEvents: List<TimelineEvent> = emptyList(),
        interactions: List<Interaction> = emptyList()
    )

    // ---- Lifecycle ----
    fun autoSave(store: JournalStore, scope: CoroutineScope): Job
    fun clearAll()
}
