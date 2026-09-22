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
 *
 * Retention is PEER-AWARE (contract section b: "retain until every peer's
 * high-water mark passes"); see [tombstoneRetentionMs] and
 * [mayPruneTombstone] for the rule and the watermark data source.
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

    /**
     * Tombstone retention window: a delete younger than this is never
     * pruned, regardless of watermarks (contract section b keeps a time
     * floor as belt-and-braces against clock skew between peers).
     */
    private val tombstoneRetentionMs = 30L * 86_400_000L

    /**
     * Fallback window used ONLY when no peer watermark has ever been
     * observed in this process (fresh install, never-hosted client, or a
     * single-device setup with no peers): pruning then uses a time window
     * alone, extended from 30 to 180 days. Justification: with zero
     * watermark evidence the choice is between unbounded growth of the
     * tombstone map (keep forever) and a bounded resurrection window;
     * 180 days covers realistic peer absence (a drawer phone, a holiday
     * laptop) six times over while the map stays small (~64 bytes per
     * entry). KNOWN GAP, reported rather than papered over: watermarks
     * live in process memory only (see [PeerSyncWatermarks]); persisting
     * them would need a snapshot/repo-API change in files owned elsewhere,
     * and NO wire change is required because SyncBatch.since already
     * carries every peer's cursor on every authenticated push.
     */
    private val tombstoneRetentionFallbackMs = 180L * 86_400_000L

    /** Deleted entity tombstones: "type:id" to deletion timestamp. Guarded by [lock]. */
    private val _tombstones = mutableMapOf<String, Long>()

    private fun tombKey(type: String, id: String) = "$type:$id"

    /**
     * May this tombstone be dropped RIGHT NOW?
     *
     * Rule (both conditions required): older than [tombstoneRetentionMs]
     * AND at or below every locally known peer sync watermark, i.e.
     * `deletedAt <= PeerSyncWatermarks.watermarkFloor()` (min over all
     * recorded peer cursors). A tombstone above a behind or unknown peer
     * watermark is KEPT even past the window: that peer has not drained
     * that far yet, and dropping the delete would resurrect it on the next
     * stale push (the flat 30-day window's bug). When NO watermark is
     * known at all, fall back to the extended time window alone.
     *
     * Watermark data source: the peer's protocol cursor (SyncBatch.since,
     * contract sections b/d) recorded by BOTH host push handlers into
     * [PeerSyncWatermarks]. Sources deliberately NOT used are listed on
     * that object. Callers must hold [lock] (watermark reads take their
     * own lock; nesting order is always tombstone lock -> watermark lock).
     */
    private fun mayPruneTombstone(deletedAt: Long, now: Long): Boolean {
        val floor = PeerSyncWatermarks.watermarkFloor()
        return if (floor == null) {
            deletedAt < now - tombstoneRetentionFallbackMs
        } else {
            deletedAt < now - tombstoneRetentionMs && deletedAt <= floor
        }
    }

    /** Record a deletion for propagation. Callers must hold [lock]. */
    private fun recordTombstone(type: String, id: String) {
        pruneTombstonesLocked()
        _tombstones[tombKey(type, id)] = currentTimeMillis()
    }

    private fun pruneTombstonesLocked() {
        val now = currentTimeMillis()
        val stale = _tombstones.filterValues { mayPruneTombstone(it, now) }.keys.toList()
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
        val now = currentTimeMillis()
        for ((key, deletedAt) in tombstones) {
            // Same eligibility rule as pruning: a restore must not smuggle
            // back a delete the retention policy would immediately drop.
            if (!mayPruneTombstone(deletedAt, now)) _tombstones[key] = deletedAt
        }
    }

    /**
     * Apply incoming tombstones. Deletes each listed local entity only when
     * NO local copy exists for that id or the local copy is not newer than
     * [cutoff] (contract section b: a concurrent update wins). [cutoff] is
     * the SENDER's cursor; 0 means the sender cursor is unknown and takes
     * the CONSERVATIVE path (an existing local copy always survives; the
     * legacy unconditional "cutoff == 0 deletes everything" rule is gone).
     * Returns true if anything was deleted. Callers must hold [lock].
     */
    internal fun applyTombstonesLocked(deleted: DeletedIds, cutoff: Long): Boolean {
        var changed = false
        fun <T> applyIds(ids: List<String>, get: (String) -> T?, updatedAt: (T) -> Long, delete: (String) -> Unit) {
            for (id in ids) {
                val existing = get(id) ?: continue
                if (updatedAt(existing) <= cutoff) {
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
        val now = currentTimeMillis()
        for ((key, deletedAt) in snapshot.tombstones) {
            if (mayPruneTombstone(deletedAt, now)) continue
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

/**
 * Per-peer sync watermarks observed by the sync HOSTS, feeding
 * peer-aware tombstone retention (contract section b: "retain until every
 * peer's high-water mark passes").
 *
 * DATA SOURCE: the peer's protocol cursor, `SyncBatch.since`, read from an
 * AUTHENTICATED HTTP push (contract sections b/d: the sender cursor, the
 * protocol's own notion of "everything up to here is settled at the
 * sender"). Recorded by both host push handlers: JVM
 * SyncServerHandlers.handlePush and iOS IosSyncServerRouter.applySyncBatch
 * (both call [recordPeerCursor] after the router has proved
 * batch.deviceId == the authenticated caller). The prune floor is the
 * MINIMUM cursor over all recorded peers; a peer cursor of 0 (a device
 * that has never drained) legitimately pins the floor at 0 so nothing
 * prunes until that peer catches up.
 *
 * Sources surveyed and deliberately NOT used, for the record:
 *  - DeviceTrustStore.TrustedPeer.lastSeenAt / IosTrustedPeer.lastSeenAt:
 *    wall-clock of the peer's LAST CONTACT (updated by pushes and pulls
 *    alike), not a data cursor: a push-only contact advances it without
 *    delivering a single tombstone, so it cannot prove receipt. It also
 *    lives in platform source sets that commonMain cannot reference.
 *  - model/Device.lastSyncAt: declared, zero write sites (dead field).
 *  - SyncTransport.lastSyncTime: this device's OWN cursor, not a peer's,
 *    and jvmMain-only.
 *
 * Known gaps (reported, not hidden): this registry is PROCESS-LOCAL, so
 * after a restart the floor is unknown until each peer pushes again and
 * [JournalTombstones] falls back to its extended window; persisting the
 * cursors would need a snapshot/repo-API change in files owned by the data
 * layer. No WIRE change is needed: SyncBatch.since already carries the
 * value. The WS delta path also carries `since` but cannot be recorded
 * without editing SyncServerRouting.kt (outside this change's ownership),
 * and the JVM pull route receives no device identity there; every
 * protocol client pushes before it pulls, so push observation covers
 * normal peers.
 *
 * Locking: guarded by its own [PlatformLock]; [JournalTombstones] reads it
 * while holding the repository lock, so the only nesting order is
 * repository lock -> this lock, never the reverse.
 */
internal object PeerSyncWatermarks {
    private val lock = PlatformLock()
    private val watermarks = mutableMapOf<String, Long>()

    /**
     * Record [deviceId]'s sync cursor from an accepted push. Monotonic per
     * device: a stale replayed cursor can never lower an already-higher
     * watermark (and lowering would only make pruning MORE conservative,
     * so monotonicity is a hygiene rule, not a security one).
     */
    fun recordPeerCursor(deviceId: String, cursor: Long) {
        if (deviceId.isBlank()) return
        lock.withLock {
            val current = watermarks[deviceId]
            if (current == null || cursor > current) watermarks[deviceId] = cursor
        }
    }

    /**
     * The prune floor: the minimum cursor over every locally known peer,
     * or null when no peer cursor has ever been observed (unknown, which
     * retention treats conservatively: window-only fallback, keep).
     */
    fun watermarkFloor(): Long? = lock.withLock { watermarks.values.minOrNull() }

    /** Forget every recorded watermark (test isolation; see the tests in commonTest). */
    fun clear() {
        lock.withLock { watermarks.clear() }
    }
}
