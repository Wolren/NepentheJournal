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
import app.journal.util.PlatformLock


/**
 * Sync and snapshot bulk-apply paths plus the single note conflict merge,
 * split out of JournalRepository in the wave2 structural refactor. Shares
 * the facade lock: every method synchronizes on the SAME monitor.
 */
internal class JournalSyncBridge(
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
    private val tombstones: JournalTombstones,
    private val markSearchIndexDirtyLocked: () -> Unit,
    private val bumpToleranceVersion: () -> Unit,
    private val bumpMutationCount: () -> Unit,
) {
    val pendingConflictCount: Flow<Int> = notesStore.flow.map { list ->
        list.count { it.conflictSiblings.isNotEmpty() }
    }

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
    fun applyBatch(
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
        val tombstonesChanged = tombstones.applyTombstonesLocked(
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
            // Search freshness for sync applies: rebuildAllIndices() below
            // also rebuilds the search index eagerly (JournalIndices ends
            // with rebuildSearchIndexLocked), and the explicit dirty flip
            // keeps the lazily-rebuilt path correct even if that eager
            // rebuild ever moves or stops covering search. Set under the
            // same lock as the rest of the bridge (callers hold [lock]);
            // O(1), never a rebuild by itself.
            indices.rebuildAllIndices()
            markSearchIndexDirtyLocked()
        }
        // Mirror the per-entity tolerance invalidation exactly once per batch: upsertDose
        // and upsertSubstance bump per item, tombstone deletes already bump through their
        // per-entity helpers (deleteDoseLocked / deleteSubstanceLocked).
        if (dosesToPut.isNotEmpty() || substancesToPut.isNotEmpty()) {
            bumpToleranceVersion()
        }
        bumpMutationCount()
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
    fun applySnapshot(snapshot: JournalSnapshot) = lock.withLock {
        tombstones.mergeSnapshotTombstonesLocked(snapshot)
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
        if (changed) {
            // Same freshness rule as applyBatch: rebuildAllIndices also
            // rebuilds search eagerly, and the explicit O(1) dirty flip
            // (callers hold [lock]) keeps the lazy path correct regardless.
            indices.rebuildAllIndices()
            markSearchIndexDirtyLocked()
        }
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

    fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note? = lock.withLock {
        val sessionId = note.sessionId ?: return@withLock null
        val existing = notesStore.get(note.id)
        val resolved = if (existing == null) note else mergeNoteConflictLocked(existing, note, remoteDeviceId)
        notesStore.put(resolved)
        // Re-index under the STORED note's session: when the local copy wins the
        // merge its sessionId may differ from the incoming note's.
        existing?.sessionId?.let { oldSessionId ->
            indices._notesBySession[oldSessionId]?.removeAll { n -> n.id == note.id }
        }
        resolved.sessionId?.let { newSessionId ->
            indices._notesBySession.getOrPut(newSessionId) { mutableListOf() }.add(resolved)
        }
        bumpMutationCount()
        markSearchIndexDirtyLocked()
        resolved
    }
}
