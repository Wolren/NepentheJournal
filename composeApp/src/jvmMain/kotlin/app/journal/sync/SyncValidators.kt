package app.journal.sync

import app.journal.data.AppJson
import app.journal.log.Log
import app.journal.model.*
import kotlinx.serialization.json.Json

/**
 * Input validation for sync payloads.
 * Shared between HTTP (KtorSyncServerJvm) and WebSocket handlers.
 *
 * All field-length and count limits are enforced BEFORE any data reaches
 * JournalRepository, preventing injection of malformed data from untrusted
 * (or compromised) peers.
 */

private val json = AppJson.json

/** Count caps shared with the pull handler so push and pull enforce the same limits. */
const val MAX_ITEMS_DEFAULT = 500
const val MAX_SUBSTANCES = 100
const val MAX_EFFECTS = 100
const val MAX_INTERACTIONS = 100
const val MAX_CUSTOM_UNITS = 100
private const val MAX_FIELD_LEN = 65536
private const val MAX_ID_LEN = 128
private const val MAX_NAME_LEN = 200
private const val MAX_TITLE_LEN = 500
private const val MAX_UNIT_LEN = 20
private const val MAX_ROA_LEN = 50
private const val MAX_LABEL_LEN = 200

/** Earliest accepted entity timestamp: 2000-01-01T00:00:00Z. */
const val MIN_ENTITY_TIMESTAMP = 946684800000L
/** Entity timestamps may be at most 1 day in the future (clock skew allowance). */
private const val FUTURE_SLACK_MS = 86_400_000L

/** Entity timestamps must fall between 2000-01-01 and now plus 1 day. */
private fun isReasonableEntityTime(ts: Long): Boolean =
    ts in MIN_ENTITY_TIMESTAMP..(System.currentTimeMillis() + FUTURE_SLACK_MS)

/**
 * Validate a SyncBatch (HTTP push). Returns an error message or null.
 */
