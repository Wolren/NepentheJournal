/*
 * Nepenthe Journal — GPLv3
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

import app.journal.ingest.IngestRepository
import app.journal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Data classes for R/Pandas-friendly tabular export
data class SessionDataRow(
    val id: String,
    val title: String,
    val date: String,
    val startTime: String,
    val endTime: String?,
    val durationHours: Double?,
    val tags: String,
    val set: String?,
    val setting: String?,
    val intention: String?,
    val outcome: String?,
    val rating: Int?,
    val shulginRating: String?,
    val consumerName: String?,
    val isFavorite: Boolean,
    val isArchived: Boolean,
    val substanceNames: String,
    val doseCount: Int
)

data class DoseDataRow(
    val id: String,
    val sessionId: String,
    val substanceId: String,
    val substanceName: String,
    val route: String,
    val amount: Double,
    val unit: String,
    val timestamp: Long,
    val redosing: Boolean,
    val isEstimate: Boolean,
    val notes: String?
)

data class SubstanceDataRow(
    val id: String,
    val name: String,
    val aliases: String,
    val substanceClass: String,
    val cid: Long?,
    val molecularFormula: String?,
    val molecularWeight: String?,
    val iupacName: String?,
    val logP: Double?,
    val routes: String,
    val effects: String,
    val toxicity: String,
    val addictionPotential: String?
)

/**
 * In-memory journal repository with map-backed storage for O(1) lookups,
 * precomputed query indices (4.3), mutation counter for debounced auto-save (4.1),
 * and DataFrame export (2.4).
 */
class JournalRepository internal constructor() : IngestRepository {

    // ---- Backing maps for O(1) point lookups ----
    private val _sessionsMap = mutableMapOf<String, Session>()
    private val _dosesMap = mutableMapOf<String, Dose>()
    private val _substancesMap = mutableMapOf<String, Substance>()
    private val _notesMap = mutableMapOf<String, Note>()
    private val _timelineEventsMap = mutableMapOf<String, TimelineEvent>()
    private val _interactionsMap = mutableMapOf<String, Interaction>()
    private val _effectsMap = mutableMapOf<String, Effect>()
    private val _customUnitsMap = mutableMapOf<String, CustomUnit>()

    // ---- Session-child indexes (incrementally updated) ----
    private val _dosesBySession = mutableMapOf<String, MutableList<Dose>>()
    private val _notesBySession = mutableMapOf<String, MutableList<Note>>()
    private val _eventsBySession = mutableMapOf<String, MutableList<TimelineEvent>>()

    // ---- Precomputed query indices (4.3) ----
    /** session.startTime date -> list of session IDs */
    private val _sessionsByDate = mutableMapOf<LocalDate, MutableList<String>>()
    /** substanceId -> set of session IDs that include this substance */
    private val _sessionsPerSubstance = mutableMapOf<String, MutableSet<String>>()
    /** tag -> set of session IDs with this tag */
    private val _sessionsByTag = mutableMapOf<String, MutableSet<String>>()

    // ---- Public StateFlows (rebuilt from maps on write) ----
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _doses = MutableStateFlow<List<Dose>>(emptyList())
    val doses: StateFlow<List<Dose>> = _doses.asStateFlow()

    private val _substances = MutableStateFlow<List<Substance>>(emptyList())
    val substances: StateFlow<List<Substance>> = _substances.asStateFlow()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _timelineEvents = MutableStateFlow<List<TimelineEvent>>(emptyList())
    val timelineEvents: StateFlow<List<TimelineEvent>> = _timelineEvents.asStateFlow()

    private val _interactions = MutableStateFlow<List<Interaction>>(emptyList())
    val interactions: StateFlow<List<Interaction>> = _interactions.asStateFlow()

    private val _effects = MutableStateFlow<List<Effect>>(emptyList())
    val effects: StateFlow<List<Effect>> = _effects.asStateFlow()

    private val _customUnits = MutableStateFlow<List<CustomUnit>>(emptyList())
    val customUnits: StateFlow<List<CustomUnit>> = _customUnits.asStateFlow()

    private val _useShulginRating = MutableStateFlow(false)
    val useShulginRating: StateFlow<Boolean> = _useShulginRating.asStateFlow()

