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

import app.journal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

// Tabular export data classes (R/Pandas-friendly)
data class SessionDataRow(
    val id: String, val title: String, val date: String,
    val startTime: String, val endTime: String?,
    val durationHours: Double?, val tags: String,
    val set: String?, val setting: String?, val intention: String?,
    val outcome: String?, val rating: Int?,
    val shulginRating: String?, val consumerName: String?,
    val isFavorite: Boolean, val isArchived: Boolean,
    val substanceNames: String, val doseCount: Int
)

data class DoseDataRow(
    val id: String, val sessionId: String, val substanceId: String,
    val substanceName: String, val route: String,
    val amount: Double, val unit: String, val timestamp: Long,
    val redosing: Boolean, val isEstimate: Boolean, val notes: String?
)

data class SubstanceDataRow(
    val id: String, val name: String, val aliases: String,
    val substanceClass: String, val cid: Long?,
    val molecularFormula: String?, val molecularWeight: String?,
    val iupacName: String?, val logP: Double?,
    val routes: String, val effects: String,
    val toxicity: String, val addictionPotential: String?
)

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

    private val sessionsStore = EntityStore(Session::id)
    private val dosesStore = EntityStore(Dose::id)
    private val substancesStore = EntityStore(Substance::id)
    private val notesStore = EntityStore(Note::id)
    private val timelineEventsStore = EntityStore(TimelineEvent::id)
    private val interactionsStore = EntityStore(Interaction::id)
    private val effectsStore = EntityStore(Effect::id)
    private val customUnitsStore = EntityStore(CustomUnit::id)

    // ---- Preferences ----
    private val _useShulginRating = MutableStateFlow(false)
    override val useShulginRating: StateFlow<Boolean> = _useShulginRating.asStateFlow()

    private val _useSubstanceColors = MutableStateFlow(true)
    override val useSubstanceColors: StateFlow<Boolean> = _useSubstanceColors.asStateFlow()

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
    override val pendingConflictCount: Flow<Int> = notes.map { list ->
        list.count { it.conflictSiblings.isNotEmpty() }
    }

    // ---- Session-child indexes (incrementally updated) ----
    private val _dosesBySession = mutableMapOf<String, MutableList<Dose>>()
    private val _notesBySession = mutableMapOf<String, MutableList<Note>>()
    private val _eventsBySession = mutableMapOf<String, MutableList<TimelineEvent>>()

    // ---- Precomputed query indices ----
    private val _sessionsByDate = mutableMapOf<LocalDate, MutableList<String>>()
    private val _sessionsPerSubstance = mutableMapOf<String, MutableSet<String>>()
    private val _sessionsByTag = mutableMapOf<String, MutableSet<String>>()
    /** substanceId -> list of effects that reference this substance */
    private val _effectsBySubstance = mutableMapOf<String, MutableList<Effect>>()
    private val lock = Any()

    // ========================
    //  Bulk apply (seed load)
    // ========================

    override fun applySnapshot(snapshot: JournalSnapshot) {
        sessionsStore.applyAll(snapshot.sessions)
        substancesStore.applyAll(snapshot.substances)
        dosesStore.applyAll(snapshot.doses)
        notesStore.applyAll(snapshot.notes)
        timelineEventsStore.applyAll(snapshot.timelineEvents)
        interactionsStore.applyAll(snapshot.interactions)
        effectsStore.applyAll(snapshot.effects)
        customUnitsStore.applyAll(snapshot.customUnits)
        rebuildAllIndices()
        setShulginRating(snapshot.useShulginRating)
        setSubstanceColors(snapshot.useSubstanceColors)
    }

    // ========================
    //  Query index helpers
    // ========================

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
        _effectsBySubstance.clear()
        sessionsStore.forEachValue { addSessionToIndices(it) }
        dosesStore.forEachValue { dose ->
            _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)
        }
        effectsStore.forEachValue { effect ->
            for (subId in effect.substanceIds) {
                _effectsBySubstance.getOrPut(subId) { mutableListOf() }.add(effect)
            }
        }
    }

    // ========================
    //  Sessions
    // ========================

    override fun upsertSession(session: Session) {
        val oldSession = sessionsStore.put(session)
        if (oldSession == null) addSessionToIndices(session)
        bumpMutationCount()
    }

    override fun getSession(id: String): Session? = sessionsStore.get(id)

    override fun deleteSession(id: String) {
        val session = sessionsStore.get(id) ?: return
        sessionsStore.remove(id)
        removeSessionFromIndices(session)

        val affectedSubstances = dosesStore.removeWhere { it.sessionId == id }
            .map { it.substanceId }.toSet()

        _dosesBySession.remove(id)
        _notesBySession.remove(id)
        _eventsBySession.remove(id)

        notesStore.removeWhere { it.sessionId == id }
        timelineEventsStore.removeWhere { it.sessionId == id }

        for (subId in affectedSubstances) {
            _sessionsPerSubstance[subId]?.remove(id)
            if (_sessionsPerSubstance[subId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(subId)
        }
        bumpMutationCount()
    }

    // ========================
    //  Doses
    // ========================

    override fun upsertDose(dose: Dose) {
        val prev = dosesStore.put(dose)
        val prevSessionId = prev?.sessionId
        val prevSubstanceId = prev?.substanceId

        if (prevSessionId != null && prevSessionId != dose.sessionId) {
            _dosesBySession[prevSessionId]?.removeAll { it.id == dose.id }
        }
        _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)

        if (prevSubstanceId != null && prevSubstanceId != dose.substanceId) {
            _sessionsPerSubstance[prevSubstanceId]?.remove(dose.sessionId)
            if (_sessionsPerSubstance[prevSubstanceId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(prevSubstanceId)
        }
        _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)

        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun dosesForSession(sessionId: String): List<Dose> =
        synchronized(this) { _dosesBySession[sessionId]?.toList() ?: emptyList() }

    override fun deleteDose(id: String) {
        val removed = dosesStore.remove(id) ?: return
        synchronized(this) {
            _dosesBySession[removed.sessionId]?.removeAll { it.id == id }
            _sessionsPerSubstance[removed.substanceId]?.remove(removed.sessionId)
            if (_sessionsPerSubstance[removed.substanceId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(removed.substanceId)
        }
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Substances
    // ========================

    override fun upsertSubstance(substance: Substance) {
        substancesStore.put(substance)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun getSubstance(id: String): Substance? = substancesStore.get(id)

    override fun searchSubstances(query: String): List<Substance> {
        val q = query.lowercase()
        return substancesStore.all.filter {
            it.name.lowercase().contains(q) ||
            it.aliases.any { a -> a.lowercase().contains(q) }
        }
    }

    override fun deleteSubstance(id: String) {
        substancesStore.remove(id)
        val affectedSessionIds = synchronized(this) {
            dosesStore.removeWhere { it.substanceId == id }
                .map { it.sessionId }.toSet()
        }
        for (sessionId in affectedSessionIds) {
            _dosesBySession[sessionId]?.removeAll { it.substanceId == id }
        }
        synchronized(this) { _sessionsPerSubstance.remove(id) }
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Interactions
    // ========================

    override fun upsertInteraction(interaction: Interaction) {
        interactionsStore.put(interaction)
        bumpMutationCount()
    }

    // ========================
    //  Effects
    // ========================

    override fun upsertEffect(effect: Effect) {
        val prev = effectsStore.put(effect)
        // Update _effectsBySubstance index
        synchronized(lock) {
            if (prev != null) {
                for (subId in prev.substanceIds) {
                    _effectsBySubstance[subId]?.removeAll { it.id == effect.id }
                    if (_effectsBySubstance[subId]?.isEmpty() == true)
                        _effectsBySubstance.remove(subId)
                }
            }
            for (subId in effect.substanceIds) {
                _effectsBySubstance.getOrPut(subId) { mutableListOf() }.add(effect)
            }
        }
        bumpMutationCount()
    }

    override fun getEffect(id: String): Effect? = effectsStore.get(id)

    override fun effectsForSubstance(substanceId: String): List<Effect> =
        synchronized(lock) { _effectsBySubstance[substanceId]?.toList() ?: emptyList() }

    // ========================
    //  Custom Units
    // ========================

    override fun upsertCustomUnit(unit: CustomUnit) {
        customUnitsStore.put(unit)
        bumpMutationCount()
    }

    override fun deleteCustomUnit(id: String) {
        customUnitsStore.remove(id)
        bumpMutationCount()
    }

    override fun customUnitsForSubstance(substanceId: String): List<CustomUnit> =
        customUnitsStore.all.filter { it.substanceId == substanceId }

    // ========================
    //  Preferences
    // ========================

    override fun setShulginRating(enabled: Boolean) {
        _useShulginRating.value = enabled
        bumpMutationCount()
    }

    override fun setSubstanceColors(enabled: Boolean) {
        _useSubstanceColors.value = enabled
    }

    // ========================
    //  Notes
    // ========================

    override fun upsertNote(note: Note) = synchronized(lock) {
        val prev = notesStore.put(note)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null && prevSessionId != note.sessionId) {
            _notesBySession[prevSessionId]?.removeAll { it.id == note.id }
        }
        val sessionId = note.sessionId ?: return
        _notesBySession.getOrPut(sessionId) { mutableListOf() }.add(note)
        bumpMutationCount()
    }

    override fun notesForSession(sessionId: String): List<Note> =
        synchronized(lock) { _notesBySession[sessionId]?.toList() ?: emptyList() }

    // ========================
    //  Timeline Events
    // ========================

    override fun upsertTimelineEvent(event: TimelineEvent) = synchronized(lock) {
        val prev = timelineEventsStore.put(event)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null && prevSessionId != event.sessionId) {
            _eventsBySession[prevSessionId]?.removeAll { it.id == event.id }
        }
        _eventsBySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
        bumpMutationCount()
    }

    override fun eventsForSession(sessionId: String): List<TimelineEvent> =
        synchronized(lock) { (_eventsBySession[sessionId] ?: emptyList()).sortedBy { it.timestamp } }

    // ========================
    //  Query indices (public)
    // ========================

    override fun sessionIdsOnDateRange(fromDate: String?, toDate: String?): List<String> = synchronized(lock) {
        val from = fromDate?.let { LocalDate.parse(it) }
        val to = toDate?.let { LocalDate.parse(it) }
        _sessionsByDate.entries
            .filter { (date, _) ->
                (from == null || date >= from) && (to == null || date <= to)
            }
            .sortedBy { (date, _) -> date }
            .flatMap { (_, ids) -> ids }
    }

    override fun sessionIdsForSubstance(substanceId: String): List<String> =
        synchronized(lock) { _sessionsPerSubstance[substanceId]?.toList() ?: emptyList() }

    override fun sessionIdsWithTag(tag: String): List<String> =
        synchronized(lock) { _sessionsByTag[tag]?.toList() ?: emptyList() }

    override fun sessionIdsWithAnyTag(tags: List<String>): Set<String> = synchronized(lock) {
        if (tags.isEmpty()) return sessionsStore.keys
        val result = mutableSetOf<String>()
        for (tag in tags) {
            _sessionsByTag[tag]?.let { result.addAll(it) }
        }
        result
    }

    override fun rebuildIndices() { rebuildAllIndices() }

    // ========================
    //  DataFrame export
    // ========================

    override fun sessionsDataFrame(): List<SessionDataRow> = synchronized(lock) {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        sessionsStore.all.map { session ->
            val sessionDoses = dosesForSession(session.id)
            val subNames = sessionDoses.mapNotNull { subNameCache[it.substanceId] }.distinct()
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
                id = session.id, title = session.title,
                date = dt.date.toString(), startTime = dt.toString(),
                endTime = endDt?.toString(),
                durationHours = durationHours?.let { kotlin.math.round(it * 100) / 100.0 },
                tags = session.tags.joinToString(";"),
                set = session.set, setting = session.setting,
                intention = session.intention, outcome = session.outcome,
                rating = session.rating, shulginRating = session.shulginRating,
                consumerName = session.consumerName,
                isFavorite = session.isFavorite, isArchived = session.isArchived,
                substanceNames = subNames.joinToString(";"),
                doseCount = sessionDoses.size
            )
        }
    }

    override fun dosesDataFrame(): List<DoseDataRow> {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        return dosesStore.all.map { dose ->
            DoseDataRow(
                id = dose.id, sessionId = dose.sessionId,
                substanceId = dose.substanceId,
                substanceName = subNameCache[dose.substanceId] ?: "unknown",
                route = dose.routeOfAdministration,
                amount = dose.amount, unit = dose.unit,
                timestamp = dose.timestamp, redosing = dose.redosing,
                isEstimate = dose.isDoseEstimate, notes = dose.notes
            )
        }
    }

    override fun substancesDataFrame(): List<SubstanceDataRow> {
        return substancesStore.all.map { sub ->
            SubstanceDataRow(
                id = sub.id, name = sub.name,
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
    //  Auto-save
    // ========================

    override fun autoSave(store: JournalStore, scope: CoroutineScope): Job {
        return scope.launch {
            mutationCount
                .drop(1)
                .debounce(2000)
                .collect { store.save() }
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
    ) = synchronized(lock) {
        sessionsStore.applyAll(sessions)
        dosesStore.applyAll(doses)
        timelineEventsStore.applyAll(timelineEvents)
        interactionsStore.applyAll(interactions)
        rebuildAllIndices()
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Clear
    // ========================

    override fun clearAll() = synchronized(lock) {
        sessionsStore.clear()
        substancesStore.clear()
        dosesStore.clear()
        notesStore.clear()
        timelineEventsStore.clear()
        interactionsStore.clear()
        effectsStore.clear()
        _effectsBySubstance.clear()
        customUnitsStore.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _sessionsByTag.clear()
        _useShulginRating.value = false
        _useSubstanceColors.value = true
        bumpToleranceVersion()
        bumpMutationCount()
    }

    companion object {
        val instance: JournalRepository by lazy { JournalRepository() }
    }
}