fun validateSyncBatch(batch: SyncBatch): String? {
    // Count limits
    if (batch.sessions.size > MAX_ITEMS_DEFAULT) return "Too many sessions (max $MAX_ITEMS_DEFAULT)"
    if (batch.doses.size > MAX_ITEMS_DEFAULT) return "Too many doses (max $MAX_ITEMS_DEFAULT)"
    if (batch.substances.size > MAX_SUBSTANCES) return "Too many substances (max $MAX_SUBSTANCES)"
    if (batch.notes.size > MAX_ITEMS_DEFAULT) return "Too many notes (max $MAX_ITEMS_DEFAULT)"
    if (batch.timelineEvents.size > MAX_ITEMS_DEFAULT) return "Too many events (max $MAX_ITEMS_DEFAULT)"
    if (batch.interactions.size > MAX_INTERACTIONS) return "Too many interactions (max $MAX_INTERACTIONS)"
    if (batch.effects.size > MAX_EFFECTS) return "Too many effects (max $MAX_EFFECTS)"
    if (batch.customUnits.size > MAX_CUSTOM_UNITS) return "Too many custom units (max $MAX_CUSTOM_UNITS)"

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

/**
 * Validate a WsDelta (WebSocket sync). Returns an error message or null.
 */
fun validateWsDelta(delta: WsDelta): String? {
    if (delta.sessions.size > MAX_ITEMS_DEFAULT) return "Too many sessions (max $MAX_ITEMS_DEFAULT)"
    if (delta.doses.size > MAX_ITEMS_DEFAULT) return "Too many doses (max $MAX_ITEMS_DEFAULT)"
    if (delta.substances.size > MAX_SUBSTANCES) return "Too many substances (max $MAX_SUBSTANCES)"
    if (delta.notes.size > MAX_ITEMS_DEFAULT) return "Too many notes (max $MAX_ITEMS_DEFAULT)"
    if (delta.timelineEvents.size > MAX_ITEMS_DEFAULT) return "Too many events (max $MAX_ITEMS_DEFAULT)"
    if (delta.interactions.size > MAX_INTERACTIONS) return "Too many interactions (max $MAX_INTERACTIONS)"
    if (delta.effects.size > MAX_EFFECTS) return "Too many effects (max $MAX_EFFECTS)"
    if (delta.customUnits.size > MAX_CUSTOM_UNITS) return "Too many custom units (max $MAX_CUSTOM_UNITS)"

    validateSessions(delta.sessions)?.let { return it }
    validateDoses(delta.doses)?.let { return it }
    validateNotes(delta.notes)?.let { return it }
    validateSubstances(delta.substances)?.let { return it }
    validateInteractions(delta.interactions)?.let { return it }
    validateTimelineEvents(delta.timelineEvents)?.let { return it }
    validateEffects(delta.effects)?.let { return it }
    validateCustomUnits(delta.customUnits)?.let { return it }
    return null
}

// ========== Entity-level field validation ==========

private fun validateSessions(sessions: List<Session>): String? {
    for (s in sessions) {
        if (s.id.isBlank() || s.id.length > MAX_ID_LEN) return "Invalid session ID"
        if (!isReasonableEntityTime(s.createdAt)) return "Session createdAt out of range"
        if (!isReasonableEntityTime(s.updatedAt)) return "Session updatedAt out of range"
        if (!isReasonableEntityTime(s.startTime)) return "Session startTime out of range"
        if (s.endTime != null && !isReasonableEntityTime(s.endTime)) return "Session endTime out of range"
        if (s.title.length > MAX_TITLE_LEN) return "Session title too long"
        if ((s.set?.length ?: 0) > MAX_FIELD_LEN) return "Session set too long"
        if ((s.setting?.length ?: 0) > MAX_FIELD_LEN) return "Session setting too long"
        if ((s.intention?.length ?: 0) > MAX_FIELD_LEN) return "Session intention too long"
        if ((s.outcome?.length ?: 0) > MAX_FIELD_LEN) return "Session outcome too long"
        if (s.rating != null && (s.rating < 1 || s.rating > 10)) return "Invalid rating"
    }
    return null
}

private fun validateDoses(doses: List<Dose>): String? {
    for (d in doses) {
        if (d.id.isBlank() || d.id.length > MAX_ID_LEN) return "Invalid dose ID"
        if (!isReasonableEntityTime(d.createdAt)) return "Dose createdAt out of range"
        if (!isReasonableEntityTime(d.updatedAt)) return "Dose updatedAt out of range"
        if (!isReasonableEntityTime(d.timestamp)) return "Dose timestamp out of range"
        if (d.sessionId.isBlank() || d.sessionId.length > MAX_ID_LEN) return "Invalid dose sessionId"
        if (d.substanceId.isBlank() || d.substanceId.length > MAX_ID_LEN) return "Invalid dose substanceId"
        if (d.routeOfAdministration.length > MAX_ROA_LEN) return "Invalid ROA length"
        if (d.unit.length > MAX_UNIT_LEN) return "Invalid unit length"
        if (!d.amount.isFinite()) return "Dose amount must be finite"
        if (d.amount < 0 || d.amount > 1_000_000) return "Invalid dose amount"
        if (d.notes?.length ?: 0 > MAX_FIELD_LEN) return "Dose notes too long"
    }
    return null
}

private fun validateNotes(notes: List<Note>): String? {
    for (n in notes) {
        if (n.id.isBlank() || n.id.length > MAX_ID_LEN) return "Invalid note ID"
        if (!isReasonableEntityTime(n.createdAt)) return "Note createdAt out of range"
        if (!isReasonableEntityTime(n.updatedAt)) return "Note updatedAt out of range"
        if (n.body.length > MAX_FIELD_LEN) return "Note body too long"
        if (n.title?.length ?: 0 > MAX_TITLE_LEN) return "Note title too long"
    }
    return null
}

private fun validateSubstances(substances: List<Substance>): String? {
    for (s in substances) {
        if (s.id.isBlank() || s.id.length > MAX_ID_LEN) return "Invalid substance ID"
        if (!isReasonableEntityTime(s.createdAt)) return "Substance createdAt out of range"
        if (!isReasonableEntityTime(s.updatedAt)) return "Substance updatedAt out of range"
        if (s.name.isBlank() || s.name.length > MAX_NAME_LEN) return "Invalid substance name"
        if (s.aliases.size > 100) return "Too many substance aliases"
        if (s.aliases.any { it.length > MAX_NAME_LEN }) return "Substance alias too long"
        if ((s.summary?.length ?: 0) > MAX_FIELD_LEN) return "Substance summary too long"
    }
    return null
}

private fun validateInteractions(interactions: List<Interaction>): String? {
    val knownRisk = setOf("DANGEROUS", "UNSAFE", "UNCERTAIN", "LOW", "UNKNOWN")
    for (i in interactions) {
        if (i.id.isBlank() || i.id.length > MAX_ID_LEN) return "Invalid interaction ID"
        if (!isReasonableEntityTime(i.createdAt)) return "Interaction createdAt out of range"
        if (!isReasonableEntityTime(i.updatedAt)) return "Interaction updatedAt out of range"
        if (i.substanceAId.isBlank() || i.substanceAId.length > MAX_ID_LEN) return "Invalid interaction substanceAId"
        if (i.substanceBId.isBlank() || i.substanceBId.length > MAX_ID_LEN) return "Invalid interaction substanceBId"
        if (i.riskLevel.name !in knownRisk) return "Invalid interaction risk"
        // Never auto downgrade risk: rejecting unknown risk values keeps the
        // receiver's stored severity intact instead of mapping it to LOW.
        if (i.description?.length ?: 0 > MAX_FIELD_LEN) return "Interaction description too long"
        if (i.sources.size > 50) return "Too many interaction sources"
        if (i.sources.any { it.length > 500 }) return "Interaction source too long"
    }
    return null
}

private fun validateTimelineEvents(events: List<TimelineEvent>): String? {
    for (t in events) {
        if (t.id.isBlank() || t.id.length > MAX_ID_LEN) return "Invalid event ID"
        if (!isReasonableEntityTime(t.createdAt)) return "Event createdAt out of range"
        if (!isReasonableEntityTime(t.updatedAt)) return "Event updatedAt out of range"
        if (!isReasonableEntityTime(t.timestamp)) return "Event timestamp out of range"
        if (t.label.length > MAX_LABEL_LEN) return "TimelineEvent label too long"
        if (t.body?.length ?: 0 > MAX_FIELD_LEN) return "TimelineEvent body too long"
    }
    return null
}

private fun validateEffects(effects: List<Effect>): String? {
    for (e in effects) {
        if (e.id.isBlank() || e.id.length > MAX_ID_LEN) return "Invalid effect ID"
        if (!isReasonableEntityTime(e.createdAt)) return "Effect createdAt out of range"
        if (!isReasonableEntityTime(e.updatedAt)) return "Effect updatedAt out of range"
        if (e.name.isBlank() || e.name.length > MAX_NAME_LEN) return "Invalid effect name"
        if (e.substanceIds.size > 500) return "Too many effect substance IDs"
        if (e.substanceIds.any { it.isBlank() || it.length > MAX_ID_LEN }) return "Invalid effect substanceId"
    }
    return null
}

private fun validateCustomUnits(units: List<CustomUnit>): String? {
    for (u in units) {
        if (u.id.isBlank() || u.id.length > MAX_ID_LEN) return "Invalid unit ID"
        if (!isReasonableEntityTime(u.createdAt)) return "Unit createdAt out of range"
        if (!isReasonableEntityTime(u.updatedAt)) return "Unit updatedAt out of range"
        if (u.name.isBlank() || u.name.length > 100) return "Invalid unit name"
        if (u.substanceId.isBlank() || u.substanceId.length > MAX_ID_LEN) return "Invalid unit substanceId"
    }
    return null
}
