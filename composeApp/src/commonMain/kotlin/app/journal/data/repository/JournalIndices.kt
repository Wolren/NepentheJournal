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

import app.journal.model.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.journal.util.PlatformLock


/**
 * The nine incremental query indices plus their maintenance and read helpers,
 * split out of JournalRepository in the wave2 structural refactor. All
 * mutation helpers assume the caller holds the facade lock.
 */
internal class JournalIndices(
    private val lock: PlatformLock,
    private val sessionsStore: EntityStore<Session>,
    private val dosesStore: EntityStore<Dose>,
    private val notesStore: EntityStore<Note>,
    private val timelineEventsStore: EntityStore<TimelineEvent>,
    private val effectsStore: EntityStore<Effect>,
    private val customUnitsStore: EntityStore<CustomUnit>,
    private val doses: Flow<List<Dose>>,
    private val substances: Flow<List<Substance>>,
    private val rebuildSearchIndexLocked: () -> Unit,
) {
    /**
     * Doses of one substance as a flow, deduplicated: edits to other
     * substances re-emit the source list but leave this filtered list equal,
     * so collectors see no emission (distinctUntilChanged).
     */
    fun dosesForSubstance(substanceId: String): Flow<List<Dose>> =
        doses.map { list -> list.filter { it.substanceId == substanceId } }.distinctUntilChanged()

    /** Live id -> Substance map; emits only when membership or content changes. */
    val substancesById: Flow<Map<String, Substance>> =
        substances.map { list -> list.associateBy { it.id } }.distinctUntilChanged()

    /** Live id -> display name map; emits only on add/remove/rename. */
    val substanceNamesById: Flow<Map<String, String>> =
        substancesById.map { byId -> byId.mapValues { (_, substance) -> substance.name } }
            .distinctUntilChanged()

    // ---- Session-child indexes (incrementally updated) ----
    internal val _dosesBySession = mutableMapOf<String, MutableList<Dose>>()
    internal val _notesBySession = mutableMapOf<String, MutableList<Note>>()
    internal val _eventsBySession = mutableMapOf<String, MutableList<TimelineEvent>>()

    // ---- Precomputed query indices ----
    internal val _sessionsByDate = mutableMapOf<LocalDate, MutableList<String>>()
    internal val _sessionsPerSubstance = mutableMapOf<String, MutableSet<String>>()
    /** substanceId -> list of effects that reference this substance */
    internal val _effectsBySubstance = mutableMapOf<String, MutableList<Effect>>()
    /** substanceId -> list of custom units */
    internal val _customUnitsBySubstance = mutableMapOf<String, MutableList<CustomUnit>>()

    /**
     * Precomputed dose stats per substance.
     * (distinctSessionCount, lastUsedTimestamp). Updated incrementally on dose mutations.
     */
    val substanceDoseStats: Map<String, Pair<Int, Long>>
        get() = lock.withLock { _substanceDoseStats.toMap() }
    internal val _substanceDoseStats = mutableMapOf<String, Pair<Int, Long>>()
    internal val _doseStatsSessionIds = mutableMapOf<String, MutableSet<String>>()

    // ========================
    //  Query index helpers
    // ========================

    private fun sessionDate(session: Session): LocalDate =
        Instant.fromEpochMilliseconds(session.startTime)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date

    internal fun addSessionToIndices(session: Session) {
        val date = sessionDate(session)
        _sessionsByDate.getOrPut(date) { mutableListOf() }.add(session.id)
    }

    internal fun removeSessionFromIndices(session: Session) {
        val date = sessionDate(session)
        _sessionsByDate[date]?.remove(session.id)
        if (_sessionsByDate[date]?.isEmpty() == true) _sessionsByDate.remove(date)
    }

    internal fun rebuildAllIndices() {
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
        rebuildSearchIndexLocked()
    }

    /** Incrementally update precomputed dose stats for a substance - counts distinct sessions only. */
    internal fun updateDoseStatsForSubstance(substanceId: String, sessionId: String, timestamp: Long) {
        val ids = _doseStatsSessionIds.getOrPut(substanceId) { mutableSetOf() }
        val isNew = ids.add(sessionId)
        val prev = _substanceDoseStats[substanceId]?.first ?: 0
        val count = if (isNew) prev + 1 else prev
        val lastUsed = maxOf(_substanceDoseStats[substanceId]?.second ?: 0L, timestamp)
        _substanceDoseStats[substanceId] = Pair(count, lastUsed)
    }

    /** Recompute dose stats for a single substance from scratch. */
    internal fun rebuildSubstanceDoseStats(substanceId: String) {
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

    /**
     * Drop one dose from the per-session list and the per-substance session set.
     * The session is only forgotten when no OTHER dose of the same substance still
     * lives there: the previous unconditional removal dropped live sessions from
     * [sessionIdsForSubstance] whenever a session held two doses of one substance,
     * and a re-parent left the old session behind. Callers must hold [lock].
     */
    internal fun removeDoseFromIndicesLocked(dose: Dose) {
        val sessionDoses = _dosesBySession[dose.sessionId]
        sessionDoses?.removeAll { it.id == dose.id }
        val sessionStillUsed = sessionDoses?.any { it.substanceId == dose.substanceId } == true
        if (!sessionStillUsed) {
            val sessions = _sessionsPerSubstance[dose.substanceId]
            sessions?.remove(dose.sessionId)
            if (sessions != null && sessions.isEmpty()) _sessionsPerSubstance.remove(dose.substanceId)
            if (sessionDoses != null && sessionDoses.isEmpty()) _dosesBySession.remove(dose.sessionId)
        }
    }

    fun dosesForSession(sessionId: String): List<Dose> =
        lock.withLock { _dosesBySession[sessionId]?.toList() ?: emptyList() }

    fun effectsForSubstance(substanceId: String): List<Effect> =
        lock.withLock { _effectsBySubstance[substanceId]?.toList() ?: emptyList() }

    fun customUnitsForSubstance(substanceId: String): List<CustomUnit> = lock.withLock {
        _customUnitsBySubstance[substanceId]?.toList() ?: emptyList()
    }

    fun notesForSession(sessionId: String): List<Note> =
        lock.withLock { _notesBySession[sessionId]?.toList() ?: emptyList() }

    fun eventsForSession(sessionId: String): List<TimelineEvent> =
        lock.withLock { (_eventsBySession[sessionId] ?: emptyList()).sortedBy { it.timestamp } }

    fun sessionIdsOnDateRange(fromDate: String?, toDate: String?): List<String> = lock.withLock {
        val from = fromDate?.let { LocalDate.parse(it) }
        val to = toDate?.let { LocalDate.parse(it) }
        _sessionsByDate.entries
            .filter { (date, _) ->
                (from == null || date >= from) && (to == null || date <= to)
            }
            .sortedBy { (date, _) -> date }
            .flatMap { (_, ids) -> ids }
    }

    fun sessionIdsForSubstance(substanceId: String): List<String> =
        lock.withLock { _sessionsPerSubstance[substanceId]?.toList() ?: emptyList() }

    fun sessionIdsForSubstances(substanceIds: Set<String>): Set<String> = lock.withLock {
        buildSet {
            for (subId in substanceIds) {
                _sessionsPerSubstance[subId]?.let { addAll(it) }
            }
        }
    }

    fun rebuildIndices() = lock.withLock { rebuildAllIndices() }

    /** Clear every incremental index. Callers must hold [lock]. */
    internal fun clearAllLocked() {
        _effectsBySubstance.clear()
        _customUnitsBySubstance.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _substanceDoseStats.clear()
        _doseStatsSessionIds.clear()
    }
}
