package app.journal.data

import app.journal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Public interface for the journal data repository.
 *
 * All entity store operations follow the same pattern:
 * - upsert* - insert or update by ID
 * - get* - single entity lookup by ID (returns null if missing)
 * - delete* - remove by ID (no-op if missing)
 * - *ForSession / *ForSubstance - indexed children queries
 *
 * StateFlows emit fresh lists after every mutation.
 * Implementations must be thread-safe.
 */
interface IJournalRepository {

    // ---- StateFlows (read-only snapshots after each mutation) ----
    val sessions: StateFlow<List<Session>>
    val doses: StateFlow<List<Dose>>
    val substances: StateFlow<List<Substance>>
    val notes: StateFlow<List<Note>>
    val timelineEvents: StateFlow<List<TimelineEvent>>
    val interactions: StateFlow<List<Interaction>>
    val effects: StateFlow<List<Effect>>
    val customUnits: StateFlow<List<CustomUnit>>

    val ratingScaleMode: StateFlow<RatingScaleMode>
    val useSubstanceColors: StateFlow<Boolean>
    val welcomeCompleted: StateFlow<Boolean>
    /**
     * Fingerprint of the bundled seed + DoseWiki resources the substance
     * library was last ingested from. When it matches the bundled resources
     * at startup, the expensive re-parse and re-ingest is skipped and the
     * persisted library is used as is.
     */
    val seedFingerprint: StateFlow<String?>
    val toleranceVersion: StateFlow<Int>
    val mutationCount: StateFlow<Long>

    // ---- Obsidian vault config ----
    val obsidianVaultPath: StateFlow<String>
    val obsidianAutoExport: StateFlow<Boolean>
    val obsidianSubfolder: StateFlow<String>
    val obsidianFileOrganization: StateFlow<String>

    // ---- Display preferences ----
    val showSessionsTrendChart: StateFlow<Boolean>

    fun setObsidianVaultPath(path: String)
    fun setObsidianAutoExport(enabled: Boolean)
    fun setObsidianSubfolder(folder: String)
    fun setObsidianFileOrganization(org: String)

    fun setShowSessionsTrendChart(enabled: Boolean)

    // ---- Derived flows ----
    val recentSessions: Flow<List<Session>>
    val totalSessionCount: Flow<Int>
    val totalSubstanceCount: Flow<Int>
    val pendingConflictCount: Flow<Int>

    /**
     * Live id -> Substance map for O(1) lookups without taking the repo lock
     * per row. Emits only when the map content changes.
     */
    val substancesById: Flow<Map<String, Substance>>

    /**
     * Live id -> display name map (the cheap shape dashboards remember).
     * Emits only when a name is added, removed or renamed.
     */
    val substanceNamesById: Flow<Map<String, String>>

    /**
     * Doses of [substanceId] as a flow, deduplicated: mutations that do not
     * change this substance's dose list (for example edits to other
     * substances) do not re-emit.
     */
    fun dosesForSubstance(substanceId: String): Flow<List<Dose>>

    // ---- Bulk apply ----
    fun fullSnapshot(): JournalSnapshot
    fun applySnapshot(snapshot: JournalSnapshot)

    // ---- Sessions ----
    fun upsertSession(session: Session)
    fun getSession(id: String): Session?
    /** Flips the favorite flag, no-op for unknown ids. */
    fun toggleFavorite(sessionId: String)
    fun deleteSession(id: String)

    // ---- Individuals (device-local, never synced) ----
    val persons: StateFlow<List<Person>>
    fun upsertPerson(person: Person)
    fun getPerson(id: String): Person?
    fun deletePerson(id: String)

    // ---- Doses ----
    fun upsertDose(dose: Dose)
    fun dosesForSession(sessionId: String): List<Dose>
    fun deleteDose(id: String)

    /**
     * Batch upsert of one session's doses and timeline events: one putAll per
     * store, one index rebuild, one tolerance bump (only when doses changed)
     * and one mutation bump. Children whose sessionId differs from
     * [sessionId] (draft-id children of a not-yet-saved session) are
     * re-parented here, so callers may pass their lists as-is. Final state is
     * equivalent to looping [upsertDose] / [upsertTimelineEvent].
     */
    fun upsertSessionChildren(
        sessionId: String,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>
    )

    // ---- Substances ----
    fun upsertSubstance(substance: Substance)
    fun getSubstance(id: String): Substance?
    fun deleteSubstance(id: String)

    // ---- Interactions ----
    fun upsertInteraction(interaction: Interaction)
    fun getInteraction(id: String): Interaction?
    fun deleteInteraction(id: String)

    // ---- Effects ----
    fun upsertEffect(effect: Effect)
    fun getEffect(id: String): Effect?
    fun effectsForSubstance(substanceId: String): List<Effect>
    fun deleteEffect(id: String)

    // ---- Custom Units ----
    fun upsertCustomUnit(unit: CustomUnit)
    fun deleteCustomUnit(id: String)
    fun customUnitsForSubstance(substanceId: String): List<CustomUnit>

