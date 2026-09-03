package app.journal.data

/**
 * Deleted entity IDs carried on sync payloads so deletes propagate to peers.
 *
 * Without tombstones a delete on one device resurrects on the next sync
 * from a peer that still holds the entity. Every delete records a tombstone
 * (see JournalRepository) and every sync payload carries the tombstones
 * newer than the peer cursor. All lists default to empty so older payloads
 * without tombstones still decode.
 */
data class DeletedIds(
    val deletedSessionIds: List<String> = emptyList(),
    val deletedDoseIds: List<String> = emptyList(),
    val deletedNoteIds: List<String> = emptyList(),
    val deletedSubstanceIds: List<String> = emptyList(),
    val deletedEffectIds: List<String> = emptyList(),
    val deletedInteractionIds: List<String> = emptyList(),
    val deletedTimelineEventIds: List<String> = emptyList(),
    val deletedCustomUnitIds: List<String> = emptyList()
)
