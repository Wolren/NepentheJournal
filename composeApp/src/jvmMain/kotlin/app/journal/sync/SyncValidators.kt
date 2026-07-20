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

private const val MAX_ITEMS_DEFAULT = 500
private const val MAX_SUBSTANCES = 100
private const val MAX_EFFECTS = 100
private const val MAX_INTERACTIONS = 100
private const val MAX_CUSTOM_UNITS = 100
private const val MAX_FIELD_LEN = 65536
private const val MAX_ID_LEN = 128
private const val MAX_NAME_LEN = 200
private const val MAX_TITLE_LEN = 500
private const val MAX_UNIT_LEN = 20
private const val MAX_ROA_LEN = 50
private const val MAX_LABEL_LEN = 200

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
        if (s.id.length > MAX_ID_LEN) return "Session ID too long"
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
        if (d.id.length > MAX_ID_LEN) return "Dose ID too long"
        if (d.sessionId.length > MAX_ID_LEN) return "Dose sessionId too long"
        if (d.substanceId.length > MAX_ID_LEN) return "Dose substanceId too long"
        if (d.routeOfAdministration.length > MAX_ROA_LEN) return "Invalid ROA length"
        if (d.unit.length > MAX_UNIT_LEN) return "Invalid unit length"
        if (d.amount < 0 || d.amount > 1_000_000) return "Invalid dose amount"
        if (d.notes?.length ?: 0 > MAX_FIELD_LEN) return "Dose notes too long"
    }
    return null
}

private fun validateNotes(notes: List<Note>): String? {
    for (n in notes) {
        if (n.id.length > MAX_ID_LEN) return "Note ID too long"
        if (n.body.length > MAX_FIELD_LEN) return "Note body too long"
        if (n.title?.length ?: 0 > MAX_TITLE_LEN) return "Note title too long"
    }
    return null
}

private fun validateSubstances(substances: List<Substance>): String? {
    for (s in substances) {
        if (s.id.length > MAX_ID_LEN) return "Substance ID too long"
        if (s.name.length > MAX_NAME_LEN) return "Substance name too long"
        if (s.aliases.any { it.length > MAX_NAME_LEN }) return "Substance alias too long"
        if ((s.summary?.length ?: 0) > MAX_FIELD_LEN) return "Substance summary too long"
    }
    return null
}

private fun validateInteractions(interactions: List<Interaction>): String? {
    for (i in interactions) {
        if (i.id.length > MAX_ID_LEN) return "Interaction ID too long"
        if (i.substanceAId.length > MAX_ID_LEN) return "Interaction substanceAId too long"
        if (i.substanceBId.length > MAX_ID_LEN) return "Interaction substanceBId too long"
        if (i.description?.length ?: 0 > MAX_FIELD_LEN) return "Interaction description too long"
    }
    return null
}

private fun validateTimelineEvents(events: List<TimelineEvent>): String? {
    for (t in events) {
        if (t.id.length > MAX_ID_LEN) return "TimelineEvent ID too long"
        if (t.label.length > MAX_LABEL_LEN) return "TimelineEvent label too long"
        if (t.body?.length ?: 0 > MAX_FIELD_LEN) return "TimelineEvent body too long"
    }
    return null
}

private fun validateEffects(effects: List<Effect>): String? {
    for (e in effects) {
        if (e.id.length > MAX_ID_LEN) return "Effect ID too long"
        if (e.name.length > MAX_NAME_LEN) return "Effect name too long"
        if (e.substanceIds.any { it.length > MAX_ID_LEN }) return "Effect substanceId too long"
    }
    return null
}

private fun validateCustomUnits(units: List<CustomUnit>): String? {
    for (u in units) {
        if (u.id.length > MAX_ID_LEN) return "Unit ID too long"
        if (u.name.length > 100) return "Unit name too long"
        if (u.substanceId.length > MAX_ID_LEN) return "Unit substanceId too long"
    }
    return null
}