    // ---- Preferences ----
    fun setRatingScaleMode(mode: RatingScaleMode)
    fun setSubstanceColors(enabled: Boolean)
    fun setWelcomeCompleted(completed: Boolean)
    fun setSeedFingerprint(fingerprint: String?)

    // ---- Notes ----
    fun upsertNote(note: Note)
    fun deleteNote(id: String)
    fun notesForSession(sessionId: String): List<Note>

    /**
     * Atomically upsert a note through the shared conflict merge: when the
     * stored body differs, the note with the higher updatedAt wins (tie:
     * incoming) and the LOSING body is preserved in full as a ConflictSibling
     * (never destroyed). Runs under the repository lock to prevent TOCTOU
     * races between read and write. Every other note apply path
     * (applyBatch on sync paths) routes through the same merge.
     * @return the resolved note (with conflict siblings if applicable), or null if skipped
     */
    fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note?

    // ---- Timeline Events ----
    fun upsertTimelineEvent(event: TimelineEvent)
    fun deleteTimelineEvent(id: String)
    fun eventsForSession(sessionId: String): List<TimelineEvent>

    // ---- Query indices ----
    fun sessionIdsOnDateRange(fromDate: String? = null, toDate: String? = null): List<String>
    fun sessionIdsForSubstance(substanceId: String): List<String>

    /**
     * Batch version of [sessionIdsForSubstance] - returns the union of session IDs
     * that contain doses of any of the given substance IDs, using a single lock acquire.
     */
    fun sessionIdsForSubstances(substanceIds: Set<String>): Set<String>
    fun rebuildIndices()

    /**
     * Precomputed dose stats per substance: (distinctSessionCount, lastUsedTimestamp).
     * Maintained incrementally. Returns empty map if no doses.
     */
    val substanceDoseStats: Map<String, Pair<Int, Long>>

    // ---- DataFrame export ----
    fun sessionsDataFrame(): List<SessionDataRow>
    fun dosesDataFrame(): List<DoseDataRow>
    fun substancesDataFrame(): List<SubstanceDataRow>

    /**
     * Returns a consistent snapshot of all sessions with their doses.
     * Reads everything under the repo lock so the data is self-consistent.
     */
    fun exportSessionBundles(): List<SessionBundle>

    // ---- Bulk insert (batch ops) ----
    fun bulkInsert(
        sessions: List<Session> = emptyList(),
        doses: List<Dose> = emptyList(),
        timelineEvents: List<TimelineEvent> = emptyList(),
        interactions: List<Interaction> = emptyList()
    )

    // ---- Bulk apply (sync deltas, seed load) ----
    /**
     * Bulk-apply entities. Snapshot loads pass [persons]; sync deltas leave the
     * default empty so device-local persons are never overwritten by peers.
     *
     * Notes on a last-writer-wins (sync) apply do NOT blind-overwrite: they
     * route through the same conflict merge as [upsertNoteWithConflict], so a
     * divergent peer edit keeps both bodies. Seed/restore applies
     * (lastWriterWins = false) stay authoritative blind writes. Dose or
     * substance puts bump toleranceVersion exactly once for the batch.
     */
    fun applyBatch(
        sessions: List<Session> = emptyList(),
        doses: List<Dose> = emptyList(),
        substances: List<Substance> = emptyList(),
        effects: List<Effect> = emptyList(),
        interactions: List<Interaction> = emptyList(),
        notes: List<Note> = emptyList(),
        timelineEvents: List<TimelineEvent> = emptyList(),
        customUnits: List<CustomUnit> = emptyList(),
        persons: List<Person> = emptyList(),
        lastWriterWins: Boolean = false,
        deletedSessionIds: List<String> = emptyList(),
        deletedDoseIds: List<String> = emptyList(),
        deletedNoteIds: List<String> = emptyList(),
        deletedSubstanceIds: List<String> = emptyList(),
        deletedEffectIds: List<String> = emptyList(),
        deletedInteractionIds: List<String> = emptyList(),
        deletedTimelineEventIds: List<String> = emptyList(),
        deletedCustomUnitIds: List<String> = emptyList(),
        tombstoneCutoff: Long = 0L
    )

    // ---- Full-text search ----
    fun search(query: String): List<SearchResult>
    fun rebuildSearchIndex()
    /** Deleted IDs newer than [since], grouped by entity type. */
    fun deletedIdsSince(since: Long): DeletedIds
    /** All live tombstones as storage keys to deletion timestamps. */
    fun exportTombstones(): Map<String, Long>
    /** Replace the tombstone journal (snapshot restore). */
    fun importTombstones(tombstones: Map<String, Long>)

    // ---- Lifecycle ----
    /**
     * Debounced auto-save loop: after a quiet period following each mutation,
     * invoke [save] (the caller owns persistence, so this interface never
     * references the JournalStore class). The same quiet-period tail also
     * refreshes the full-text search index when mutations dirtied it.
     */
    fun autoSave(scope: CoroutineScope, save: () -> Unit): Job
    fun clearAll()
}
