package app.journal.data.repository

import app.journal.model.ClockStamped
import app.journal.util.Hlc

/**
 * Total order for conflicting writes. When BOTH sides carry a hybrid logical clock
 * stamp, the clock decides; otherwise updatedAt decides. An exact tie on the
 * deciding key goes to the larger device origin, so two peers that saw the same pair
 * pick the same side instead of each keeping its own copy. A device beating itself
 * keeps the incoming write, which is the later operation inside one batch.
 *
 * Mixed case: one side stamped, one pre-v10 blank. Only updatedAt is shared there,
 * so it decides. That relation is not transitive across a mix of stamped and
 * unstamped rows; the merge laws hold per pair until every row carries a stamp.
 */
internal object EntityOrder {

    fun incomingWins(
        incomingUpdatedAt: Long,
        incomingDevice: String,
        existingUpdatedAt: Long,
        existingDevice: String,
    ): Boolean = incomingWins(
        incomingUpdatedAt, "", incomingDevice,
        existingUpdatedAt, "", existingDevice,
    )

    fun incomingWins(incoming: ClockStamped, existing: ClockStamped): Boolean = incomingWins(
        incoming.updatedAt, incoming.clock, incoming.deviceOrigin,
        existing.updatedAt, existing.clock, existing.deviceOrigin,
    )

    fun incomingWins(
        incomingUpdatedAt: Long,
        incomingClock: String,
        incomingDevice: String,
        existingUpdatedAt: Long,
        existingClock: String,
        existingDevice: String,
    ): Boolean {
        val incomingHlc = Hlc.parseOrNull(incomingClock)
        val existingHlc = Hlc.parseOrNull(existingClock)
        if (incomingHlc != null && existingHlc != null) {
            val byClock = incomingHlc.compareTo(existingHlc)
            if (byClock != 0) return byClock > 0
        } else if (incomingUpdatedAt != existingUpdatedAt) {
            return incomingUpdatedAt > existingUpdatedAt
        }
        if (incomingDevice == existingDevice) return true
        return incomingDevice > existingDevice
    }
}
