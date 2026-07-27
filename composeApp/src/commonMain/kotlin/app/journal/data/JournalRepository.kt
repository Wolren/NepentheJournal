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

import app.journal.log.Log
import app.journal.model.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import app.journal.util.PlatformLock
import kotlinx.datetime.toLocalDateTime

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

    // ---- Obsidian vault config ----
    private val _obsidianVaultPath = MutableStateFlow("")
    override val obsidianVaultPath: StateFlow<String> = _obsidianVaultPath.asStateFlow()

    private val _obsidianAutoExport = MutableStateFlow(false)
    override val obsidianAutoExport: StateFlow<Boolean> = _obsidianAutoExport.asStateFlow()

    private val _obsidianSubfolder = MutableStateFlow("Nepenthe")
    override val obsidianSubfolder: StateFlow<String> = _obsidianSubfolder.asStateFlow()

    private val _obsidianFileOrganization = MutableStateFlow("flat")
    override val obsidianFileOrganization: StateFlow<String> = _obsidianFileOrganization.asStateFlow()

    // ---- Display preferences ----
    private val _showSessionsTrendChart = MutableStateFlow(false)
    override val showSessionsTrendChart: StateFlow<Boolean> = _showSessionsTrendChart.asStateFlow()

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

    // ---- Full-text search index ----
    val searchIndex = SearchIndex()

    // ---- Precomputed query indices ----
    private val _sessionsByDate = mutableMapOf<LocalDate, MutableList<String>>()
    private val _sessionsPerSubstance = mutableMapOf<String, MutableSet<String>>()
    /** substanceId -> list of effects that reference this substance */
    private val _effectsBySubstance = mutableMapOf<String, MutableList<Effect>>()
    /** substanceId -> list of custom units */
    private val _customUnitsBySubstance = mutableMapOf<String, MutableList<CustomUnit>>()

    /**
     * Precomputed dose stats per substance.
     * (distinctSessionCount, lastUsedTimestamp). Updated incrementally on dose mutations.
     */
    override val substanceDoseStats: Map<String, Pair<Int, Long>>
        get() = lock.withLock { _substanceDoseStats.toMap() }
    private val _substanceDoseStats = mutableMapOf<String, Pair<Int, Long>>()
    private val _doseStatsSessionIds = mutableMapOf<String, MutableSet<String>>()
    private val lock = PlatformLock()

    // ========================
    //  Bulk apply (seed load)
    // ========================

    /** Bulk-apply entities from a sync delta — single emissions per store, single mutation bump. */
    fun applyBatch(
        sessions: List<Session> = emptyList(),
        doses: List<Dose> = emptyList(),
        substances: List<Substance> = emptyList(),
        effects: List<Effect> = emptyList(),
        interactions: List<Interaction> = emptyList(),
        notes: List<Note> = emptyList(),
        timelineEvents: List<TimelineEvent> = emptyList(),
        customUnits: List<CustomUnit> = emptyList()
    ) = lock.withLock {
        if (sessions.isNotEmpty()) sessionsStore.putAll(sessions)
        if (doses.isNotEmpty()) dosesStore.putAll(doses)
        if (substances.isNotEmpty()) substancesStore.putAll(substances)
        if (effects.isNotEmpty()) effectsStore.putAll(effects)
        if (interactions.isNotEmpty()) interactionsStore.putAll(interactions)
        if (notes.isNotEmpty()) notesStore.putAll(notes)
        if (timelineEvents.isNotEmpty()) timelineEventsStore.putAll(timelineEvents)
        if (customUnits.isNotEmpty()) customUnitsStore.putAll(customUnits)
        // Rebuild all indices after bulk upsert to handle updates to existing entities
        // where old index entries (dates, per-session children) need to be replaced.
        if (sessions.isNotEmpty() || doses.isNotEmpty() || effects.isNotEmpty() ||
            notes.isNotEmpty() || timelineEvents.isNotEmpty() || customUnits.isNotEmpty()
        ) {
            rebuildAllIndices()
        }
        bumpMutationCount()
    }

    override fun applySnapshot(snapshot: JournalSnapshot) = lock.withLock {
        sessionsStore.putAll(snapshot.sessions)
        substancesStore.putAll(snapshot.substances)
        dosesStore.putAll(snapshot.doses)
        notesStore.putAll(snapshot.notes)
        timelineEventsStore.putAll(snapshot.timelineEvents)
        interactionsStore.putAll(snapshot.interactions)
        effectsStore.putAll(snapshot.effects)
        customUnitsStore.putAll(snapshot.customUnits)
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
    }

    private fun removeSessionFromIndices(session: Session) {
        val date = sessionDate(session)
        _sessionsByDate[date]?.remove(session.id)
        if (_sessionsByDate[date]?.isEmpty() == true) _sessionsByDate.remove(date)
    }

    private fun rebuildAllIndices() {
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _effectsBySubstance.clear()
        _customUnitsBySubstance.clear()
        _substanceDoseStats.clear()
        _doseStatsSessionIds.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        sessionsStore.forEachValue { addSessionToIndices(it) }
        dosesStore.forEachValue { dose ->
            _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)
            _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)
            updateDoseStatsForSubstance(dose.substanceId, dose.sessionId, dose.timestamp)
        }
        notesStore.forEachValue { note ->
            if (note.sessionId != null) {
                _notesBySession.getOrPut(note.sessionId) { mutableListOf() }.add(note)
            }
        }
        timelineEventsStore.forEachValue { event ->
            if (event.sessionId != null) {
                _eventsBySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
            }
        }
        effectsStore.forEachValue { effect ->
            for (subId in effect.substanceIds) {
                _effectsBySubstance.getOrPut(subId) { mutableListOf() }.add(effect)
            }
        }
        customUnitsStore.forEachValue { unit ->
            _customUnitsBySubstance.getOrPut(unit.substanceId) { mutableListOf() }.add(unit)
        }
        rebuildSearchIndex()
    }

    /** Incrementally update precomputed dose stats for a substance — counts distinct sessions only. */
    private fun updateDoseStatsForSubstance(substanceId: String, sessionId: String, timestamp: Long) {
        val ids = _doseStatsSessionIds.getOrPut(substanceId) { mutableSetOf() }
        val isNew = ids.add(sessionId)
        val prev = _substanceDoseStats[substanceId]?.first ?: 0
        val count = if (isNew) prev + 1 else prev
        val lastUsed = maxOf(_substanceDoseStats[substanceId]?.second ?: 0L, timestamp)
        _substanceDoseStats[substanceId] = Pair(count, lastUsed)
    }

    // ========================
    //  Sessions
    // ========================

    override fun upsertSession(session: Session) = lock.withLock {
        val oldSession = sessionsStore.put(session)
        if (oldSession != null) removeSessionFromIndices(oldSession)
        addSessionToIndices(session)
        bumpMutationCount()
    }

    override fun getSession(id: String): Session? = lock.withLock { sessionsStore.get(id) }

    override fun deleteSession(id: String) = lock.withLock {
        val session = sessionsStore.get(id) ?: return@withLock
        sessionsStore.remove(id)
        removeSessionFromIndices(session)

        // Batch-remove child entities with single emissions per store
        val removedDoses = dosesStore.removeWhere { it.sessionId == id }
        val removedSubstances = removedDoses.map { it.substanceId }.toSet()

        _dosesBySession.remove(id)
        _notesBySession.remove(id)
        _eventsBySession.remove(id)

        notesStore.removeWhere { it.sessionId == id }
        timelineEventsStore.removeWhere { it.sessionId == id }

        // Rebuild dose stats for affected substances
        for (subId in removedSubstances) {
            _sessionsPerSubstance[subId]?.remove(id)
            if (_sessionsPerSubstance[subId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(subId)
            rebuildSubstanceDoseStats(subId)
        }

        bumpMutationCount()
        bumpToleranceVersion()
    }

    /** Recompute dose stats for a single substance from scratch. */
    private fun rebuildSubstanceDoseStats(substanceId: String) {
        val relevant = dosesStore.all.filter { it.substanceId == substanceId }
        if (relevant.isEmpty()) {
            _substanceDoseStats.remove(substanceId)
            _doseStatsSessionIds.remove(substanceId)
            return
        }
        val sessionIds = relevant.map { it.sessionId }.distinct()
        val lastTimestamp = relevant.maxOf { it.timestamp }
        _substanceDoseStats[substanceId] = Pair(sessionIds.size, lastTimestamp)
        _doseStatsSessionIds[substanceId] = sessionIds.toMutableSet()
    }

    // ========================
    //  Doses
    // ========================

    override fun upsertDose(dose: Dose) = lock.withLock {
        val prev = dosesStore.put(dose)
        val prevSessionId = prev?.sessionId
        val prevSubstanceId = prev?.substanceId

        // Remove previous entry from the session index to prevent duplicates on update
        if (prevSessionId != null) {
            _dosesBySession[prevSessionId]?.removeAll { it.id == dose.id }
        }
        _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)

        if (prevSubstanceId != null && prevSubstanceId != dose.substanceId) {
            _sessionsPerSubstance[prevSubstanceId]?.remove(dose.sessionId)
            if (_sessionsPerSubstance[prevSubstanceId]?.isEmpty() == true)
                _sessionsPerSubstance.remove(prevSubstanceId)
            rebuildSubstanceDoseStats(prevSubstanceId)
        }
        _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)

        // Update substance dose stats incrementally (distinct session count + last used timestamp).
        updateDoseStatsForSubstance(dose.substanceId, dose.sessionId, dose.timestamp)

        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun dosesForSession(sessionId: String): List<Dose> =
        lock.withLock { _dosesBySession[sessionId]?.toList() ?: emptyList() }

    override fun deleteDose(id: String) = lock.withLock {
        val removed = dosesStore.remove(id) ?: return@withLock
        _dosesBySession[removed.sessionId]?.removeAll { it.id == id }
        _sessionsPerSubstance[removed.substanceId]?.remove(removed.sessionId)
        if (_sessionsPerSubstance[removed.substanceId]?.isEmpty() == true)
            _sessionsPerSubstance.remove(removed.substanceId)
        rebuildSubstanceDoseStats(removed.substanceId)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun deleteNote(id: String) = lock.withLock {
        val removed = notesStore.remove(id) ?: return@withLock
        removed.sessionId?.let { _notesBySession[it]?.removeAll { n -> n.id == id } }
        bumpMutationCount()
    }

    override fun deleteTimelineEvent(id: String) = lock.withLock {
        val removed = timelineEventsStore.remove(id) ?: return@withLock
        _eventsBySession[removed.sessionId]?.removeAll { e -> e.id == id }
        bumpMutationCount()
    }

    // ========================
    //  Substances
    // ========================

    override fun upsertSubstance(substance: Substance) = lock.withLock {
        substancesStore.put(substance)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun getSubstance(id: String): Substance? = lock.withLock { substancesStore.get(id) }

    override fun searchSubstances(query: String): List<Substance> = lock.withLock {
        val q = query.lowercase()
        substancesStore.all.filter {
            it.name.lowercase().contains(q) ||
            it.aliases.any { a -> a.lowercase().contains(q) }
        }
    }

    override fun deleteSubstance(id: String) = lock.withLock {
        substancesStore.remove(id)

        // Cascade: remove all child entities for this substance
        customUnitsStore.removeWhere { it.substanceId == id }
        _customUnitsBySubstance.remove(id)

        // Clean up effect index entries referencing this substance
        effectsStore.forEachValue { effect ->
            if (id in effect.substanceIds) {
                _effectsBySubstance[id]?.removeAll { it.id == effect.id }
            }
        }
        _effectsBySubstance.remove(id)

        // Clean up interactions referencing this substance
        interactionsStore.removeWhere {
            id in listOf(it.substanceAId, it.substanceBId)
        }

        val affectedSessionIds = dosesStore.removeWhere { it.substanceId == id }
            .map { it.sessionId }.toSet()
        for (sessionId in affectedSessionIds) {
            _dosesBySession[sessionId]?.removeAll { it.substanceId == id }
        }
        _sessionsPerSubstance.remove(id)
        _substanceDoseStats.remove(id)
        _doseStatsSessionIds.remove(id)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Interactions
    // ========================

    override fun upsertInteraction(interaction: Interaction) = lock.withLock {
        interactionsStore.put(interaction)
        bumpMutationCount()
    }

    override fun getInteraction(id: String): Interaction? = lock.withLock { interactionsStore.get(id) }

    // ========================
    //  Effects
    // ========================

    override fun upsertEffect(effect: Effect) = lock.withLock {
        val prev = effectsStore.put(effect)
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
        bumpMutationCount()
    }

    override fun getEffect(id: String): Effect? = lock.withLock { effectsStore.get(id) }

    override fun effectsForSubstance(substanceId: String): List<Effect> =
        lock.withLock { _effectsBySubstance[substanceId]?.toList() ?: emptyList() }

    // ========================
    //  Custom Units
    // ========================

    override fun upsertCustomUnit(unit: CustomUnit) = lock.withLock {
        val prev = customUnitsStore.put(unit)
        if (prev != null) {
            _customUnitsBySubstance[prev.substanceId]?.removeAll { it.id == unit.id }
            if (_customUnitsBySubstance[prev.substanceId]?.isEmpty() == true &&
                prev.substanceId != unit.substanceId)
                _customUnitsBySubstance.remove(prev.substanceId)
        }
        _customUnitsBySubstance.getOrPut(unit.substanceId) { mutableListOf() }.add(unit)
        bumpMutationCount()
    }

    override fun deleteCustomUnit(id: String) = lock.withLock {
        val removed = customUnitsStore.remove(id) ?: return@withLock
        _customUnitsBySubstance[removed.substanceId]?.removeAll { it.id == id }
        if (_customUnitsBySubstance[removed.substanceId]?.isEmpty() == true)
            _customUnitsBySubstance.remove(removed.substanceId)
        bumpMutationCount()
    }

    override fun customUnitsForSubstance(substanceId: String): List<CustomUnit> = lock.withLock {
        _customUnitsBySubstance[substanceId]?.toList() ?: emptyList()
    }

    // ========================
    //  Preferences
    // ========================

    override fun setShulginRating(enabled: Boolean) = lock.withLock {
        _useShulginRating.value = enabled
        bumpMutationCount()
    }

    override fun setSubstanceColors(enabled: Boolean) = lock.withLock {
        _useSubstanceColors.value = enabled
    }

    override fun setObsidianVaultPath(path: String) = lock.withLock {
        _obsidianVaultPath.value = path
        bumpMutationCount()
    }

    override fun setObsidianAutoExport(enabled: Boolean) = lock.withLock {
        _obsidianAutoExport.value = enabled
        bumpMutationCount()
    }

    override fun setObsidianSubfolder(folder: String) = lock.withLock {
        _obsidianSubfolder.value = folder
        bumpMutationCount()
    }

    override fun setObsidianFileOrganization(org: String) = lock.withLock {
        _obsidianFileOrganization.value = org
        bumpMutationCount()
    }

    override fun setShowSessionsTrendChart(enabled: Boolean) = lock.withLock {
        _showSessionsTrendChart.value = enabled
        bumpMutationCount()
    }

    // ========================
    //  Notes
    // ========================

    override fun upsertNote(note: Note) = lock.withLock {
        val prev = notesStore.put(note)
        val prevSessionId = prev?.sessionId
        // Remove previous entry from index to prevent duplicates on update
        if (prevSessionId != null) {
            _notesBySession[prevSessionId]?.removeAll { it.id == note.id }
        }
        note.sessionId?.let { sessionId ->
            _notesBySession.getOrPut(sessionId) { mutableListOf() }.add(note)
        }
        bumpMutationCount()
    }

    override fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note? = lock.withLock {
        val sessionId = note.sessionId ?: return@withLock null
        val existing = notesStore.get(note.id)
        val resolved = if (existing != null && existing.body != note.body) {
            note.copy(conflictSiblings = existing.conflictSiblings +
                    ConflictSibling(note.body, remoteDeviceId, note.updatedAt))
        } else note
        notesStore.put(resolved)
        if (sessionId != (existing?.sessionId ?: sessionId)) {
            existing?.sessionId?.let { _notesBySession[it]?.removeAll { n -> n.id == note.id } }
        }
        if (existing != null) {
            _notesBySession[sessionId]?.removeAll { it.id == note.id }
        }
        _notesBySession.getOrPut(sessionId) { mutableListOf() }.add(resolved)
        bumpMutationCount()
        resolved
    }

    override fun notesForSession(sessionId: String): List<Note> =
        lock.withLock { _notesBySession[sessionId]?.toList() ?: emptyList() }

    // ========================
    //  Timeline Events
    // ========================

    override fun upsertTimelineEvent(event: TimelineEvent) = lock.withLock {
        val prev = timelineEventsStore.put(event)
        val prevSessionId = prev?.sessionId
        // Remove previous entry from index to prevent duplicates on update
        if (prevSessionId != null) {
            _eventsBySession[prevSessionId]?.removeAll { it.id == event.id }
        }
        _eventsBySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
        bumpMutationCount()
    }

    override fun eventsForSession(sessionId: String): List<TimelineEvent> =
        lock.withLock { (_eventsBySession[sessionId] ?: emptyList()).sortedBy { it.timestamp } }

    // ========================
    //  Query indices (public)
    // ========================

    override fun sessionIdsOnDateRange(fromDate: String?, toDate: String?): List<String> = lock.withLock {
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
        lock.withLock { _sessionsPerSubstance[substanceId]?.toList() ?: emptyList() }

    override fun rebuildIndices() { rebuildAllIndices() }

    // ========================
    //  DataFrame export
    // ========================

    override fun sessionsDataFrame(): List<SessionDataRow> = lock.withLock {
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

    override fun dosesDataFrame(): List<DoseDataRow> = lock.withLock {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        dosesStore.all.map { dose ->
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

    override fun substancesDataFrame(): List<SubstanceDataRow> = lock.withLock {
        substancesStore.all.map { sub ->
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

    override fun exportSessionBundles(): List<SessionBundle> = lock.withLock {
        sessionsStore.all.sortedByDescending { it.startTime }.map { session ->
            SessionBundle(session, dosesForSession(session.id))
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
                .collect {
                    try {
                        store.save()
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
        _effectsBySubstance.clear()
        customUnitsStore.clear()
        _customUnitsBySubstance.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _substanceDoseStats.clear()
        _doseStatsSessionIds.clear()
        _useShulginRating.value = false
        _useSubstanceColors.value = true
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Full-text search
    // ========================

    override fun search(query: String): List<SearchResult> = lock.withLock {
        searchIndex.search(query)
    }

    override fun rebuildSearchIndex() {
        searchIndex.rebuild(this)
    }

    companion object {
        val instance: JournalRepository by lazy { JournalRepository() }
    }
}
