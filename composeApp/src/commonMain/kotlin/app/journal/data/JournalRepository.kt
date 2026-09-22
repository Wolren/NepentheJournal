/*
 * Nepenthe Journal - GPLv3
 * Copyright (C) 2026 Wolren
 *
 * Derived from PsychonautWiki Journal (GPL-3.0-or-later)
 * Copyright (C) 2022 Isaak Hanimann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.journal.data

import app.journal.log.Log
import app.journal.model.*
import app.journal.serde.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis

/**
 * Thread-safe in-memory journal repository.
 *
 * Backed by EntityStore instances (synchronized per entity type).
 * Child-entity indices are maintained incrementally on every mutation.
 * StateFlows emit fresh lists after each mutation for Compose reactivity.
 */
class JournalRepository internal constructor() : IJournalRepository {

    // ---- Self-contained EntityStore instances (each owns its StateFlow) ----
    override val sessions: StateFlow<List<Session>>
        get() = sessionsStore.flow
    override val doses: StateFlow<List<Dose>>
        get() = dosesStore.flow
    override val substances: StateFlow<List<Substance>>
        get() = substancesStore.flow
    override val notes: StateFlow<List<Note>>
        get() = notesStore.flow
    override val timelineEvents: StateFlow<List<TimelineEvent>>
        get() = timelineEventsStore.flow
    override val interactions: StateFlow<List<Interaction>>
        get() = interactionsStore.flow
    override val effects: StateFlow<List<Effect>>
        get() = effectsStore.flow
    override val customUnits: StateFlow<List<CustomUnit>>
        get() = customUnitsStore.flow
    override val persons: StateFlow<List<Person>>
        get() = personsStore.flow

    private val sessionsStore = EntityStore(Session::id)
    private val dosesStore = EntityStore(Dose::id)
    private val substancesStore = EntityStore(Substance::id)
    private val notesStore = EntityStore(Note::id)
    private val timelineEventsStore = EntityStore(TimelineEvent::id)
    private val interactionsStore = EntityStore(Interaction::id)
    private val effectsStore = EntityStore(Effect::id)
    private val customUnitsStore = EntityStore(CustomUnit::id)
    private val personsStore = EntityStore(Person::id)

    // ---- Version counter for tolerance recalculation ----
    private val _toleranceVersion = MutableStateFlow(0)
    override val toleranceVersion: StateFlow<Int> = _toleranceVersion.asStateFlow()
    private fun bumpToleranceVersion() { _toleranceVersion.update { it + 1 } }

    // ---- Mutation counter for auto-save debounce ----
    private val _mutationCount = MutableStateFlow(0L)
    override val mutationCount: StateFlow<Long> = _mutationCount.asStateFlow()
    private fun bumpMutationCount() { _mutationCount.update { it + 1 } }

    // ---- Derived flows ----
    override val recentSessions: Flow<List<Session>> =
        sessions.map { it.sortedByDescending { s -> s.startTime }.take(5) }

    override val totalSessionCount: Flow<Int> = sessions.map { it.size }
    override val totalSubstanceCount: Flow<Int> = substances.map { it.size }

    private val lock = PlatformLock()

