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
import app.journal.util.PlatformLock
import app.journal.util.currentTimeMillis


/**
 * Entity upsert CRUD for every journal store, split out of JournalRepository
 * in the wave2 structural refactor. Holds no state of its own: writes go
 * through the shared EntityStores and indices under the facade lock.
 */
internal class JournalMutations(
    private val lock: PlatformLock,
    private val sessionsStore: EntityStore<Session>,
    private val dosesStore: EntityStore<Dose>,
    private val substancesStore: EntityStore<Substance>,
    private val notesStore: EntityStore<Note>,
    private val timelineEventsStore: EntityStore<TimelineEvent>,
    private val interactionsStore: EntityStore<Interaction>,
    private val effectsStore: EntityStore<Effect>,
    private val customUnitsStore: EntityStore<CustomUnit>,
    private val personsStore: EntityStore<Person>,
    private val indices: JournalIndices,
    private val markSearchIndexDirtyLocked: () -> Unit,
    private val bumpToleranceVersion: () -> Unit,
    private val bumpMutationCount: () -> Unit,
) {
    fun upsertPerson(person: Person) = lock.withLock {
        personsStore.put(person)
        bumpMutationCount()
    }

    fun deletePerson(id: String) = lock.withLock { personsStore.remove(id); bumpMutationCount() }

    fun upsertSession(session: Session) = lock.withLock {
        val oldSession = sessionsStore.put(session)
        if (oldSession != null) indices.removeSessionFromIndices(oldSession)
        indices.addSessionToIndices(session)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun toggleFavorite(sessionId: String) = lock.withLock {
        val session = sessionsStore.get(sessionId) ?: return@withLock
        val oldSession = sessionsStore.put(
            session.copy(isFavorite = !session.isFavorite, updatedAt = currentTimeMillis()),
        )
        if (oldSession != null) indices.removeSessionFromIndices(oldSession)
        indices.addSessionToIndices(sessionsStore.get(sessionId)!!)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertDose(dose: Dose) = lock.withLock {
        val prev = dosesStore.put(dose)

        if (prev != null) {
            // Re-parent / replace cleanup (audit: a sessionId change skipped index and
            // stat maintenance): drop the PREVIOUS index rows first, then recompute
            // this substance's stats from the store that already holds the final dose
            // (a moved session or a lowered timestamp can shrink them, which a running
            // incremental max can never do).
            indices.removeDoseFromIndicesLocked(prev)
            indices.rebuildSubstanceDoseStats(prev.substanceId)
            if (dose.substanceId != prev.substanceId) {
                indices.rebuildSubstanceDoseStats(dose.substanceId)
            }
        } else {
            // Brand-new dose: incremental add is exact (distinct sessions + max ts).
            indices.updateDoseStatsForSubstance(dose.substanceId, dose.sessionId, dose.timestamp)
        }
        indices._dosesBySession.getOrPut(dose.sessionId) { mutableListOf() }.add(dose)
        indices._sessionsPerSubstance.getOrPut(dose.substanceId) { mutableSetOf() }.add(dose.sessionId)

        bumpToleranceVersion()
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertSubstance(substance: Substance) = lock.withLock {
        substancesStore.put(substance)
        bumpToleranceVersion()
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertInteraction(interaction: Interaction) = lock.withLock {
        interactionsStore.put(interaction)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertEffect(effect: Effect) = lock.withLock {
        val prev = effectsStore.put(effect)
        if (prev != null) {
            for (subId in prev.substanceIds) {
                indices._effectsBySubstance[subId]?.removeAll { it.id == effect.id }
                if (indices._effectsBySubstance[subId]?.isEmpty() == true)
                    indices._effectsBySubstance.remove(subId)
            }
        }
        for (subId in effect.substanceIds) {
            indices._effectsBySubstance.getOrPut(subId) { mutableListOf() }.add(effect)
        }
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertCustomUnit(unit: CustomUnit) = lock.withLock {
        val prev = customUnitsStore.put(unit)
        if (prev != null) {
            indices._customUnitsBySubstance[prev.substanceId]?.removeAll { it.id == unit.id }
            if (indices._customUnitsBySubstance[prev.substanceId]?.isEmpty() == true &&
                prev.substanceId != unit.substanceId)
                indices._customUnitsBySubstance.remove(prev.substanceId)
        }
        indices._customUnitsBySubstance.getOrPut(unit.substanceId) { mutableListOf() }.add(unit)
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertNote(note: Note) = lock.withLock {
        val prev = notesStore.put(note)
        val prevSessionId = prev?.sessionId
        // Remove previous entry from index to prevent duplicates on update
        if (prevSessionId != null) {
            indices._notesBySession[prevSessionId]?.removeAll { it.id == note.id }
        }
        note.sessionId?.let { sessionId ->
            indices._notesBySession.getOrPut(sessionId) { mutableListOf() }.add(note)
        }
        bumpMutationCount()
        markSearchIndexDirtyLocked()
    }

    fun upsertTimelineEvent(event: TimelineEvent) = lock.withLock {
        val prev = timelineEventsStore.put(event)
        val prevSessionId = prev?.sessionId
        // Remove previous entry from index to prevent duplicates on update
        if (prevSessionId != null) {
            indices._eventsBySession[prevSessionId]?.removeAll { it.id == event.id }
        }
        indices._eventsBySession.getOrPut(event.sessionId) { mutableListOf() }.add(event)
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
            if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
        }
        val reparentedEvents = timelineEvents.map {
            if (it.sessionId == sessionId) it else it.copy(sessionId = sessionId)
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