    private val _useSubstanceColors = MutableStateFlow(true)
    val useSubstanceColors: StateFlow<Boolean> = _useSubstanceColors.asStateFlow()

    // Version counter for tolerance recalculation
    private val _toleranceVersion = MutableStateFlow(0)
    val toleranceVersion: StateFlow<Int> = _toleranceVersion.asStateFlow()
    private fun bumpToleranceVersion() { _toleranceVersion.update { it + 1 } }

    // ---- Sync from Snapshot (bulk) ----

    /**
     * Bulk-apply a JournalSnapshot directly into backing maps,
     * performing a single sync pass at the end instead of one
     * per entity. Use for seed loading and migration — avoids
     * O(n) StateFlow emissions for each of hundreds of entities.
     */
    fun applySnapshot(snapshot: JournalSnapshot) {
        snapshot.sessions.forEach { _sessionsMap[it.id] = it }
        snapshot.substances.forEach { _substancesMap[it.id] = it }
        snapshot.doses.forEach { _dosesMap[it.id] = it }
        snapshot.notes.forEach { _notesMap[it.id] = it }
        snapshot.timelineEvents.forEach { _timelineEventsMap[it.id] = it }
        snapshot.interactions.forEach { _interactionsMap[it.id] = it }
        snapshot.effects.forEach { _effectsMap[it.id] = it }
        snapshot.customUnits.forEach { _customUnitsMap[it.id] = it }

        // Single sync pass after all maps are populated
        syncSessions()
        syncSubstances()
        syncDoses()
        syncNotes()
        syncTimelineEvents()
        syncInteractions()
        syncEffects()
        syncCustomUnits()

        // Rebuild all indices from scratch
        rebuildAllIndices()

        // Apply preferences
        setShulginRating(snapshot.useShulginRating)
        setSubstanceColors(snapshot.useSubstanceColors)
    }

    // ---- Mutation counter for auto-save debounce (4.1) ----
    private val _mutationCount = MutableStateFlow(0L)
    val mutationCount: StateFlow<Long> = _mutationCount.asStateFlow()
    private fun bumpMutationCount() { _mutationCount.update { it + 1 } }

    // ---- Derived flows ----
    val recentSessions: Flow<List<Session>> = sessions.map { list ->
        list.sortedByDescending { it.startTime }.take(5)
    }

    val totalSessionCount: Flow<Int> = sessions.map { it.size }
    val totalSubstanceCount: Flow<Int> = substances.map { it.size }
    val pendingConflictCount: Flow<Int> = notes.map { list ->
        list.count { it.conflictSiblings.isNotEmpty() }
    }

    // ---- Helpers to sync flows from maps ----
    private fun syncSessions() { _sessions.value = _sessionsMap.values.toList() }
    private fun syncSubstances() { _substances.value = _substancesMap.values.toList() }
    private fun syncDoses() { _doses.value = _dosesMap.values.toList() }
    private fun syncNotes() { _notes.value = _notesMap.values.toList() }
    private fun syncTimelineEvents() { _timelineEvents.value = _timelineEventsMap.values.toList() }
    private fun syncInteractions() { _interactions.value = _interactionsMap.values.toList() }
    private fun syncEffects() { _effects.value = _effectsMap.values.toList() }
    private fun syncCustomUnits() { _customUnits.value = _customUnitsMap.values.toList() }

    // ---- Query index helpers ----
    private fun sessionDate(session: Session): LocalDate =
        Instant.fromEpochMilliseconds(session.startTime)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun addSessionToIndices(session: Session) {
        val date = sessionDate(session)
        _sessionsByDate.getOrPut(date) { mutableListOf() }.add(session.id)
        for (tag in session.tags) {
            _sessionsByTag.getOrPut(tag) { mutableSetOf() }.add(session.id)
        }
    }

    private fun removeSessionFromIndices(session: Session) {
        val date = sessionDate(session)
        _sessionsByDate[date]?.remove(session.id)
        if (_sessionsByDate[date]?.isEmpty() == true) _sessionsByDate.remove(date)
        for (tag in session.tags) {
            _sessionsByTag[tag]?.remove(session.id)
            if (_sessionsByTag[tag]?.isEmpty() == true) _sessionsByTag.remove(tag)
        }
    }

