package app.journal.sync

/**
 * Push chunking and cursor discipline (commonMain).
 *
 * Contract: docs/HARDENING-CONTRACTS-2026-09.md section (d). A client MUST
 * slice its outgoing SyncBatch through [buildPushSlices] so every slice
 * respects the validator count caps, send the slices sequentially, and
 * advance its cursor ONLY when every slice acked success=true
 * ([allSucceeded] / [advanceCursorIfAllSucceeded]). On any failure the
 * cursor stays at its previous value so the unsent remainder is retried.
 *
 * This fixes audit C1: a first sync against the bundled seed library
 * (2015 interactions, cap 100) used to send one oversized batch that the
 * server always rejected, deadlocking lastSyncTime forever.
 */

/**
 * Per-slice count caps. Defaults are the shared SyncLimits values, so a
 * slice produced with defaults always passes [validateSyncBatch].
 */
data class SyncChunkLimits(
    val maxItems: Int = SyncLimits.MAX_ITEMS_DEFAULT,
    val maxSubstances: Int = SyncLimits.MAX_SUBSTANCES,
    val maxEffects: Int = SyncLimits.MAX_EFFECTS,
    val maxInteractions: Int = SyncLimits.MAX_INTERACTIONS,
    val maxCustomUnits: Int = SyncLimits.MAX_CUSTOM_UNITS
)

/**
 * Slice [batch] into sequential push payloads that each respect [limits].
 *
 * Every slice keeps the batch metadata (deviceId, deviceName, since), so the
 * server can validate and attribute each slice exactly like a full batch.
 * Entity order is preserved: concatenating the slices collection-by-collection
 * reproduces the input lists exactly, with disjoint ids per collection.
 *
 * Tombstone ID lists are sliced with the same per-type caps as their entity
 * collections, because [validateSyncBatch] caps deleted IDs identically.
 *
 * An empty batch yields a single empty slice (documented choice): callers can
 * send slices uniformly and the empty slice acks as a no-op, keeping the
 * "advance cursor only after every slice succeeds" rule trivial.
 *
 * @throws IllegalArgumentException if any cap is below 1 (would never drain).
 */
fun buildPushSlices(batch: SyncBatch, limits: SyncChunkLimits = SyncChunkLimits()): List<SyncBatch> {
    require(
        limits.maxItems >= 1 && limits.maxSubstances >= 1 && limits.maxEffects >= 1 &&
            limits.maxInteractions >= 1 && limits.maxCustomUnits >= 1
    ) { "All chunk caps must be >= 1" }

    var sessions = batch.sessions
    var doses = batch.doses
    var substances = batch.substances
    var effects = batch.effects
    var interactions = batch.interactions
    var notes = batch.notes
    var events = batch.timelineEvents
    var units = batch.customUnits
    var deletedSessions = batch.deletedSessionIds
    var deletedDoses = batch.deletedDoseIds
    var deletedNotes = batch.deletedNoteIds
    var deletedSubstances = batch.deletedSubstanceIds
    var deletedEffects = batch.deletedEffectIds
    var deletedInteractions = batch.deletedInteractionIds
    var deletedEvents = batch.deletedTimelineEventIds
    var deletedUnits = batch.deletedCustomUnitIds

    val slices = mutableListOf<SyncBatch>()
    while (true) {
        slices += batch.copy(
            sessions = sessions.take(limits.maxItems),
            doses = doses.take(limits.maxItems),
            substances = substances.take(limits.maxSubstances),
            effects = effects.take(limits.maxEffects),
            interactions = interactions.take(limits.maxInteractions),
            notes = notes.take(limits.maxItems),
            timelineEvents = events.take(limits.maxItems),
            customUnits = units.take(limits.maxCustomUnits),
            deletedSessionIds = deletedSessions.take(limits.maxItems),
            deletedDoseIds = deletedDoses.take(limits.maxItems),
            deletedNoteIds = deletedNotes.take(limits.maxItems),
            deletedSubstanceIds = deletedSubstances.take(limits.maxSubstances),
            deletedEffectIds = deletedEffects.take(limits.maxEffects),
            deletedInteractionIds = deletedInteractions.take(limits.maxInteractions),
            deletedTimelineEventIds = deletedEvents.take(limits.maxItems),
            deletedCustomUnitIds = deletedUnits.take(limits.maxCustomUnits)
        )
        sessions = sessions.drop(limits.maxItems)
        doses = doses.drop(limits.maxItems)
        substances = substances.drop(limits.maxSubstances)
        effects = effects.drop(limits.maxEffects)
        interactions = interactions.drop(limits.maxInteractions)
        notes = notes.drop(limits.maxItems)
        events = events.drop(limits.maxItems)
        units = units.drop(limits.maxCustomUnits)
        deletedSessions = deletedSessions.drop(limits.maxItems)
        deletedDoses = deletedDoses.drop(limits.maxItems)
        deletedNotes = deletedNotes.drop(limits.maxItems)
        deletedSubstances = deletedSubstances.drop(limits.maxSubstances)
        deletedEffects = deletedEffects.drop(limits.maxEffects)
        deletedInteractions = deletedInteractions.drop(limits.maxInteractions)
        deletedEvents = deletedEvents.drop(limits.maxItems)
        deletedUnits = deletedUnits.drop(limits.maxCustomUnits)

        val drained = sessions.isEmpty() && doses.isEmpty() && substances.isEmpty() &&
            effects.isEmpty() && interactions.isEmpty() && notes.isEmpty() &&
            events.isEmpty() && units.isEmpty() && deletedSessions.isEmpty() &&
            deletedDoses.isEmpty() && deletedNotes.isEmpty() && deletedSubstances.isEmpty() &&
            deletedEffects.isEmpty() && deletedInteractions.isEmpty() &&
            deletedEvents.isEmpty() && deletedUnits.isEmpty()
        if (drained) return slices
    }
}

/**
 * True only when at least one ack was received AND every ack reports
 * success=true. The cursor MUST NOT move on an empty ack list: that means no
 * slice was confirmed.
 */
fun allSucceeded(acks: List<SyncResponse>): Boolean =
    acks.isNotEmpty() && acks.all { it.success }

/**
 * Cursor advance rule (contract section d): the cursor moves to
 * [candidateCursor] only when every slice acked success; otherwise it stays
 * at [previousCursor] and the client retries from there next cycle.
 */
fun advanceCursorIfAllSucceeded(previousCursor: Long, candidateCursor: Long, acks: List<SyncResponse>): Long =
    if (allSucceeded(acks)) candidateCursor else previousCursor
