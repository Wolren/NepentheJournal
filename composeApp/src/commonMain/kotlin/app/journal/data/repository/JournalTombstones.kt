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
 * Tombstone journal plus the delete cascades that record tombstones.
 * Split out of JournalRepository in the wave2 structural refactor: shares
 * the facade lock, so every method synchronizes on the SAME monitor.
 */
internal class JournalTombstones(
    private val lock: PlatformLock,
    private val sessionsStore: EntityStore<Session>,
    private val dosesStore: EntityStore<Dose>,
    private val notesStore: EntityStore<Note>,
    private val substancesStore: EntityStore<Substance>,
    private val effectsStore: EntityStore<Effect>,
    private val interactionsStore: EntityStore<Interaction>,
    private val timelineEventsStore: EntityStore<TimelineEvent>,
    private val customUnitsStore: EntityStore<CustomUnit>,
    private val indices: JournalIndices,
    private val bumpToleranceVersion: () -> Unit,
    private val bumpMutationCount: () -> Unit,
) {
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

    fun deletedIdsSince(since: Long): DeletedIds {
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

    fun exportTombstones(): Map<String, Long> = lock.withLock { _tombstones.toMap() }

    fun importTombstones(tombstones: Map<String, Long>) = lock.withLock {
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
    internal fun applyTombstonesLocked(deleted: DeletedIds, cutoff: Long): Boolean {
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

    /** Merge a snapshot's tombstones into local state: union, newest timestamp wins, retention-filtered. Callers must hold [lock]. */
    internal fun mergeSnapshotTombstonesLocked(snapshot: JournalSnapshot) {
        pruneTombstonesLocked()
        val tombCutoff = currentTimeMillis() - tombstoneRetentionMs
        for ((key, deletedAt) in snapshot.tombstones) {
            if (deletedAt < tombCutoff) continue
            val current = _tombstones[key]
            if (current == null || deletedAt > current) _tombstones[key] = deletedAt
        }
    }

    internal fun clear() {
        _tombstones.clear()
    }

    // ---- Delete cascades: every removal records a tombstone. Callers must hold [lock]. ----
    internal fun deleteSessionLocked(id: String) {
        val session = sessionsStore.get(id) ?: return
        sessionsStore.remove(id)
        indices.removeSessionFromIndices(session)
        recordTombstone("session", id)

        // Batch-remove child entities with single emissions per store
        val removedDoses = dosesStore.removeWhere { it.sessionId == id }
        for (dose in removedDoses) recordTombstone("dose", dose.id)
        val removedSubstances = removedDoses.map { it.substanceId }.toSet()

        indices._dosesBySession.remove(id)
        indices._notesBySession.remove(id)
        indices._eventsBySession.remove(id)

        val removedNotes = notesStore.removeWhere { it.sessionId == id }
        for (note in removedNotes) recordTombstone("note", note.id)
        val removedEvents = timelineEventsStore.removeWhere { it.sessionId == id }
        for (event in removedEvents) recordTombstone("timelineEvent", event.id)

        // Rebuild dose stats for affected substances
        for (subId in removedSubstances) {
            indices._sessionsPerSubstance[subId]?.remove(id)
            if (indices._sessionsPerSubstance[subId]?.isEmpty() == true)
                indices._sessionsPerSubstance.remove(subId)
            indices.rebuildSubstanceDoseStats(subId)
        }

        bumpMutationCount()
        bumpToleranceVersion()
    }

    internal fun deleteDoseLocked(id: String) {
        val removed = dosesStore.remove(id) ?: return
        recordTombstone("dose", id)
        indices.removeDoseFromIndicesLocked(removed)
        indices.rebuildSubstanceDoseStats(removed.substanceId)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    internal fun deleteNoteLocked(id: String) {
        val removed = notesStore.remove(id) ?: return
        recordTombstone("note", id)
        removed.sessionId?.let { indices._notesBySession[it]?.removeAll { n -> n.id == id } }
        bumpMutationCount()
    }

    internal fun deleteTimelineEventLocked(id: String) {
        val removed = timelineEventsStore.remove(id) ?: return
        recordTombstone("timelineEvent", id)
        indices._eventsBySession[removed.sessionId]?.removeAll { e -> e.id == id }
        bumpMutationCount()
    }

    internal fun deleteSubstanceLocked(id: String) {
        substancesStore.remove(id)
        recordTombstone("substance", id)

        // Cascade: remove all child entities for this substance
        val removedUnits = customUnitsStore.removeWhere { it.substanceId == id }
        for (unit in removedUnits) recordTombstone("customUnit", unit.id)
        indices._customUnitsBySubstance.remove(id)

        // Clean up effect index entries referencing this substance
        effectsStore.forEachValue { effect ->
            if (id in effect.substanceIds) {
                indices._effectsBySubstance[id]?.removeAll { it.id == effect.id }
            }
        }
        indices._effectsBySubstance.remove(id)

        // Clean up interactions referencing this substance
        val removedInteractions = interactionsStore.removeWhere {
            id in listOf(it.substanceAId, it.substanceBId)
        }
        for (interaction in removedInteractions) recordTombstone("interaction", interaction.id)

        val removedSubstanceDoses = dosesStore.removeWhere { it.substanceId == id }
        for (dose in removedSubstanceDoses) recordTombstone("dose", dose.id)
        val affectedSessionIds = removedSubstanceDoses.map { it.sessionId }.toSet()
        for (sessionId in affectedSessionIds) {
            indices._dosesBySession[sessionId]?.removeAll { it.substanceId == id }
        }
        indices._sessionsPerSubstance.remove(id)
        indices._substanceDoseStats.remove(id)
        indices._doseStatsSessionIds.remove(id)
        bumpToleranceVersion()
        bumpMutationCount()
    }

    internal fun deleteCustomUnitLocked(id: String) {
        val removed = customUnitsStore.remove(id) ?: return
        recordTombstone("customUnit", id)
        indices._customUnitsBySubstance[removed.substanceId]?.removeAll { it.id == id }
        if (indices._customUnitsBySubstance[removed.substanceId]?.isEmpty() == true)
            indices._customUnitsBySubstance.remove(removed.substanceId)
        bumpMutationCount()
    }

    internal fun deleteEffectLocked(id: String) {
        val removed = effectsStore.remove(id) ?: return
        for (subId in removed.substanceIds) {
            indices._effectsBySubstance[subId]?.removeAll { it.id == id }
            if (indices._effectsBySubstance[subId]?.isEmpty() == true)
                indices._effectsBySubstance.remove(subId)
        }
        recordTombstone("effect", id)
        bumpMutationCount()
    }

    internal fun deleteInteractionLocked(id: String) {
        interactionsStore.remove(id) ?: return
        recordTombstone("interaction", id)
        bumpMutationCount()
    }
}
