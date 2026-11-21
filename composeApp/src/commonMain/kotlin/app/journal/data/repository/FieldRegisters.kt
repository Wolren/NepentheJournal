package app.journal.data.repository

import app.journal.model.FieldVersioned
import app.journal.serde.AppJson
import app.journal.util.Hlc
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Per-field LWW registers on top of the row clock (phase 3).
 *
 * A local write stamps only the fields whose serialized value changed, so two peers
 * that edit different fields both keep their edit. A sync merge picks each field by
 * its stamp. A pair that includes a pre-v10 row (blank row clock) keeps the whole-row
 * updatedAt rule; field merging engages only when both rows carry a clock.
 *
 * The merge is deterministic and idempotent: stamps decide, deviceOrigin breaks an
 * exact stamp tie, and the encoded value breaks a full tie. Identity fields are not
 * merged: createdAt follows the row winner, updatedAt and the row clock take the
 * maximum.
 */
internal object FieldRegisters {

    private val identityFields = setOf(
        "id", "createdAt", "updatedAt", "deviceOrigin", "clock", "fieldClocks",
    )

    /** Clock map for a local write of [next] over [previous]. */
    fun <T : FieldVersioned> stamp(
        previous: T?,
        next: T,
        serializer: KSerializer<T>,
        rowClock: String,
    ): Map<String, String> {
        val nextJson = AppJson.json.encodeToJsonElement(serializer, next).jsonObject
        val previousJson = previous?.let { AppJson.json.encodeToJsonElement(serializer, it).jsonObject }
        val previousRow = previous?.let { rowStamp(it) }
        val clocks = LinkedHashMap<String, String>()
        for (field in nextJson.keys) {
            if (field in identityFields) continue
            clocks[field] = if (
                previous != null && previousJson != null && previousRow != null &&
                previousJson[field] == nextJson[field]
            ) {
                validStamp(previous.fieldClocks[field]) ?: previousRow
            } else {
                rowClock
            }
        }
        return clocks
    }

    /** Row stamp: the HLC clock, or one derived from updatedAt for a pre-v10 row. */
    fun <T : FieldVersioned> rowStamp(entity: T): String =
        validStamp(entity.clock) ?: Hlc.of(entity.updatedAt).encoded

    /** Field stamp: the field clock, or the row stamp when the field has no entry. */
    fun <T : FieldVersioned> fieldStamp(entity: T, field: String): String =
        validStamp(entity.fieldClocks[field]) ?: rowStamp(entity)

    /**
     * True when [incoming] wins [field]. Used by the session outcome note and the note
     * body sibling, so it must agree with [merge]. A pre-v10 row on either side falls
     * back to the whole-row rule.
     */
    fun <T : FieldVersioned> fieldWins(
        incoming: T,
        existing: T,
        field: String,
        serializer: KSerializer<T>,
    ): Boolean {
        if (validStamp(incoming.clock) == null || validStamp(existing.clock) == null) {
            return EntityOrder.incomingWins(incoming, existing)
        }
        val incomingValue = AppJson.json.encodeToJsonElement(serializer, incoming).jsonObject[field]
            ?: return EntityOrder.incomingWins(incoming, existing)
        val existingValue = AppJson.json.encodeToJsonElement(serializer, existing).jsonObject[field]
            ?: return EntityOrder.incomingWins(incoming, existing)
        return beats(
            fieldStamp(incoming, field), incoming.deviceOrigin, incomingValue.toString(),
            fieldStamp(existing, field), existing.deviceOrigin, existingValue.toString(),
        )
    }

    /**
     * Join two rows field by field. Both rows must carry a clock; a mixed pair returns
     * the whole-row winner unchanged, which preserves pre-v10 peer behaviour.
     */
    fun <T : FieldVersioned> merge(existing: T, incoming: T, serializer: KSerializer<T>): T {
        if (validStamp(existing.clock) == null || validStamp(incoming.clock) == null) {
            return if (EntityOrder.incomingWins(incoming, existing)) incoming else existing
        }
        val existingJson = AppJson.json.encodeToJsonElement(serializer, existing).jsonObject
        val incomingJson = AppJson.json.encodeToJsonElement(serializer, incoming).jsonObject
        val existingRow = rowStamp(existing)
        val incomingRow = rowStamp(incoming)
        val fieldStamps = LinkedHashMap<String, String>()

        val merged = buildJsonObject {
            for (field in existingJson.keys) {
                if (field in identityFields) continue
                val existingValue = existingJson.getValue(field)
                val incomingValue = incomingJson[field] ?: existingValue
                val existingExplicit = validStamp(existing.fieldClocks[field])
                val incomingExplicit = validStamp(incoming.fieldClocks[field])
                val existingStamp = existingExplicit ?: existingRow
                val incomingStamp = incomingExplicit ?: incomingRow
                val takeIncoming = beats(
                    incomingStamp, incoming.deviceOrigin, incomingValue.toString(),
                    existingStamp, existing.deviceOrigin, existingValue.toString(),
                )
                put(field, if (takeIncoming) incomingValue else existingValue)
                // Keep a missing entry missing when both sides fell back to the row
                // clock: adding it would make merge(x, x) differ from x.
                if (existingExplicit != null || incomingExplicit != null) {
                    fieldStamps[field] = if (takeIncoming) incomingStamp else existingStamp
                }
            }
            put("id", JsonPrimitive(existing.id))
            val incomingRowWins = rowBeats(
                incomingRow, incoming.deviceOrigin, incoming.createdAt,
                existingRow, existing.deviceOrigin, existing.createdAt,
            )
            // createdAt follows the row winner: both peers compute the same winner, so
            // the value converges without relying on a follow-up push to propagate it.
            put("createdAt", JsonPrimitive(if (incomingRowWins) incoming.createdAt else existing.createdAt))
            put("updatedAt", JsonPrimitive(maxOf(existing.updatedAt, incoming.updatedAt)))
            put("deviceOrigin", JsonPrimitive(if (incomingRowWins) incoming.deviceOrigin else existing.deviceOrigin))
            put("clock", JsonPrimitive(if (incomingRowWins) incomingRow else existingRow))
            put("fieldClocks", buildJsonObject {
                for ((field, stamp) in fieldStamps) put(field, JsonPrimitive(stamp))
            })
        }
        return AppJson.json.decodeFromJsonElement(serializer, merged)
    }

    /** Drops malformed stamps so a hand-edited file falls back to updatedAt. */
    private fun validStamp(stamp: String?): String? =
        stamp?.takeIf { Hlc.parseOrNull(it) != null }

    private fun rowBeats(
        incomingStamp: String,
        incomingDevice: String,
        incomingCreatedAt: Long,
        existingStamp: String,
        existingDevice: String,
        existingCreatedAt: Long,
    ): Boolean {
        val incoming = Hlc.parseOrNull(incomingStamp)
        val existing = Hlc.parseOrNull(existingStamp)
        if (incoming != null && existing != null && incoming != existing) return incoming > existing
        if (incomingDevice != existingDevice) return incomingDevice > existingDevice
        return incomingCreatedAt > existingCreatedAt
    }

    private fun beats(
        incomingStamp: String,
        incomingDevice: String,
        incomingValue: String,
        existingStamp: String,
        existingDevice: String,
        existingValue: String,
    ): Boolean {
        val incoming = Hlc.parseOrNull(incomingStamp)
        val existing = Hlc.parseOrNull(existingStamp)
        if (incoming != null && existing != null && incoming != existing) return incoming > existing
        if (incomingDevice != existingDevice) return incomingDevice > existingDevice
        return incomingValue > existingValue
    }
}