    // ---- Focused components: same lock, shared store instances (wave2 split) ----
    private val searchEngine = JournalSearch(
        lock, sessionsStore, substancesStore, notesStore, dosesStore, timelineEventsStore, effectsStore,
    )
    private val prefs = JournalPreferences(lock, bumpMutationCount = { bumpMutationCount() })
    private val indices = JournalIndices(
        lock, sessionsStore, dosesStore, notesStore, timelineEventsStore, effectsStore, customUnitsStore,
        doses = dosesStore.flow, substances = substancesStore.flow,
        rebuildSearchIndexLocked = { searchEngine.rebuildSearchIndexLocked() },
    )
    private val tombstones = JournalTombstones(
        lock, sessionsStore, dosesStore, notesStore, substancesStore, effectsStore, interactionsStore,
        timelineEventsStore, customUnitsStore, indices,
        bumpToleranceVersion = { bumpToleranceVersion() }, bumpMutationCount = { bumpMutationCount() },
    )
    private val mutations = JournalMutations(
        lock, sessionsStore, dosesStore, substancesStore, notesStore, timelineEventsStore, interactionsStore,
        effectsStore, customUnitsStore, personsStore, indices,
        markSearchIndexDirtyLocked = { searchEngine.markSearchIndexDirtyLocked() },
        bumpToleranceVersion = { bumpToleranceVersion() }, bumpMutationCount = { bumpMutationCount() },
    )
    private val frames = JournalExportFrames(lock, sessionsStore, dosesStore, substancesStore, indices)
    private val syncBridge = JournalSyncBridge(
        lock, sessionsStore, dosesStore, substancesStore, notesStore, timelineEventsStore, interactionsStore,
        effectsStore, customUnitsStore, personsStore, indices, tombstones,
        markSearchIndexDirtyLocked = { searchEngine.markSearchIndexDirtyLocked() },
        bumpToleranceVersion = { bumpToleranceVersion() }, bumpMutationCount = { bumpMutationCount() },
    )

    // ---- Preferences (state lives in JournalPreferences) ----
    override val ratingScaleMode: StateFlow<RatingScaleMode> = prefs.ratingScaleMode
    override val useSubstanceColors: StateFlow<Boolean> = prefs.useSubstanceColors
    override val welcomeCompleted: StateFlow<Boolean> = prefs.welcomeCompleted
    override val seedFingerprint: StateFlow<String?> = prefs.seedFingerprint
    override val obsidianVaultPath: StateFlow<String> = prefs.obsidianVaultPath
    override val obsidianAutoExport: StateFlow<Boolean> = prefs.obsidianAutoExport
    override val obsidianSubfolder: StateFlow<String> = prefs.obsidianSubfolder
    override val obsidianFileOrganization: StateFlow<String> = prefs.obsidianFileOrganization
    override val showSessionsTrendChart: StateFlow<Boolean> = prefs.showSessionsTrendChart

    // ---- Derived flows and indices (delegated) ----
    override val pendingConflictCount: Flow<Int> = syncBridge.pendingConflictCount
    override fun dosesForSubstance(substanceId: String): Flow<List<Dose>> = indices.dosesForSubstance(substanceId)
    override val substancesById: Flow<Map<String, Substance>> = indices.substancesById
    override val substanceNamesById: Flow<Map<String, String>> = indices.substanceNamesById
    override val substanceDoseStats: Map<String, Pair<Int, Long>> get() = indices.substanceDoseStats
    val searchIndex: SearchIndex get() = searchEngine.searchIndex

    // ========================
    //  Bulk apply (seed load)
    // ========================

    override fun applyBatch(
        sessions: List<Session>,
        doses: List<Dose>,
        substances: List<Substance>,
        effects: List<Effect>,
        interactions: List<Interaction>,
        notes: List<Note>,
        timelineEvents: List<TimelineEvent>,
        customUnits: List<CustomUnit>,
        persons: List<Person>,
        lastWriterWins: Boolean,
        deletedSessionIds: List<String>,
        deletedDoseIds: List<String>,
        deletedNoteIds: List<String>,
        deletedSubstanceIds: List<String>,
        deletedEffectIds: List<String>,
        deletedInteractionIds: List<String>,
        deletedTimelineEventIds: List<String>,
        deletedCustomUnitIds: List<String>,
        tombstoneCutoff: Long
    ) = syncBridge.applyBatch(
        sessions, doses, substances, effects, interactions, notes, timelineEvents,
        customUnits, persons, lastWriterWins, deletedSessionIds, deletedDoseIds,
        deletedNoteIds, deletedSubstanceIds, deletedEffectIds, deletedInteractionIds,
        deletedTimelineEventIds, deletedCustomUnitIds, tombstoneCutoff
    )

    override fun fullSnapshot(): JournalSnapshot = lock.withLock {
        AppJson.snapshot(this)
    }

