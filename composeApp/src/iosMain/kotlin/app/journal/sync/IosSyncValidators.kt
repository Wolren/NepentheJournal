package app.journal.sync

import app.journal.model.CustomUnit
import app.journal.model.Dose
import app.journal.model.Effect
import app.journal.model.Interaction
import app.journal.model.Note
import app.journal.model.Session
import app.journal.model.Substance
import app.journal.model.TimelineEvent
import app.journal.util.currentTimeMillis

/**
 * iOS sync payload validation.
 *
 * Same field caps as the shared JVM validators (SyncValidators.kt): count
 * limits plus per field length, range, and value checks enforced before any
 * data reaches the repository. Timestamp bounds additionally reject entities
 * with negative timestamps or timestamps more than 2 years in the future,
 * matching the shared ExportImport future margin policy.
 */
object IosSyncValidators {

    const val MAX_ITEMS_DEFAULT = 500
    // Measured seed max is 325 substances; headroom for user customs.
    const val MAX_SUBSTANCES = 1000
    const val MAX_EFFECTS = 100
    const val MAX_INTERACTIONS = 100
    const val MAX_CUSTOM_UNITS = 100
    const val MAX_FIELD_LEN = 65536
    const val MAX_ID_LEN = 128
    const val MAX_NAME_LEN = 200
    const val MAX_TITLE_LEN = 500
    const val MAX_UNIT_LEN = 20
    const val MAX_ROA_LEN = 50
    const val MAX_LABEL_LEN = 200
    const val MAX_UNIT_NAME_LEN = 100

    /** Max margin into the future for entity timestamps (2 years). */
    const val TIMESTAMP_FUTURE_MARGIN_MS = 31536000000L * 2

    /** Validate a SyncBatch push payload. Returns an error or null. */
    fun validateSyncBatch(batch: SyncBatch): String? {
        if (batch.sessions.size > MAX_ITEMS_DEFAULT) return "Too many sessions (max $MAX_ITEMS_DEFAULT)"
        if (batch.doses.size > MAX_ITEMS_DEFAULT) return "Too many doses (max $MAX_ITEMS_DEFAULT)"
        if (batch.substances.size > MAX_SUBSTANCES) return "Too many substances (max $MAX_SUBSTANCES)"
        if (batch.notes.size > MAX_ITEMS_DEFAULT) return "Too many notes (max $MAX_ITEMS_DEFAULT)"
        if (batch.timelineEvents.size > MAX_ITEMS_DEFAULT) return "Too many events (max $MAX_ITEMS_DEFAULT)"
        if (batch.interactions.size > MAX_INTERACTIONS) return "Too many interactions (max $MAX_INTERACTIONS)"
        if (batch.effects.size > MAX_EFFECTS) return "Too many effects (max $MAX_EFFECTS)"
        if (batch.customUnits.size > MAX_CUSTOM_UNITS) return "Too many custom units (max $MAX_CUSTOM_UNITS)"
        validateDeletedIds(
            batch.deletedSessionIds, batch.deletedDoseIds, batch.deletedSubstanceIds,
            batch.deletedEffectIds, batch.deletedInteractionIds, batch.deletedNoteIds,
            batch.deletedTimelineEventIds, batch.deletedCustomUnitIds
        )?.let { return it }

        validateSessions(batch.sessions)?.let { return it }
        validateDoses(batch.doses)?.let { return it }
        validateNotes(batch.notes)?.let { return it }
        validateSubstances(batch.substances)?.let { return it }
        validateInteractions(batch.interactions)?.let { return it }
        validateTimelineEvents(batch.timelineEvents)?.let { return it }
        validateEffects(batch.effects)?.let { return it }
        validateCustomUnits(batch.customUnits)?.let { return it }
        return null
    }

    /** Tombstone ID validation: per-type count caps plus ID shape. */
    private fun validateDeletedIds(
        sessions: List<String>,
        doses: List<String>,
        substances: List<String>,
        effects: List<String>,
        interactions: List<String>,
        notes: List<String>,
        timelineEvents: List<String>,
        customUnits: List<String>
    ): String? {
        if (sessions.size > MAX_ITEMS_DEFAULT) return "Too many deleted sessions (max $MAX_ITEMS_DEFAULT)"
        if (doses.size > MAX_ITEMS_DEFAULT) return "Too many deleted doses (max $MAX_ITEMS_DEFAULT)"
        if (substances.size > MAX_SUBSTANCES) return "Too many deleted substances (max $MAX_SUBSTANCES)"
        if (notes.size > MAX_ITEMS_DEFAULT) return "Too many deleted notes (max $MAX_ITEMS_DEFAULT)"
        if (timelineEvents.size > MAX_ITEMS_DEFAULT) return "Too many deleted events (max $MAX_ITEMS_DEFAULT)"
        if (interactions.size > MAX_INTERACTIONS) return "Too many deleted interactions (max $MAX_INTERACTIONS)"
        if (effects.size > MAX_EFFECTS) return "Too many deleted effects (max $MAX_EFFECTS)"
        if (customUnits.size > MAX_CUSTOM_UNITS) return "Too many deleted custom units (max $MAX_CUSTOM_UNITS)"
        for (id in sessions + doses + substances + effects + interactions + notes + timelineEvents + customUnits) {
            if (id.isBlank() || id.length > MAX_ID_LEN) return "Invalid deleted ID"
        }
        return null
    }

    private fun timestampError(value: Long, field: String): String? {
        if (value < 0) return "$field timestamp out of range"
        val max = currentTimeMillis() + TIMESTAMP_FUTURE_MARGIN_MS
        if (value > max) return "$field timestamp out of range"
        return null
    }

