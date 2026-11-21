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
import app.journal.data.repository.EntityClocks
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis


/**
 * Entity upsert CRUD for every journal store, split out of JournalRepository
 * in the wave2 structural refactor. Holds no state of its own: writes go
 * through the shared EntityStores and indices under the facade lock.
 */
internal class JournalMutations(
    private val lock: PlatformLock,
    private val clocks: EntityClocks,
    private val sessionsStore: EntityStore<Session>,
    private val dosesStore: EntityStore<Dose>,
    private val substancesStore: EntityStore<Substance>,
    private val notesStore: EntityStore<Note>,
    private val timelineEventsStore: EntityStore<TimelineEvent>,
    private val interactionsStore: EntityStore<Interaction>,
    private val effectsStore: EntityStore<Effect>,
    private val customUnitsStore: EntityStore<CustomUnit>,
    private val personsStore: EntityStore<Person>,
    private val medicationsStore: EntityStore<Medication>,
    private val indices: JournalIndices,
    private val markSearchIndexDirtyLocked: () -> Unit,
    private val bumpToleranceVersion: () -> Unit,
    private val bumpMutationCount: () -> Unit,
    private val bumpLibraryVersion: () -> Unit,
) {
    fun upsertPerson(person: Person) = lock.withLock {
        personsStore.put(person)
        bumpMutationCount()
    }

    fun deletePerson(id: String) = lock.withLock { personsStore.remove(id); bumpMutationCount() }

    fun upsertMedication(medication: Medication) = lock.withLock {
        medicationsStore.put(medication)
        bumpMutationCount()
    }

    fun deleteMedication(id: String) = lock.withLock {
        medicationsStore.remove(id)
        bumpMutationCount()
    }

    fun upsertSession(session: Session) = lock.withLock {
        putSessionLocked(session.copy(clock = clocks.next(session.updatedAt)))
    }

    /**
     * Sync-apply entry for a session received from a peer: the incoming stamp is
     * kept, never restamped, or the two peers would keep overwriting each other.
     */
    fun applyRemoteSession(session: Session) = lock.withLock {
        clocks.observe(session.clock)
        putSessionLocked(session)
    }

    private fun putSessionLocked(session: Session) {
        val oldSession = sessionsStore.put(session)
        if (oldSession != null) indices.removeSessionFromIndices(oldSession)
        indices.addSessionToIndices(session)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun toggleFavorite(sessionId: String) = lock.withLock {
        val session = sessionsStore.get(sessionId) ?: return@withLock
        val oldSession = sessionsStore.put(
            session.copy(
                isFavorite = !session.isFavorite,
                updatedAt = currentTimeMillis(),
                clock = clocks.next(),
            ),
        )
        if (oldSession != null) indices.removeSessionFromIndices(oldSession)
        indices.addSessionToIndices(sessionsStore.get(sessionId)!!)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertDose(dose: Dose) = lock.withLock {
        val stamped = dose.copy(clock = clocks.next(dose.updatedAt))
        val prev = dosesStore.put(stamped)

        if (prev != null) {
            indices.removeDoseFromIndicesLocked(prev)
            indices.rebuildSubstanceDoseStats(prev.substanceId)
            if (stamped.substanceId != prev.substanceId) {
                indices.rebuildSubstanceDoseStats(stamped.substanceId)
            }
        } else {
            indices.updateDoseStatsForSubstance(stamped.substanceId, stamped.sessionId, stamped.timestamp)
        }
        indices._dosesBySession.getOrPut(stamped.sessionId) { mutableListOf() }.add(stamped)
        indices._sessionsPerSubstance.getOrPut(stamped.substanceId) { mutableSetOf() }.add(stamped.sessionId)

        bumpToleranceVersion()
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertSubstance(substance: Substance) = lock.withLock {
        substancesStore.put(substance.copy(clock = clocks.next(substance.updatedAt)))
        bumpToleranceVersion()
        bumpMutationCount()
        bumpLibraryVersion()
        markSearchIndexDirtyLocked()
    }

    fun upsertInteraction(interaction: Interaction) = lock.withLock {
        interactionsStore.put(interaction.copy(clock = clocks.next(interaction.updatedAt)))
        bumpMutationCount()
        bumpLibraryVersion()
        markSearchIndexDirtyLocked()
    }

    fun upsertEffect(effect: Effect) = lock.withLock {
        val stamped = effect.copy(clock = clocks.next(effect.updatedAt))
        val prev = effectsStore.put(stamped)
        if (prev != null) {
            for (subId in prev.substanceIds) {
                indices._effectsBySubstance[subId]?.removeAll { it.id == stamped.id }
                if (indices._effectsBySubstance[subId]?.isEmpty() == true)
                    indices._effectsBySubstance.remove(subId)
            }
        }
        for (subId in stamped.substanceIds) {
            indices._effectsBySubstance.getOrPut(subId) { mutableListOf() }.add(stamped)
        }
        bumpMutationCount()
        bumpLibraryVersion()
        markSearchIndexDirtyLocked()
    }

    fun upsertCustomUnit(unit: CustomUnit) = lock.withLock {
        val stamped = unit.copy(clock = clocks.next(unit.updatedAt))
        val prev = customUnitsStore.put(stamped)
        if (prev != null) {
            indices._customUnitsBySubstance[prev.substanceId]?.removeAll { it.id == stamped.id }
            if (indices._customUnitsBySubstance[prev.substanceId]?.isEmpty() == true &&
                prev.substanceId != stamped.substanceId)
                indices._customUnitsBySubstance.remove(prev.substanceId)
        }
        indices._customUnitsBySubstance.getOrPut(stamped.substanceId) { mutableListOf() }.add(stamped)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertNote(note: Note) = lock.withLock {
        // A replayed conflict note arrives with identical content and a new updatedAt
        // only by stamping; skipping the no-op keeps conflict replay byte-identical.
        val stored = notesStore.get(note.id)
        if (stored != null && note.copy(clock = stored.clock) == stored) return@withLock
        val stamped = note.copy(clock = clocks.next(note.updatedAt))
        val prev = notesStore.put(stamped)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null) {
            indices._notesBySession[prevSessionId]?.removeAll { it.id == stamped.id }
        }
        stamped.sessionId?.let { sessionId ->
            indices._notesBySession.getOrPut(sessionId) { mutableListOf() }.add(stamped)
        }
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertTimelineEvent(event: TimelineEvent) = lock.withLock {
        val stamped = event.copy(clock = clocks.next(event.updatedAt))
        val prev = timelineEventsStore.put(stamped)
        val prevSessionId = prev?.sessionId
        if (prevSessionId != null) {
            indices._eventsBySession[prevSessionId]?.removeAll { it.id == stamped.id }
        }
        indices._eventsBySession.getOrPut(stamped.sessionId) { mutableListOf() }.add(stamped)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    /**
     * Batch upsert of one session's doses and timeline events (task: SessionEditor
     * save path). One putAll per store, ONE index rebuild ([rebuildAllIndices]
     * recomputes the per-session lists, per-substance session sets and dose stats in
     * a single pass), one tolerance bump when doses changed, one mutation bump.
     * Children whose sessionId differs from [sessionId] (draft-id rows of a
     * not-yet-saved session) are re-parented here. Final state is equivalent to
     * looping [upsertDose] / [upsertTimelineEvent], proven by
     * JournalRepositoryTest.sessionChildrenBatchMatchesPerItemUpserts.
     *
     * Search freshness: this path must flip the dirty bit itself (it writes
     * stores directly instead of going through the per-entity upserts that
     * already do). The flip is the F1 O(1) mechanism, so a batch save costs
     * one flag set for search, not a re-tokenization; the next query rebuilds
     * lazily. Before wave4 this flag was never set here, so a search right
     * after a batch save silently missed the new children.
     */
    fun upsertSessionChildren(
        sessionId: String,
        doses: List<Dose>,
        timelineEvents: List<TimelineEvent>
    ) = lock.withLock {
        val reparentedDoses = doses.map {
            val reparented = if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
            reparented.copy(clock = clocks.next(reparented.updatedAt))
        }
        val reparentedEvents = timelineEvents.map {
            val reparented = if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
            reparented.copy(clock = clocks.next(reparented.updatedAt))
        }
        if (reparentedDoses.isEmpty() && reparentedEvents.isEmpty()) return@withLock
        dosesStore.putAll(reparentedDoses)
        timelineEventsStore.putAll(reparentedEvents)
        indices.rebuildAllIndices()
        if (reparentedDoses.isNotEmpty()) bumpToleranceVersion()
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }
}