    override fun applySnapshot(snapshot: JournalSnapshot) = syncBridge.applySnapshot(snapshot)

    // ========================
    //  Individuals (device-local, never synced, no tombstones)
    // ========================

    override fun upsertPerson(person: Person) = mutations.upsertPerson(person)

    override fun getPerson(id: String): Person? = lock.withLock { personsStore.get(id) }

    override fun deletePerson(id: String) = mutations.deletePerson(id)

    // ========================
    //  Sessions
    // ========================

    override fun upsertSession(session: Session) = mutations.upsertSession(session)

    override fun getSession(id: String): Session? = lock.withLock { sessionsStore.get(id) }

    override fun toggleFavorite(sessionId: String) = mutations.toggleFavorite(sessionId)

    override fun deleteSession(id: String) = lock.withLock { tombstones.deleteSessionLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    // ========================
    //  Doses
    // ========================

    override fun upsertDose(dose: Dose) = mutations.upsertDose(dose)

    override fun dosesForSession(sessionId: String): List<Dose> = indices.dosesForSession(sessionId)

    override fun deleteDose(id: String) = lock.withLock { tombstones.deleteDoseLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    override fun deleteNote(id: String) = lock.withLock { tombstones.deleteNoteLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    override fun deleteTimelineEvent(id: String) = lock.withLock { tombstones.deleteTimelineEventLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    // ========================
    //  Substances
    // ========================

    override fun upsertSubstance(substance: Substance) = mutations.upsertSubstance(substance)

    override fun getSubstance(id: String): Substance? = lock.withLock { substancesStore.get(id) }

    override fun deleteSubstance(id: String) = lock.withLock { tombstones.deleteSubstanceLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    // ========================
    //  Interactions
    // ========================

    override fun upsertInteraction(interaction: Interaction) = mutations.upsertInteraction(interaction)

    override fun getInteraction(id: String): Interaction? = lock.withLock { interactionsStore.get(id) }

    // ========================
    //  Effects
    // ========================

    override fun upsertEffect(effect: Effect) = mutations.upsertEffect(effect)

    override fun getEffect(id: String): Effect? = lock.withLock { effectsStore.get(id) }

    override fun effectsForSubstance(substanceId: String): List<Effect> = indices.effectsForSubstance(substanceId)

    // ========================
    //  Custom Units
    // ========================

    override fun upsertCustomUnit(unit: CustomUnit) = mutations.upsertCustomUnit(unit)

    override fun deleteCustomUnit(id: String) = lock.withLock { tombstones.deleteCustomUnitLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    override fun customUnitsForSubstance(substanceId: String): List<CustomUnit> = indices.customUnitsForSubstance(substanceId)

    override fun deleteEffect(id: String) = lock.withLock { tombstones.deleteEffectLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    override fun deleteInteraction(id: String) = lock.withLock { tombstones.deleteInteractionLocked(id); searchEngine.markSearchIndexDirtyLocked() }

    // ========================
    //  Preferences
    // ========================

    override fun setRatingScaleMode(mode: RatingScaleMode) = prefs.setRatingScaleMode(mode)
    override fun setSubstanceColors(enabled: Boolean) = prefs.setSubstanceColors(enabled)
    override fun setWelcomeCompleted(completed: Boolean) = prefs.setWelcomeCompleted(completed)
    override fun setSeedFingerprint(fingerprint: String?) = prefs.setSeedFingerprint(fingerprint)
    override fun setObsidianVaultPath(path: String) = prefs.setObsidianVaultPath(path)
    override fun setObsidianAutoExport(enabled: Boolean) = prefs.setObsidianAutoExport(enabled)
    override fun setObsidianSubfolder(folder: String) = prefs.setObsidianSubfolder(folder)
    override fun setObsidianFileOrganization(org: String) = prefs.setObsidianFileOrganization(org)
    override fun setShowSessionsTrendChart(enabled: Boolean) = prefs.setShowSessionsTrendChart(enabled)

    // ========================
    //  Notes
    // ========================

    override fun upsertNote(note: Note) = mutations.upsertNote(note)
    override fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note? = syncBridge.upsertNoteWithConflict(note, remoteDeviceId)

    override fun notesForSession(sessionId: String): List<Note> = indices.notesForSession(sessionId)

    // ========================
    //  Timeline Events
    // ========================

    override fun upsertTimelineEvent(event: TimelineEvent) = mutations.upsertTimelineEvent(event)

    override fun eventsForSession(sessionId: String): List<TimelineEvent> = indices.eventsForSession(sessionId)

    override fun upsertSessionChildren(
        sessionId: String,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>
    ) = mutations.upsertSessionChildren(sessionId, doses, timelineEvents)

    // ========================
    //  Query indices (public)
    // ========================

    override fun sessionIdsOnDateRange(fromDate: String?, toDate: String?): List<String> = indices.sessionIdsOnDateRange(fromDate, toDate)
    override fun sessionIdsForSubstance(substanceId: String): List<String> = indices.sessionIdsForSubstance(substanceId)
    override fun sessionIdsForSubstances(substanceIds: Set<String>): Set<String> = indices.sessionIdsForSubstances(substanceIds)
    override fun rebuildIndices() = indices.rebuildIndices()

    // ========================
    //  DataFrame export
    // ========================
    override fun sessionsDataFrame(): List<SessionDataRow> = frames.sessionsDataFrame()
    override fun dosesDataFrame(): List<DoseDataRow> = frames.dosesDataFrame()
    override fun substancesDataFrame(): List<SubstanceDataRow> = frames.substancesDataFrame()
    override fun exportSessionBundles(): List<SessionBundle> = frames.exportSessionBundles()

    // ========================
    //  Auto-save
    // ========================

    override fun autoSave(scope: CoroutineScope, save: () -> Unit): Job {
        return scope.launch {
            mutationCount
                .drop(1)
                .debounce(2000)
                .collect {
                    try {
                        // Same quiet-period tail as the save: mutations only mark the
                        // search index dirty, so one idle rebuild covers a whole burst
                        // of edits instead of a rebuild per keystroke afterward.
                        searchEngine.rebuildSearchIndexIfDirty()
                        save()
                        Log.withTag("Repo").v { "Auto-saved (mutation #$it)" }
                    } catch (e: Exception) {
                        Log.withTag("Repo").e(e) { "Auto-save failed" }
                    }
                }
        }
    }

    // ========================
    //  Bulk insert (batch ops)
    // ========================

    /**
     * Bulk-insert entities with a single sync pass instead of one
     * StateFlow emission per entity. Use for test data generation and
     * large imports where per-item emit overhead matters.
     */
    override fun bulkInsert(
        sessions: List<Session>,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>,
        interactions: List<Interaction>
    ) = applyBatch(
        sessions = sessions,
        doses = doses,
        timelineEvents = timelineEvents,
        interactions = interactions
    )

    // ========================
    //  Clear
    // ========================

    override fun clearAll() = lock.withLock {
        Log.withTag("Repo").w { "clearAll: wiping all journal data" }
        sessionsStore.clear()
        substancesStore.clear()
        dosesStore.clear()
        notesStore.clear()
        timelineEventsStore.clear()
        interactionsStore.clear()
        effectsStore.clear()
        customUnitsStore.clear()
        personsStore.clear()
        indices.clearAllLocked()
        tombstones.clear()
        searchEngine.rebuildSearchIndexLocked()
        prefs.resetForClearAll()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Full-text search
    // ========================

    override fun search(query: String): List<SearchResult> = searchEngine.search(query)
    override fun rebuildSearchIndex() = searchEngine.rebuildSearchIndex()
    override fun deletedIdsSince(since: Long): DeletedIds = tombstones.deletedIdsSince(since)
    override fun exportTombstones(): Map<String, Long> = tombstones.exportTombstones()
    override fun importTombstones(tombstones: Map<String, Long>) = this.tombstones.importTombstones(tombstones)

    companion object {
        val instance: JournalRepository by lazy { JournalRepository() }
    }
}
