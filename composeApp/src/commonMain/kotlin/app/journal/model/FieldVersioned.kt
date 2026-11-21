package app.journal.model

/**
 * A synced row with per-field hybrid logical clock stamps (phase 3). The map key is
 * the serialized field name; a missing entry falls back to the row clock. Sessions,
 * doses and notes carry it from snapshot version 11. Other synced rows merge whole.
 */
interface FieldVersioned : ClockStamped {
    val fieldClocks: Map<String, String>
}
