package app.journal.data.repository

/**
 * Total order for conflicting writes. Later timestamp wins; on an exact tie the larger
 * device origin wins, so two peers that saw the same pair pick the same side instead of
 * each keeping its own copy. A device beating itself keeps the incoming write, which is
 * the later operation inside one batch.
 */
internal object EntityOrder {

    fun incomingWins(
        incomingUpdatedAt: Long,
        incomingDevice: String,
        existingUpdatedAt: Long,
        existingDevice: String,
    ): Boolean {
        if (incomingUpdatedAt != existingUpdatedAt) return incomingUpdatedAt > existingUpdatedAt
        if (incomingDevice == existingDevice) return true
        return incomingDevice > existingDevice
    }
}