    private fun rebuildAllIndices() {
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _sessionsByTag.clear()
        for (session in _sessionsMap.values) {
            addSessionToIndices(session)
        }
        for (dose in _dosesMap.values) {
            _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)
        }
    }

    // --- Sessions ---

    fun upsertSession(session: Session) {
        val oldSession = _sessionsMap.put(session.id, session)
        if (oldSession != null && oldSession !== session) {
            removeSessionFromIndices(oldSession)
        }
        addSessionToIndices(session)
        syncSessions()
        bumpMutationCount()
    }

    fun getSession(id: String): Session? =
        _sessionsMap[id]

    fun deleteSession(id: String) {
        val session = _sessionsMap[id] ?: return
        removeSessionFromIndices(session)
        _sessionsMap.remove(id)

        // Remove child entities in bulk
        var dosesChanged = false
        var notesChanged = false
        var eventsChanged = false
        val dosesBefore = _dosesMap.size
        val affectedSubstances = _dosesMap.values.filter { it.sessionId == id }
            .map { it.substanceId }.toSet()
        _dosesMap.values.removeAll { it.sessionId == id }
        if (_dosesMap.size != dosesBefore) {
            rebuildDoseIndex()
            dosesChanged = true
        }

        val notesBefore = _notesMap.size
        _notesMap.values.removeAll { it.sessionId == id }
        if (_notesMap.size != notesBefore) {
            _notesBySession.remove(id)
            notesChanged = true
        }

        val eventsBefore = _timelineEventsMap.size
        _timelineEventsMap.values.removeAll { it.sessionId == id }
        if (_timelineEventsMap.size != eventsBefore) {
            _eventsBySession.remove(id)
            eventsChanged = true
        }

        _dosesBySession.remove(id)
        _notesBySession.remove(id)
        _eventsBySession.remove(id)

        // Clean substance index
        for (subId in affectedSubstances) {
            _sessionsPerSubstance[subId]?.remove(id)
            if (_sessionsPerSubstance[subId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(subId)
        }

        syncSessions()
        if (dosesChanged) syncDoses()
        if (notesChanged) syncNotes()
        if (eventsChanged) syncTimelineEvents()
        bumpMutationCount()
    }

    // --- Doses ---

    fun upsertDose(dose: Dose) {
        val prev = _dosesMap.put(dose.id, dose)
        val prevSessionId = prev?.sessionId
        val prevSubstanceId = prev?.substanceId

        // Update session-child index
        if (prevSessionId != null && prevSessionId != dose.sessionId) {
            _dosesBySession[prevSessionId]?.removeAll { it.id == dose.id }
        }
        _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)

        // Update substance-to-session index
        if (prevSubstanceId != null && prevSubstanceId != dose.substanceId) {
            _sessionsPerSubstance[prevSubstanceId]?.remove(dose.sessionId)
            if (_sessionsPerSubstance[prevSubstanceId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(prevSubstanceId)
        }
        _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)

        syncDoses()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    fun dosesForSession(sessionId: String): List<Dose> =
        _dosesBySession[sessionId] ?: emptyList()

    fun deleteDose(id: String) {
        val removed = _dosesMap.remove(id) ?: return
        _dosesBySession[removed.sessionId]?.removeAll { it.id == id }
        _sessionsPerSubstance[removed.substanceId]?.remove(removed.sessionId)
        if (_sessionsPerSubstance[removed.substanceId]?.isEmpty() == true)
            _sessionsPerSubstance.remove(removed.substanceId)
        syncDoses()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // --- Substances ---

    override fun upsertSubstance(substance: Substance) {
        _substancesMap[substance.id] = substance
        syncSubstances()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    fun getSubstance(id: String): Substance? =
        _substancesMap[id]

    fun searchSubstances(query: String): List<Substance> {
        val q = query.lowercase()
        return _substancesMap.values.filter {
            it.name.lowercase().contains(q) ||
            it.aliases.any { a -> a.lowercase().contains(q) }
        }
    }

    fun deleteSubstance(id: String) {
        _substancesMap.remove(id)
        // Remove associated doses and update session-child index
        val affectedSessionIds = _dosesMap.values
            .filter { it.substanceId == id }
            .map { it.sessionId }
            .toSet()
        _dosesMap.values.removeAll { it.substanceId == id }
        for (sessionId in affectedSessionIds) {
            _dosesBySession[sessionId]?.removeAll { it.substanceId == id }
        }
        syncSubstances()
        _sessionsPerSubstance.remove(id)
        if (affectedSessionIds.isNotEmpty()) syncDoses()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // --- Interactions ---

    override fun upsertInteraction(interaction: Interaction) {
        _interactionsMap[interaction.id] = interaction
        syncInteractions()
        bumpMutationCount()
    }

    // --- Effects ---

    override fun upsertEffect(effect: Effect) {
        _effectsMap[effect.id] = effect
        syncEffects()
        bumpMutationCount()
    }

    fun getEffect(id: String): Effect? =
        _effectsMap[id]

    fun effectsForSubstance(substanceId: String): List<Effect> =
        _effectsMap.values.filter { substanceId in it.substanceIds }

    // --- Custom Units ---

    fun upsertCustomUnit(unit: CustomUnit) {
        _customUnitsMap[unit.id] = unit
        syncCustomUnits()
        bumpMutationCount()
    }

    fun deleteCustomUnit(id: String) {
        _customUnitsMap.remove(id)
        syncCustomUnits()
        bumpMutationCount()
    }

    fun customUnitsForSubstance(substanceId: String): List<CustomUnit> =
        _customUnitsMap.values.filter { it.substanceId == substanceId }

    fun setShulginRating(enabled: Boolean) {
        _useShulginRating.value = enabled
        bumpMutationCount()
    }

    fun setSubstanceColors(enabled: Boolean) {
        _useSubstanceColors.value = enabled
    }

    // --- Notes ---

    fun upsertNote(note: Note) {
        val prev = _notesMap.put(note.id, note)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null && prevSessionId != note.sessionId) {
            _notesBySession[prevSessionId]?.removeAll { it.id == note.id }
        }
        _notesBySession.getOrPut(note.sessionId ?: return) { mutableListOf() }.add(note)
        syncNotes()
        bumpMutationCount()
    }

    fun notesForSession(sessionId: String): List<Note> =
        _notesBySession[sessionId] ?: emptyList()

    // --- Timeline ---

    fun upsertTimelineEvent(event: TimelineEvent) {
        val prev = _timelineEventsMap.put(event.id, event)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null && prevSessionId != event.sessionId) {
            _eventsBySession[prevSessionId]?.removeAll { it.id == event.id }
        }
        _eventsBySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
        syncTimelineEvents()
        bumpMutationCount()
    }

    fun eventsForSession(sessionId: String): List<TimelineEvent> =
        (_eventsBySession[sessionId] ?: emptyList()).sortedBy { it.timestamp }

    // --- Index rebuilds (used after bulk removal) ---

    private fun rebuildDoseIndex() {
        _dosesBySession.clear()
        for (dose in _dosesMap.values) {
            _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)
        }
    }

    // ========================
    // Query layer (4.3)
    // ========================

    /**
     * Returns session IDs for sessions on or within the given date range.
     * Dates are ISO-8601 (yyyy-MM-dd) strings. Null bounds are open.
     */
    fun sessionIdsOnDateRange(fromDate: String? = null, toDate: String? = null): List<String> {
        val from = fromDate?.let { LocalDate.parse(it) }
        val to = toDate?.let { LocalDate.parse(it) }
        return _sessionsByDate.entries
            .filter { (date, _) ->
                (from == null || date >= from) && (to == null || date <= to)
            }
            .sortedBy { (date, _) -> date }
            .flatMap { (_, ids) -> ids }
    }

    /**
     * Returns session IDs for all sessions that include a given substance.
     */
    fun sessionIdsForSubstance(substanceId: String): List<String> =
        _sessionsPerSubstance[substanceId]?.toList() ?: emptyList()

    /**
     * Returns session IDs tagged with a given tag.
     */
    fun sessionIdsWithTag(tag: String): List<String> =
        _sessionsByTag[tag]?.toList() ?: emptyList()

    /**
     * Returns session IDs matching any of the given tags. Empty set = all sessions.
     */
    fun sessionIdsWithAnyTag(tags: List<String>): Set<String> {
        if (tags.isEmpty()) return _sessionsMap.keys
        val result = mutableSetOf<String>()
        for (tag in tags) {
            _sessionsByTag[tag]?.let { result.addAll(it) }
        }
        return result
    }

    /**
     * Rebuilds all query indices from scratch. Call after bulk import.
     */
    fun rebuildIndices() {
        rebuildAllIndices()
    }

    // ========================
    // DataFrame export (2.4)
    // ========================

    fun sessionsDataFrame(): List<SessionDataRow> {
        val subNameCache = _substancesMap.values.associate { it.id to it.name }
        return _sessionsMap.values.map { session ->
            val doses = dosesForSession(session.id)
            val subNames = doses.mapNotNull { subNameCache[it.substanceId] }.distinct()
            val dt = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val endDt = session.endTime?.let {
                Instant.fromEpochMilliseconds(it)
                    .toLocalDateTime(TimeZone.currentSystemDefault())
            }
            val durationHours = session.endTime?.let {
                (it - session.startTime) / 3600000.0
            }
            SessionDataRow(
                id = session.id,
                title = session.title,
                date = dt.date.toString(),
                startTime = dt.toString(),
                endTime = endDt?.toString(),
                durationHours = durationHours?.let { kotlin.math.round(it * 100) / 100.0 },
                tags = session.tags.joinToString(";"),
                set = session.set,
                setting = session.setting,
                intention = session.intention,
                outcome = session.outcome,
                rating = session.rating,
                shulginRating = session.shulginRating,
                consumerName = session.consumerName,
                isFavorite = session.isFavorite,
                isArchived = session.isArchived,
                substanceNames = subNames.joinToString(";"),
                doseCount = doses.size
            )
        }
    }

    fun dosesDataFrame(): List<DoseDataRow> {
        val subNameCache = _substancesMap.values.associate { it.id to it.name }
        return _dosesMap.values.map { dose ->
            DoseDataRow(
                id = dose.id,
                sessionId = dose.sessionId,
                substanceId = dose.substanceId,
                substanceName = subNameCache[dose.substanceId] ?: "unknown",
                route = dose.routeOfAdministration,
                amount = dose.amount,
                unit = dose.unit,
                timestamp = dose.timestamp,
                redosing = dose.redosing,
                isEstimate = dose.isDoseEstimate,
                notes = dose.notes
            )
        }
    }

    fun substancesDataFrame(): List<SubstanceDataRow> {
        return _substancesMap.values.map { sub ->
            SubstanceDataRow(
                id = sub.id,
                name = sub.name,
                aliases = sub.aliases.joinToString("; "),
                substanceClass = sub.substanceClass.joinToString("; "),
                cid = sub.cid,
                molecularFormula = sub.chemicalProperties?.molecularFormula,
                molecularWeight = sub.chemicalProperties?.molecularWeight,
                iupacName = sub.chemicalProperties?.iupacName,
                logP = sub.chemicalProperties?.xlogP,
                routes = sub.routesOfAdministration.joinToString("; "),
                effects = sub.effects.joinToString("; "),
                toxicity = sub.toxicity.joinToString("; "),
                addictionPotential = sub.addictionPotential
            )
        }
    }

    // ========================
    // Auto-save support (4.1)
    // ========================

    /**
     * Sets up debounced auto-save. Every mutation triggers a save after
     * 2 seconds of inactivity. Call once at startup with the app scope.
     */
    fun autoSave(store: JournalStore, scope: CoroutineScope): Job {
        return scope.launch {
            mutationCount
                .drop(1) // skip initial 0
                .debounce(2000)
                .collect { store.save() }
        }
    }

    // --- Clear ---

    fun clearAll() {
        _sessionsMap.clear()
        _substancesMap.clear()
        _dosesMap.clear()
        _notesMap.clear()
        _timelineEventsMap.clear()
        _interactionsMap.clear()
        _effectsMap.clear()
        _customUnitsMap.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _sessionsByTag.clear()
        _useShulginRating.value = false
        _useSubstanceColors.value = true
        syncSessions()
        syncSubstances()
        syncDoses()
        syncNotes()
        syncTimelineEvents()
        syncInteractions()
        syncEffects()
        syncCustomUnits()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    companion object {
        val instance: JournalRepository by lazy { JournalRepository() }
    }
}
