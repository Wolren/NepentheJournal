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

import app.journal.data.repository.EntityClocks
import app.journal.data.repository.EntityOrder
import app.journal.data.repository.FieldRegisters

import app.journal.model.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.KSerializer
import app.journal.util.PlatformLock


/**
 * Sync and snapshot bulk-apply paths plus the single note conflict merge,
 * split out of JournalRepository in the wave2 structural refactor. Shares
 * the facade lock: every method synchronizes on the SAME monitor.
 */
internal class JournalSyncBridge(
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
    private val tombstones: JournalTombstones,
    private val markSearchIndexDirtyLocked: () -> Unit,
    private val bumpToleranceVersion: () -> Unit,
    private val bumpMutationCount: () -> Unit,
    private val bumpLibraryVersion: () -> Unit,
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
     * @param medications same device-local policy as persons: medical data stays on its device.
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
        medications: List<Medication>,
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
        // A delete outranks any earlier write, so a peer that missed it cannot resurrect the row.
        fun <T> alive(list: List<T>, type: String, id: (T) -> String, updatedAt: (T) -> Long): List<T> =
            if (!lastWriterWins) list
            else list.filter { incoming ->
                val deletedAt = tombstones.deletedAtOf(type, id(incoming))
                deletedAt == null || updatedAt(incoming) > deletedAt
            }
        fun <T> newer(
            list: List<T>,
            get: (String) -> T?,
            id: (T) -> String,
            updatedAt: (T) -> Long,
            clock: (T) -> String,
            device: (T) -> String,
        ): List<T> =
            if (!lastWriterWins) list
            else list.filter { incoming ->
                val existing = get(id(incoming)) ?: return@filter true
                EntityOrder.incomingWins(
                    updatedAt(incoming), clock(incoming), device(incoming),
                    updatedAt(existing), clock(existing), device(existing),
                )
            }
        fun <T : FieldVersioned> fieldMerge(
            list: List<T>,
            get: (String) -> T?,
            serializer: KSerializer<T>,
        ): List<T> = list.mapNotNull { incoming ->
            val existing = get(incoming.id) ?: return@mapNotNull incoming
            val merged = FieldRegisters.merge(existing, incoming, serializer)
            if (merged == existing) null else merged
        }
        fun observe(clock: String, fieldClocks: Map<String, String> = emptyMap()) {
            clocks.observe(clock)
            fieldClocks.values.forEach(clocks::observe)
        }
        // Receive step: every accepted stamp advances the local clock past the peer.
        sessions.forEach { observe(it.clock, it.fieldClocks) }
        doses.forEach { observe(it.clock, it.fieldClocks) }
        substances.forEach { clocks.observe(it.clock) }
        notes.forEach { observe(it.clock, it.fieldClocks) }
        timelineEvents.forEach { clocks.observe(it.clock) }
        interactions.forEach { clocks.observe(it.clock) }
        effects.forEach { clocks.observe(it.clock) }
        customUnits.forEach { clocks.observe(it.clock) }
        val sessionsToPut =
            if (!lastWriterWins) sessions
            else fieldMerge(alive(sessions, "session", { it.id }, { it.updatedAt }), sessionsStore::get, Session.serializer())
        val dosesToPut =
            if (!lastWriterWins) doses
            else fieldMerge(alive(doses, "dose", { it.id }, { it.updatedAt }), dosesStore::get, Dose.serializer())
        val substancesToPut = newer(alive(substances, "substance", { it.id }, { it.updatedAt }), substancesStore::get, { it.id }, { it.updatedAt }, { it.clock }, { it.deviceOrigin })
        val effectsToPut = newer(alive(effects, "effect", { it.id }, { it.updatedAt }), effectsStore::get, { it.id }, { it.updatedAt }, { it.clock }, { it.deviceOrigin })
        val interactionsToPut = newer(alive(interactions, "interaction", { it.id }, { it.updatedAt }), interactionsStore::get, { it.id }, { it.updatedAt }, { it.clock }, { it.deviceOrigin })
        val timelineEventsToPut = newer(alive(timelineEvents, "timelineEvent", { it.id }, { it.updatedAt }), timelineEventsStore::get, { it.id }, { it.updatedAt }, { it.clock }, { it.deviceOrigin })
        val customUnitsToPut = newer(alive(customUnits, "customUnit", { it.id }, { it.updatedAt }), customUnitsStore::get, { it.id }, { it.updatedAt }, { it.clock }, { it.deviceOrigin })
        // A no-op merge is skipped; the clock decides order once both sides carry one.
        val notesToPut: List<Note> =
            if (!lastWriterWins) notes
            else alive(notes, "note", { it.id }, { it.updatedAt }).mapNotNull { incoming ->
                val existing = notesStore.get(incoming.id) ?: return@mapNotNull incoming
                val merged = mergeNoteConflictLocked(existing, incoming, incoming.deviceOrigin)
                if (merged == existing) null else merged
            }
        val personsToPut = newer(persons, personsStore::get, { it.id }, { it.updatedAt }, { "" }, { it.deviceOrigin })
        val medicationsToPut = newer(medications, medicationsStore::get, { it.id }, { it.updatedAt }, { "" }, { it.deviceOrigin })
        val changedGroups = bulkPutLocked(
            sessions = sessionsToPut,
            substances = substancesToPut,
            doses = dosesToPut,
            notes = notesToPut,
            timelineEvents = timelineEventsToPut,
            interactions = interactionsToPut,
            effects = effectsToPut,
            customUnits = customUnitsToPut,
            persons = personsToPut,
            medications = medicationsToPut,
        )
        val tombstonesChanged = tombstones.applyTombstonesLocked(
            DeletedIds(
                deletedSessionIds, deletedDoseIds, deletedNoteIds, deletedSubstanceIds,
                deletedEffectIds, deletedInteractionIds, deletedTimelineEventIds, deletedCustomUnitIds
            ),
            tombstoneCutoff
        )
        if (changedGroups.isNotEmpty()) {
            // Rebuild only the families this apply fed; delete helpers update their own entries.
            indices.rebuildGroups(changedGroups)
            markSearchIndexDirtyLocked()
        }
        if (tombstonesChanged) markSearchIndexDirtyLocked()
        if (dosesToPut.isNotEmpty() || substancesToPut.isNotEmpty()) {
            bumpToleranceVersion()
        }
        if (substancesToPut.isNotEmpty() || effectsToPut.isNotEmpty() || interactionsToPut.isNotEmpty() ||
            deletedSubstanceIds.isNotEmpty() || deletedEffectIds.isNotEmpty() || deletedInteractionIds.isNotEmpty()
        ) {
            bumpLibraryVersion()
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
        snapshot.sessions.forEach { clocks.observe(it.clock); it.fieldClocks.values.forEach(clocks::observe) }
        snapshot.doses.forEach { clocks.observe(it.clock); it.fieldClocks.values.forEach(clocks::observe) }
        snapshot.substances.forEach { clocks.observe(it.clock) }
        snapshot.notes.forEach { clocks.observe(it.clock); it.fieldClocks.values.forEach(clocks::observe) }
        snapshot.timelineEvents.forEach { clocks.observe(it.clock) }
        snapshot.interactions.forEach { clocks.observe(it.clock) }
        snapshot.effects.forEach { clocks.observe(it.clock) }
        snapshot.customUnits.forEach { clocks.observe(it.clock) }
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
            medications = snapshot.medications,
        )
        if (changed.isNotEmpty()) {
            indices.rebuildAllIndices()
            markSearchIndexDirtyLocked()
        }
        if (snapshot.substances.isNotEmpty() || snapshot.interactions.isNotEmpty() || snapshot.effects.isNotEmpty()) {
            bumpLibraryVersion()
        }
    }

    /**
     * Shared putAll pass used by [applyBatch] and [applySnapshot]: one StateFlow
     * emission per store, no per-entity index work. Returns the index families
     * that need rebuilding for the entity types written; an empty set means no
     * index can have changed. Persons are device-local and back no index, so
     * they never contribute. Tombstone policy stays with each caller:
     * applyBatch applies peer deletes under a cutoff, applySnapshot merges them.
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
        medications: List<Medication>,
    ): Set<IndexGroup> {
        val groups = mutableSetOf<IndexGroup>()
        fun <T> put(store: EntityStore<T>, items: List<T>, group: IndexGroup?) {
            if (items.isEmpty()) return
            store.putAll(items)
            if (group != null) groups += group
        }
        put(sessionsStore, sessions, IndexGroup.SESSIONS_BY_DATE)
        // Substances, effects and interactions back no index family of their own.
        put(substancesStore, substances, null)
        put(dosesStore, doses, IndexGroup.DOSES)
        put(notesStore, notes, IndexGroup.NOTES)
        put(timelineEventsStore, timelineEvents, IndexGroup.EVENTS)
        put(interactionsStore, interactions, null)
        put(effectsStore, effects, IndexGroup.EFFECTS)
        put(customUnitsStore, customUnits, IndexGroup.CUSTOM_UNITS)
        if (persons.isNotEmpty()) personsStore.putAll(persons)
        if (medications.isNotEmpty()) medicationsStore.putAll(medications)
        return groups
    }


    /**
     * The ONE conflict merge for every note apply path (contract c): the losing body
     * is NEVER destroyed. Field registers merge title, sessionId, doseId and the rest
     * independently; `body` keeps the loser as a ConflictSibling.
     *
     * The sibling carries the loser's device (remoteDeviceId when the incoming note
     * loses) and the loser's updatedAt. The winner keeps its own updatedAt, so the
     * merge is idempotent and both peers choose the same body.
     *
     * Used by [upsertNoteWithConflict] and the sync branch of [applyBatch].
     * Callers must hold [lock].
     */
    private fun mergeNoteConflictLocked(existing: Note, incoming: Note, remoteDeviceId: String): Note {
        val merged = FieldRegisters.merge(existing, incoming, Note.serializer())
        val incomingWinsBody = FieldRegisters.fieldWins(incoming, existing, "body", Note.serializer())
        val winnerBody = if (incomingWinsBody) incoming.body else existing.body
        val loserBody = if (incomingWinsBody) existing.body else incoming.body
        val loserDevice = if (incomingWinsBody) existing.deviceOrigin else remoteDeviceId
        val loserUpdatedAt = if (incomingWinsBody) existing.updatedAt else incoming.updatedAt
        val siblings = (existing.conflictSiblings + incoming.conflictSiblings)
            .distinctBy { it.body }
        val withLoser =
            if (winnerBody == loserBody || siblings.any { it.body == loserBody }) siblings
            else siblings + ConflictSibling(loserBody, loserDevice, loserUpdatedAt)
        // The union must beat either input's siblings field, or a later merge erases it.
        val fieldClocks = merged.fieldClocks + ("conflictSiblings" to FieldRegisters.rowStamp(merged))
        return merged.copy(
            conflictSiblings = withLoser.filter { it.body != winnerBody },
            fieldClocks = fieldClocks,
        )
    }

    fun upsertNoteWithConflict(note: Note, remoteDeviceId: String): Note? = lock.withLock {
        val sessionId = note.sessionId ?: return@withLock null
        clocks.observe(note.clock)
        note.fieldClocks.values.forEach(clocks::observe)
        val existing = notesStore.get(note.id)
        val resolved = if (existing == null) note else mergeNoteConflictLocked(existing, note, remoteDeviceId)
        if (resolved == existing) return@withLock existing
        notesStore.put(resolved)
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