    private fun validateSessions(sessions: List<Session>): String? {
        for (s in sessions) {
            if (s.id.length > MAX_ID_LEN) return "Session ID too long"
            if (s.title.length > MAX_TITLE_LEN) return "Session title too long"
            if ((s.set?.length ?: 0) > MAX_FIELD_LEN) return "Session set too long"
            if ((s.setting?.length ?: 0) > MAX_FIELD_LEN) return "Session setting too long"
            if ((s.intention?.length ?: 0) > MAX_FIELD_LEN) return "Session intention too long"
            if ((s.outcome?.length ?: 0) > MAX_FIELD_LEN) return "Session outcome too long"
            if ((s.notes?.length ?: 0) > MAX_FIELD_LEN) return "Session notes too long"
            if (s.rating != null && (s.rating < 1 || s.rating > 10)) return "Invalid rating"
            timestampError(s.createdAt, "Session createdAt")?.let { return it }
            timestampError(s.updatedAt, "Session updatedAt")?.let { return it }
            timestampError(s.startTime, "Session startTime")?.let { return it }
            if (s.endTime != null) timestampError(s.endTime, "Session endTime")?.let { return it }
        }
        return null
    }

    private fun validateDoses(doses: List<Dose>): String? {
        for (d in doses) {
            if (d.id.length > MAX_ID_LEN) return "Dose ID too long"
            if (d.sessionId.length > MAX_ID_LEN) return "Dose sessionId too long"
            if (d.substanceId.length > MAX_ID_LEN) return "Dose substanceId too long"
            if (d.routeOfAdministration.length > MAX_ROA_LEN) return "Invalid ROA length"
            if (d.unit.length > MAX_UNIT_LEN) return "Invalid unit length"
            if (!d.amount.isFinite()) return "Invalid dose amount"
            if (d.amount < 0 || d.amount > 1_000_000) return "Invalid dose amount"
            if ((d.notes?.length ?: 0) > MAX_FIELD_LEN) return "Dose notes too long"
            timestampError(d.createdAt, "Dose createdAt")?.let { return it }
            timestampError(d.updatedAt, "Dose updatedAt")?.let { return it }
            timestampError(d.timestamp, "Dose timestamp")?.let { return it }
        }
        return null
    }

    private fun validateNotes(notes: List<Note>): String? {
        for (n in notes) {
            if (n.id.length > MAX_ID_LEN) return "Note ID too long"
            if (n.body.length > MAX_FIELD_LEN) return "Note body too long"
            if ((n.title?.length ?: 0) > MAX_TITLE_LEN) return "Note title too long"
            timestampError(n.createdAt, "Note createdAt")?.let { return it }
            timestampError(n.updatedAt, "Note updatedAt")?.let { return it }
        }
        return null
    }

    private fun validateSubstances(substances: List<Substance>): String? {
        for (s in substances) {
            if (s.id.length > MAX_ID_LEN) return "Substance ID too long"
            if (s.name.length > MAX_NAME_LEN) return "Substance name too long"
            if (s.aliases.any { it.length > MAX_NAME_LEN }) return "Substance alias too long"
            if ((s.summary?.length ?: 0) > MAX_FIELD_LEN) return "Substance summary too long"
            timestampError(s.createdAt, "Substance createdAt")?.let { return it }
            timestampError(s.updatedAt, "Substance updatedAt")?.let { return it }
        }
        return null
    }

    private fun validateInteractions(interactions: List<Interaction>): String? {
        for (i in interactions) {
            if (i.id.length > MAX_ID_LEN) return "Interaction ID too long"
            if (i.substanceAId.length > MAX_ID_LEN) return "Interaction substanceAId too long"
            if (i.substanceBId.length > MAX_ID_LEN) return "Interaction substanceBId too long"
            if ((i.description?.length ?: 0) > MAX_FIELD_LEN) return "Interaction description too long"
            timestampError(i.createdAt, "Interaction createdAt")?.let { return it }
            timestampError(i.updatedAt, "Interaction updatedAt")?.let { return it }
        }
        return null
    }

    private fun validateTimelineEvents(events: List<TimelineEvent>): String? {
        for (t in events) {
            if (t.id.length > MAX_ID_LEN) return "TimelineEvent ID too long"
            if (t.label.length > MAX_LABEL_LEN) return "TimelineEvent label too long"
            if ((t.body?.length ?: 0) > MAX_FIELD_LEN) return "TimelineEvent body too long"
            timestampError(t.createdAt, "TimelineEvent createdAt")?.let { return it }
            timestampError(t.updatedAt, "TimelineEvent updatedAt")?.let { return it }
            timestampError(t.timestamp, "TimelineEvent timestamp")?.let { return it }
        }
        return null
    }

    private fun validateEffects(effects: List<Effect>): String? {
        for (e in effects) {
            if (e.id.length > MAX_ID_LEN) return "Effect ID too long"
            if (e.name.length > MAX_NAME_LEN) return "Effect name too long"
            if (e.substanceIds.any { it.length > MAX_ID_LEN }) return "Effect substanceId too long"
            timestampError(e.createdAt, "Effect createdAt")?.let { return it }
            timestampError(e.updatedAt, "Effect updatedAt")?.let { return it }
        }
        return null
    }

    private fun validateCustomUnits(units: List<CustomUnit>): String? {
        for (u in units) {
            if (u.id.length > MAX_ID_LEN) return "Unit ID too long"
            if (u.name.length > MAX_UNIT_NAME_LEN) return "Unit name too long"
            if (u.substanceId.length > MAX_ID_LEN) return "Unit substanceId too long"
            timestampError(u.createdAt, "Unit createdAt")?.let { return it }
            timestampError(u.updatedAt, "Unit updatedAt")?.let { return it }
        }
        return null
    }
}
