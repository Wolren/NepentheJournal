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
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis
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

    // ---- Preferences ----
    private val _ratingScaleMode = MutableStateFlow(RatingScaleMode.OFF)
    override val ratingScaleMode: StateFlow<RatingScaleMode> = _ratingScaleMode.asStateFlow()

    private val _useSubstanceColors = MutableStateFlow(true)
    override val useSubstanceColors: StateFlow<Boolean> = _useSubstanceColors.asStateFlow()

    private val _welcomeCompleted = MutableStateFlow(false)
    override val welcomeCompleted: StateFlow<Boolean> = _welcomeCompleted.asStateFlow()

    private val _seedFingerprint = MutableStateFlow<String?>(null)
    override val seedFingerprint: StateFlow<String?> = _seedFingerprint.asStateFlow()

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

    /**
     * Doses of one substance as a flow, deduplicated: edits to other
     * substances re-emit the source list but leave this filtered list equal,
     * so collectors see no emission (distinctUntilChanged).
     */
    override fun dosesForSubstance(substanceId: String): Flow<List<Dose>> =
        doses.map { list -> list.filter { it.substanceId == substanceId } }.distinctUntilChanged()

    /** Live id -> Substance map; emits only when membership or content changes. */
    override val substancesById: Flow<Map<String, Substance>> =
        substances.map { list -> list.associateBy { it.id } }.distinctUntilChanged()

    /** Live id -> display name map; emits only on add/remove/rename. */
    override val substanceNamesById: Flow<Map<String, String>> =
        substancesById.map { byId -> byId.mapValues { (_, substance) -> substance.name } }
            .distinctUntilChanged()

    // ---- Session-child indexes (incrementally updated) ----
    private val _dosesBySession = mutableMapOf<String, MutableList<Dose>>()
    private val _notesBySession = mutableMapOf<String, MutableList<Note>>()
    private val _eventsBySession = mutableMapOf<String, MutableList<TimelineEvent>>()

    // ---- Full-text search index ----
    val searchIndex = SearchIndex()
    /**
     * Set by every single-entity mutation, cleared by
     * [rebuildSearchIndexLocked]. Mutations only flip this bit (O(1) under
     * the repo lock) instead of tokenizing every entity; [search] rebuilds
     * lazily before querying so results are never stale, and the auto-save
     * quiet-period tail rebuilds once per burst of edits. Guarded by [lock].
     */
    private var searchIndexDirty = true

    // ========================
    //  Tombstones (deleted IDs pending propagation to peers)
    // ========================

    /** Tombstone retention: deletes older than 30 days stop propagating. */
    private val tombstoneRetentionMs = 30L * 86_400_000L

    /** Deleted entity tombstones: "type:id" to deletion timestamp. Guarded by [lock]. */
    private val _tombstones = mutableMapOf<String, Long>()

    private fun tombKey(type: String, id: String) = "$type:$id"

    /** Record a deletion for propagation. Callers must hold [lock]. */
    private fun recordTombstone(type: String, id: String) {
        pruneTombstonesLocked()
        _tombstones[tombKey(type, id)] = currentTimeMillis()
    }

    private fun pruneTombstonesLocked() {
        val cutoff = currentTimeMillis() - tombstoneRetentionMs
        val stale = _tombstones.filterValues { it < cutoff }.keys.toList()
        for (key in stale) _tombstones.remove(key)
    }

    override fun deletedIdsSince(since: Long): DeletedIds {
        val sessions = mutableListOf<String>()
        val doses = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val substances = mutableListOf<String>()
        val effects = mutableListOf<String>()
        val interactions = mutableListOf<String>()
        val timelineEvents = mutableListOf<String>()
        val customUnits = mutableListOf<String>()
        lock.withLock {
            for ((key, deletedAt) in _tombstones) {
                if (deletedAt <= since) continue
                val id = key.substringAfter(":")
                when (key.substringBefore(":")) {
                    "session" -> sessions.add(id)
                    "dose" -> doses.add(id)
                    "note" -> notes.add(id)
                    "substance" -> substances.add(id)
                    "effect" -> effects.add(id)
                    "interaction" -> interactions.add(id)
                    "timelineEvent" -> timelineEvents.add(id)
                    "customUnit" -> customUnits.add(id)
                }
            }
        }
        return DeletedIds(sessions, doses, notes, substances, effects, interactions, timelineEvents, customUnits)
    }

    override fun exportTombstones(): Map<String, Long> = lock.withLock { _tombstones.toMap() }

    override fun importTombstones(tombstones: Map<String, Long>) = lock.withLock {
        _tombstones.clear()
        val cutoff = currentTimeMillis() - tombstoneRetentionMs
        for ((key, deletedAt) in tombstones) {
            if (deletedAt >= cutoff) _tombstones[key] = deletedAt
        }
    }

    /**
     * Apply incoming tombstones. Deletes each listed local entity unless the
     * local copy is newer than [cutoff] (a concurrent update wins; pass 0 to
     * delete unconditionally for live deltas). Returns true if anything was
     * deleted. Callers must hold [lock].
     */
    private fun applyTombstonesLocked(deleted: DeletedIds, cutoff: Long): Boolean {
        var changed = false
        fun <T> applyIds(ids: List<String>, get: (String) -> T?, updatedAt: (T) -> Long, delete: (String) -> Unit) {
            for (id in ids) {
                val existing = get(id) ?: continue
                if (cutoff == 0L || updatedAt(existing) <= cutoff) {
                    delete(id)
                    changed = true
                }
            }
        }
        applyIds(deleted.deletedSessionIds, sessionsStore::get, { it.updatedAt }, ::deleteSessionLocked)
        applyIds(deleted.deletedDoseIds, dosesStore::get, { it.updatedAt }, ::deleteDoseLocked)
        applyIds(deleted.deletedNoteIds, notesStore::get, { it.updatedAt }, ::deleteNoteLocked)
        applyIds(deleted.deletedSubstanceIds, substancesStore::get, { it.updatedAt }, ::deleteSubstanceLocked)
        applyIds(deleted.deletedEffectIds, effectsStore::get, { it.updatedAt }, ::deleteEffectLocked)
        applyIds(deleted.deletedInteractionIds, interactionsStore::get, { it.updatedAt }, ::deleteInteractionLocked)
        applyIds(deleted.deletedTimelineEventIds, timelineEventsStore::get, { it.updatedAt }, ::deleteTimelineEventLocked)
        applyIds(deleted.deletedCustomUnitIds, customUnitsStore::get, { it.updatedAt }, ::deleteCustomUnitLocked)
        return changed
    }

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

    /**
     * Bulk-apply entities from a sync delta: single emissions per store, single
     * mutation bump, one shared putAll pass ([bulkPutLocked]).
     *
     * @param lastWriterWins when true, incoming entities whose `updatedAt` is older than
     * the existing record are skipped (last-writer-wins by timestamp). Sync paths MUST pass
     * true: without it, a replayed response or a stale peer push silently rolls back newer
     * local data (audit M3). Seed loading and backup restore keep the default false so
     * "Reset to defaults" / restore remain authoritative. Notes are the exception: on a
     * sync path they route through the same conflict merge as upsertNoteWithConflict so a
     * divergent edit keeps both bodies (contract c).
     * @param persons device-local snapshot-only entities (never synced, no tombstones).
     * Sync deltas leave this empty; snapshot loads pass the stored list.
     */
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
    ) = lock.withLock {
        fun <T> newer(list: List<T>, get: (String) -> T?, id: (T) -> String, updatedAt: (T) -> Long): List<T> =
            if (!lastWriterWins) list
            else list.filter { incoming ->
                val existing = get(id(incoming))
                existing == null || updatedAt(incoming) >= updatedAt(existing)
            }
        val sessionsToPut = newer(sessions, sessionsStore::get, { it.id }, { it.updatedAt })
        val dosesToPut = newer(doses, dosesStore::get, { it.id }, { it.updatedAt })
        val substancesToPut = newer(substances, substancesStore::get, { it.id }, { it.updatedAt })
        val effectsToPut = newer(effects, effectsStore::get, { it.id }, { it.updatedAt })
        val interactionsToPut = newer(interactions, interactionsStore::get, { it.id }, { it.updatedAt })
        val timelineEventsToPut = newer(timelineEvents, timelineEventsStore::get, { it.id }, { it.updatedAt })
        val customUnitsToPut = newer(customUnits, customUnitsStore::get, { it.id }, { it.updatedAt })
        // Notes on a sync (LWW) path never blind-overwrite: each incoming note goes
        // through the SAME merge as upsertNoteWithConflict, so the losing body is
        // preserved as a ConflictSibling. The incoming note's own deviceOrigin is the
        // batch path's remote attribution (a batch carries no sender id). An identical
        // body older than the stored one is dropped so updatedAt cannot regress; an
        // unchanged merged result (idempotent replay) is dropped as a no-op.
        // Seed/restore paths (lastWriterWins = false) stay authoritative blind writes.
        val notesToPut: List<Note> =
            if (!lastWriterWins) notes
            else notes.mapNotNull { incoming ->
                val existing = notesStore.get(incoming.id) ?: return@mapNotNull incoming
                val merged = mergeNoteConflictLocked(existing, incoming, incoming.deviceOrigin)
                if (merged == existing || merged.updatedAt < existing.updatedAt) null else merged
            }
        // Persons are device-local (never synced, no tombstones): snapshot loads only.
        val personsToPut = newer(persons, personsStore::get, { it.id }, { it.updatedAt })
        val anyIndexedPut = bulkPutLocked(
            sessions = sessionsToPut,
            substances = substancesToPut,
            doses = dosesToPut,
            notes = notesToPut,
            timelineEvents = timelineEventsToPut,
            interactions = interactionsToPut,
            effects = effectsToPut,
            customUnits = customUnitsToPut,
            persons = personsToPut,
        )
        val tombstonesChanged = applyTombstonesLocked(
            DeletedIds(
                deletedSessionIds, deletedDoseIds, deletedNoteIds, deletedSubstanceIds,
                deletedEffectIds, deletedInteractionIds, deletedTimelineEventIds, deletedCustomUnitIds
            ),
            tombstoneCutoff
        )
        // Rebuild all indices after bulk upsert to handle updates to existing entities
        // where old index entries (dates, per-session children) need to be replaced.
        // Persons need no rebuild: they back no query index.
        if (anyIndexedPut || tombstonesChanged) {
            rebuildAllIndices()
        }
        // Mirror the per-entity tolerance invalidation exactly once per batch: upsertDose
        // and upsertSubstance bump per item, tombstone deletes already bump through their
        // per-entity helpers (deleteDoseLocked / deleteSubstanceLocked).
        if (dosesToPut.isNotEmpty() || substancesToPut.isNotEmpty()) {
            bumpToleranceVersion()
        }
        bumpMutationCount()
    }

    override fun fullSnapshot(): JournalSnapshot = lock.withLock {
        AppJson.snapshot(this)
    }

    /**
     * Apply a full snapshot (bundled seed load, "reset with test data").
     * Entity lists merge over current state (putAll by id).
     *
     * Two kinds of local state are PRESERVED instead of being overwritten by the
     * snapshot (audit "tombstone-pref-wipe"): recorded tombstones (union with the
     * snapshot's, newest timestamp wins, both retention-filtered, so a seed apply
     * cannot forget pending deletes) and the user preference flows (ratingScaleMode,
     * substanceColors, welcomeCompleted are left untouched). The snapshot's own
     * preference fields are deliberately ignored here: the only production caller is
     * the bundled-seed apply, which must never rewrite user prefs. Disk restore goes
     * through AppJson.apply, which still restores prefs and replaces tombstones via
     * importTombstones.
     */
    override fun applySnapshot(snapshot: JournalSnapshot) = lock.withLock {
        pruneTombstonesLocked()
        val tombCutoff = currentTimeMillis() - tombstoneRetentionMs
        for ((key, deletedAt) in snapshot.tombstones) {
            if (deletedAt < tombCutoff) continue
            val current = _tombstones[key]
            if (current == null || deletedAt > current) _tombstones[key] = deletedAt
        }
        val changed = bulkPutLocked(
            sessions = snapshot.sessions,
            substances = snapshot.substances,
            doses = snapshot.doses,
            notes = snapshot.notes,
            timelineEvents = snapshot.timelineEvents,
            interactions = snapshot.interactions,
            effects = snapshot.effects,
            customUnits = snapshot.customUnits,
            persons = snapshot.persons,
        )
        if (changed) rebuildAllIndices()
    }

    /**
     * Shared putAll pass used by [applyBatch] and [applySnapshot]: one StateFlow
     * emission per store, no per-entity index work. Returns true when any entity
     * that backs a query index was written; callers must run [rebuildAllIndices]
     * when it returns true. Persons are device-local and back no index, so they
     * never flip the result. Tombstone policy stays with each caller: applyBatch
     * applies peer deletes under a cutoff, applySnapshot merges them.
     * Callers must hold [lock].
     */
    private fun bulkPutLocked(
        sessions: List<Session>,
        substances: List<Substance>,
        doses: List<Dose>,
        notes: List<Note>,
        timelineEvents: List<TimelineEvent>,
        interactions: List<Interaction>,
        effects: List<Effect>,
        customUnits: List<CustomUnit>,
        persons: List<Person>,
    ): Boolean {
        var indexedChanged = false
        fun <T> put(store: EntityStore<T>, items: List<T>) {
            if (items.isEmpty()) return
            store.putAll(items)
            indexedChanged = true
        }
        put(sessionsStore, sessions)
        put(substancesStore, substances)
        put(dosesStore, doses)
        put(notesStore, notes)
        put(timelineEventsStore, timelineEvents)
        put(interactionsStore, interactions)
        put(effectsStore, effects)
        put(customUnitsStore, customUnits)
        if (persons.isNotEmpty()) personsStore.putAll(persons)
        return indexedChanged
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
        rebuildSearchIndexLocked()
    }

    /** Incrementally update precomputed dose stats for a substance - counts distinct sessions only. */
    private fun updateDoseStatsForSubstance(substanceId: String, sessionId: String, timestamp: Long) {
        val ids = _doseStatsSessionIds.getOrPut(substanceId) { mutableSetOf() }
        val isNew = ids.add(sessionId)
        val prev = _substanceDoseStats[substanceId]?.first ?: 0
        val count = if (isNew) prev + 1 else prev
        val lastUsed = maxOf(_substanceDoseStats[substanceId]?.second ?: 0L, timestamp)
        _substanceDoseStats[substanceId] = Pair(count, lastUsed)
    }

    // ========================
    //  Individuals (device-local, never synced, no tombstones)
    // ========================

    override fun upsertPerson(person: Person) = lock.withLock {
        personsStore.put(person)
        bumpMutationCount()
    }

    override fun getPerson(id: String): Person? = lock.withLock { personsStore.get(id) }

    override fun deletePerson(id: String) = lock.withLock { personsStore.remove(id); bumpMutationCount() }

    // ========================
    //  Sessions
    // ========================

    override fun upsertSession(session: Session) = lock.withLock {
        val oldSession = sessionsStore.put(session)
        if (oldSession != null) removeSessionFromIndices(oldSession)
        addSessionToIndices(session)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    override fun getSession(id: String): Session? = lock.withLock { sessionsStore.get(id) }

    override fun toggleFavorite(sessionId: String) = lock.withLock {
        val session = sessionsStore.get(sessionId) ?: return@withLock
        val oldSession = sessionsStore.put(
            session.copy(isFavorite = !session.isFavorite, updatedAt = currentTimeMillis()),
        )
        if (oldSession != null) removeSessionFromIndices(oldSession)
        addSessionToIndices(sessionsStore.get(sessionId)!!)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    override fun deleteSession(id: String) = lock.withLock { deleteSessionLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteSessionLocked(id: String) {
        val session = sessionsStore.get(id) ?: return
        sessionsStore.remove(id)
        removeSessionFromIndices(session)
        recordTombstone("session", id)

        // Batch-remove child entities with single emissions per store
        val removedDoses = dosesStore.removeWhere { it.sessionId == id }
        for (dose in removedDoses) recordTombstone("dose", dose.id)
        val removedSubstances = removedDoses.map { it.substanceId }.toSet()

        _dosesBySession.remove(id)
        _notesBySession.remove(id)
        _eventsBySession.remove(id)

        val removedNotes = notesStore.removeWhere { it.sessionId == id }
        for (note in removedNotes) recordTombstone("note", note.id)
        val removedEvents = timelineEventsStore.removeWhere { it.sessionId == id }
        for (event in removedEvents) recordTombstone("timelineEvent", event.id)

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

    /**
     * Drop one dose from the per-session list and the per-substance session set.
     * The session is only forgotten when no OTHER dose of the same substance still
     * lives there: the previous unconditional removal dropped live sessions from
     * [sessionIdsForSubstance] whenever a session held two doses of one substance,
     * and a re-parent left the old session behind. Callers must hold [lock].
     */
    private fun removeDoseFromIndicesLocked(dose: Dose) {
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

    override fun upsertDose(dose: Dose) = lock.withLock {
        val prev = dosesStore.put(dose)

        if (prev != null) {
            // Re-parent / replace cleanup (audit: a sessionId change skipped index and
            // stat maintenance): drop the PREVIOUS index rows first, then recompute
            // this substance's stats from the store that already holds the final dose
            // (a moved session or a lowered timestamp can shrink them, which a running
            // incremental max can never do).
            removeDoseFromIndicesLocked(prev)
            rebuildSubstanceDoseStats(prev.substanceId)
            if (dose.substanceId != prev.substanceId) {
                rebuildSubstanceDoseStats(dose.substanceId)
            }
        } else {
            // Brand-new dose: incremental add is exact (distinct sessions + max ts).
            updateDoseStatsForSubstance(dose.substanceId, dose.sessionId, dose.timestamp)
        }
        _dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)
        _sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)

        bumpToleranceVersion()
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    override fun dosesForSession(sessionId: String): List<Dose> =
        lock.withLock { _dosesBySession[sessionId]?.toList() ?: emptyList() }

    override fun deleteDose(id: String) = lock.withLock { deleteDoseLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteDoseLocked(id: String) {
        val removed = dosesStore.remove(id) ?: return
        recordTombstone("dose", id)
        removeDoseFromIndicesLocked(removed)
        rebuildSubstanceDoseStats(removed.substanceId)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    override fun deleteNote(id: String) = lock.withLock { deleteNoteLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteNoteLocked(id: String) {
        val removed = notesStore.remove(id) ?: return
        recordTombstone("note", id)
        removed.sessionId?.let { _notesBySession[it]?.removeAll { n -> n.id == id } }
        bumpMutationCount()
    }

    override fun deleteTimelineEvent(id: String) = lock.withLock { deleteTimelineEventLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteTimelineEventLocked(id: String) {
        val removed = timelineEventsStore.remove(id) ?: return
        recordTombstone("timelineEvent", id)
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
        markSearchIndexDirtyLocked()
    }

    override fun getSubstance(id: String): Substance? = lock.withLock { substancesStore.get(id) }

    override fun deleteSubstance(id: String) = lock.withLock { deleteSubstanceLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteSubstanceLocked(id: String) {
        substancesStore.remove(id)
        recordTombstone("substance", id)

        // Cascade: remove all child entities for this substance
        val removedUnits = customUnitsStore.removeWhere { it.substanceId == id }
        for (unit in removedUnits) recordTombstone("customUnit", unit.id)
        _customUnitsBySubstance.remove(id)

        // Clean up effect index entries referencing this substance
        effectsStore.forEachValue { effect ->
            if (id in effect.substanceIds) {
                _effectsBySubstance[id]?.removeAll { it.id == effect.id }
            }
        }
        _effectsBySubstance.remove(id)

        // Clean up interactions referencing this substance
        val removedInteractions = interactionsStore.removeWhere {
            id in listOf(it.substanceAId, it.substanceBId)
        }
        for (interaction in removedInteractions) recordTombstone("interaction", interaction.id)

        val removedSubstanceDoses = dosesStore.removeWhere { it.substanceId == id }
        for (dose in removedSubstanceDoses) recordTombstone("dose", dose.id)
        val affectedSessionIds = removedSubstanceDoses.map { it.sessionId }.toSet()
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
        markSearchIndexDirtyLocked()
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
        markSearchIndexDirtyLocked()
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
        markSearchIndexDirtyLocked()
    }

    override fun deleteCustomUnit(id: String) = lock.withLock { deleteCustomUnitLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteCustomUnitLocked(id: String) {
        val removed = customUnitsStore.remove(id) ?: return
        recordTombstone("customUnit", id)
        _customUnitsBySubstance[removed.substanceId]?.removeAll { it.id == id }
        if (_customUnitsBySubstance[removed.substanceId]?.isEmpty() == true)
            _customUnitsBySubstance.remove(removed.substanceId)
        bumpMutationCount()
    }

    override fun customUnitsForSubstance(substanceId: String): List<CustomUnit> = lock.withLock {
        _customUnitsBySubstance[substanceId]?.toList() ?: emptyList()
    }

    override fun deleteEffect(id: String) = lock.withLock { deleteEffectLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteEffectLocked(id: String) {
        val removed = effectsStore.remove(id) ?: return
        for (subId in removed.substanceIds) {
            _effectsBySubstance[subId]?.removeAll { it.id == id }
            if (_effectsBySubstance[subId]?.isEmpty() == true)
                _effectsBySubstance.remove(subId)
        }
        recordTombstone("effect", id)
        bumpMutationCount()
    }

    override fun deleteInteraction(id: String) = lock.withLock { deleteInteractionLocked(id); markSearchIndexDirtyLocked() }

    private fun deleteInteractionLocked(id: String) {
        interactionsStore.remove(id) ?: return
        recordTombstone("interaction", id)
        bumpMutationCount()
    }

    // ========================
    //  Preferences
    // ========================

    override fun setRatingScaleMode(mode: RatingScaleMode) = lock.withLock {
        _ratingScaleMode.value = mode
        bumpMutationCount()
    }

    override fun setSubstanceColors(enabled: Boolean) = lock.withLock {
        _useSubstanceColors.value = enabled
    }

    override fun setWelcomeCompleted(completed: Boolean) = lock.withLock {
        _welcomeCompleted.value = completed
        bumpMutationCount()
    }

    override fun setSeedFingerprint(fingerprint: String?) = lock.withLock {
        _seedFingerprint.value = fingerprint
        bumpMutationCount()
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
        markSearchIndexDirtyLocked()
    }

    /**
     * The ONE conflict merge for every note apply path (contract c,
     * HARDENING-CONTRACTS-2026-09 section c): the losing body is NEVER destroyed.
     *
     * - Bodies equal: incoming wins, carrying the union of both conflictSiblings
     *   (deduplicated by body, so replays stay idempotent).
     * - Bodies differ: the higher updatedAt wins (tie: incoming) and the LOSING
     *   note's full body is preserved as
     *   ConflictSibling(body = loser.body, deviceOrigin = remoteDeviceId,
     *   updatedAt = loser.updatedAt). The winner keeps its own updatedAt, so the
     *   merge never inflates timestamps and LWW stays stable across peers.
     *
     * Used by [upsertNoteWithConflict] and the sync branch of [applyBatch].
     * Callers must hold [lock].
     */
    private fun mergeNoteConflictLocked(existing: Note, incoming: Note, remoteDeviceId: String): Note {
        if (existing.body == incoming.body) {
            val siblings = (existing.conflictSiblings + incoming.conflictSiblings)
                .distinctBy { it.body }
            return incoming.copy(conflictSiblings = siblings)
        }
        val incomingWins = incoming.updatedAt >= existing.updatedAt
        val winner = if (incomingWins) incoming else existing
        val loser = if (incomingWins) existing else incoming
        // Body already preserved: replaying the losing edit is a no-op.
        if (winner.conflictSiblings.any { it.body == loser.body }) return winner
        return winner.copy(
            conflictSiblings = winner.conflictSiblings +
                ConflictSibling(loser.body, remoteDeviceId, loser.updatedAt),
        )
    }

    override fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note? = lock.withLock {
        val sessionId = note.sessionId ?: return@withLock null
        val existing = notesStore.get(note.id)
        val resolved = if (existing == null) note else mergeNoteConflictLocked(existing, note, remoteDeviceId)
        notesStore.put(resolved)
        // Re-index under the STORED note's session: when the local copy wins the
        // merge its sessionId may differ from the incoming note's.
        existing?.sessionId?.let { oldSessionId ->
            _notesBySession[oldSessionId]?.removeAll { n -> n.id == note.id }
        }
        resolved.sessionId?.let { newSessionId ->
            _notesBySession.getOrPut(newSessionId) { mutableListOf() }.add(resolved)
        }
        bumpMutationCount()
        markSearchIndexDirtyLocked()
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
        markSearchIndexDirtyLocked()
    }

    override fun eventsForSession(sessionId: String): List<TimelineEvent> =
        lock.withLock { (_eventsBySession[sessionId] ?: emptyList()).sortedBy { it.timestamp } }

    /**
     * Batch upsert of one session's doses and timeline events (task: SessionEditor
     * save path). One putAll per store, ONE index rebuild ([rebuildAllIndices]
     * recomputes the per-session lists, per-substance session sets and dose stats in
     * a single pass), one tolerance bump when doses changed, one mutation bump.
     * Children whose sessionId differs from [sessionId] (draft-id rows of a
     * not-yet-saved session) are re-parented here. Final state is equivalent to
     * looping [upsertDose] / [upsertTimelineEvent], proven by
     * JournalRepositoryTest.sessionChildrenBatchMatchesPerItemUpserts.
     */
    override fun upsertSessionChildren(
        sessionId: String,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>
    ) = lock.withLock {
        val reparentedDoses = doses.map {
            if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
        }
        val reparentedEvents = timelineEvents.map {
            if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
        }
        if (reparentedDoses.isEmpty() && reparentedEvents.isEmpty()) return@withLock
        dosesStore.putAll(reparentedDoses)
        timelineEventsStore.putAll(reparentedEvents)
        rebuildAllIndices()
        if (reparentedDoses.isNotEmpty()) bumpToleranceVersion()
        bumpMutationCount()
    }

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

    override fun sessionIdsForSubstances(substanceIds: Set<String>): Set<String> = lock.withLock {
        buildSet {
            for (subId in substanceIds) {
                _sessionsPerSubstance[subId]?.let { addAll(it) }
            }
        }
    }

    override fun rebuildIndices() = lock.withLock { rebuildAllIndices() }

    // ========================
    //  DataFrame export
    // ========================

    override fun sessionsDataFrame(): List<SessionDataRow> = lock.withLock {
        val subNameCache = substancesStore.all.associate { it.id to it.name }
        sessionsStore.all.map { session ->
            val sessionDoses = _dosesBySession[session.id]?.toList() ?: emptyList()
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
            SessionBundle(session, _dosesBySession[session.id]?.toList() ?: emptyList())
        }
    }

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
                        rebuildSearchIndexIfDirty()
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
        _effectsBySubstance.clear()
        customUnitsStore.clear()
        personsStore.clear()
        _customUnitsBySubstance.clear()
        _tombstones.clear()
        _dosesBySession.clear()
        _notesBySession.clear()
        _eventsBySession.clear()
        _sessionsByDate.clear()
        _sessionsPerSubstance.clear()
        _substanceDoseStats.clear()
        _doseStatsSessionIds.clear()
        rebuildSearchIndexLocked()
        _ratingScaleMode.value = RatingScaleMode.OFF
        _useSubstanceColors.value = true
        _welcomeCompleted.value = false
        _seedFingerprint.value = null
        bumpToleranceVersion()
        bumpMutationCount()
    }

    // ========================
    //  Full-text search
    // ========================

    override fun search(query: String): List<SearchResult> = lock.withLock {
        // Rebuild-if-dirty before querying: mutations only flip the flag, so a
        // search right after an upsert/delete can never see a stale index.
        if (searchIndexDirty) rebuildSearchIndexLocked()
        searchIndex.search(query)
    }

    /** Rebuild the search index only when a mutation dirtied it. */
    private fun rebuildSearchIndexIfDirty() = lock.withLock {
        if (searchIndexDirty) rebuildSearchIndexLocked()
    }

    /** Flip the dirty bit instead of tokenizing every entity (callers hold [lock]). */
    private fun markSearchIndexDirtyLocked() {
        searchIndexDirty = true
    }

    private fun rebuildSearchIndexLocked() {
        searchIndex.rebuild(
            // EntityStore.all already returns a fresh snapshot per store: the old
            // extra .toList() copies were redundant.
            sessions = sessionsStore.all,
            substances = substancesStore.all,
            notes = notesStore.all,
            doses = dosesStore.all,
            timelineEvents = timelineEventsStore.all,
            effects = effectsStore.all,
            substanceNames = substancesStore.all.associate { it.id to it.name }
        )
        searchIndexDirty = false
    }

    override fun rebuildSearchIndex() = lock.withLock { rebuildSearchIndexLocked() }

    companion object {
        val instance: JournalRepository by lazy { JournalRepository() }
    }
}
