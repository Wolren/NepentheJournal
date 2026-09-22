package app.journal.sync

import app.journal.model.*
import app.journal.util.currentTimeMillis

/**
 * Unified sync payload validation (commonMain).
 *
 * Single validator shared by the JVM host (HTTP push and WS delta routes),
 * the iOS host (push route), and the client-side response guard. This file
 * replaces both former platform copies:
 *   - jvmMain/sync/SyncValidators.kt (deleted)
 *   - iosMain/sync/IosSyncValidators.kt (deleted)
 *
 * Behavior is the STRICTEST union of both copies:
 *   - blank-ID and shape checks on every id and reference id (JVM only before)
 *   - year-2000 floor plus 1-day future margin on every timestamp
 *     (EntityTimePolicy; the iOS 2-year margin is gone)
 *   - all count caps and field-length caps (SyncLimits)
 *   - riskLevel allow-list on interactions (JVM only before)
 *   - blank-name checks on substances, effects, custom units (JVM only before)
 *   - session notes field length (iOS only before)
 *
 * Contract: docs/HARDENING-CONTRACTS-2026-09.md (sections d and e).
 */

// ========== Top-level payload validation ==========

/**
 * Validate a SyncBatch (HTTP push). Returns an error message or null.
 */
fun validateSyncBatch(batch: SyncBatch): String? {
    countCapError(batch)?.let { return it }
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

/**
 * Validate a WsDelta (WebSocket sync). Returns an error message or null.
 * The [WsDelta.since] cursor carries the same bounds as the HTTP since
 * parameter (contract section b).
 */
fun validateWsDelta(delta: WsDelta): String? {
    if (delta.since < 0 || delta.since > currentTimeMillis() + EntityTimePolicy.FUTURE_MARGIN_MS) {
        return "Invalid since"
    }
    countCapError(delta.sessions.size, delta.doses.size, delta.substances.size,
        delta.effects.size, delta.interactions.size, delta.notes.size,
        delta.timelineEvents.size, delta.customUnits.size)?.let { return it }
    validateDeletedIds(
        delta.deletedSessionIds, delta.deletedDoseIds, delta.deletedSubstanceIds,
        delta.deletedEffectIds, delta.deletedInteractionIds, delta.deletedNoteIds,
        delta.deletedTimelineEventIds, delta.deletedCustomUnitIds
    )?.let { return it }

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

// ========== Client response guard (contract: client-response guard) ==========

/**
 * Result of checking a SyncResponse before it is applied locally.
 * [response] carries only the entities and tombstones that passed validation.
 */
data class SyncResponseFilter(
    val response: SyncResponse,
    val skippedEntities: Int,
    val skippedTombstones: Int
)

/**
 * Validate an incoming SyncResponse entity-by-entity before it touches the
 * local store. Rejects entities with blank or over-long ids, over-cap fields,
 * out-of-range timestamps, invalid value ranges, or a disallowed riskLevel,
 * and drops tombstone IDs that are blank or over-long.
 *
 * Count caps are NOT enforced here: servers page responses at SyncLimits
 * caps (contract section d), so an over-cap response is a protocol bug that
 * must surface in tests rather than silently truncate data.
 */
fun validateSyncResponse(response: SyncResponse): SyncResponseFilter {
    var skippedEntities = 0
    var skippedTombstones = 0

    fun <T> keep(items: List<T>, error: (T) -> String?): List<T> = items.filter { item ->
        val ok = error(item) == null
        if (!ok) skippedEntities++
        ok
    }

    fun keepIds(ids: List<String>): List<String> = ids.filter { id ->
        val ok = id.isNotBlank() && id.length <= SyncLimits.MAX_ID_LEN
        if (!ok) skippedTombstones++
        ok
    }

    val clean = response.copy(
        sessions = keep(response.sessions) { validateSession(it) },
        doses = keep(response.doses) { validateDose(it) },
        substances = keep(response.substances) { validateSubstance(it) },
        effects = keep(response.effects) { validateEffect(it) },
        interactions = keep(response.interactions) { validateInteraction(it) },
        notes = keep(response.notes) { validateNote(it) },
        timelineEvents = keep(response.timelineEvents) { validateTimelineEvent(it) },
        customUnits = keep(response.customUnits) { validateCustomUnit(it) },
        deletedSessionIds = keepIds(response.deletedSessionIds),
        deletedDoseIds = keepIds(response.deletedDoseIds),
        deletedNoteIds = keepIds(response.deletedNoteIds),
        deletedSubstanceIds = keepIds(response.deletedSubstanceIds),
        deletedEffectIds = keepIds(response.deletedEffectIds),
        deletedInteractionIds = keepIds(response.deletedInteractionIds),
        deletedTimelineEventIds = keepIds(response.deletedTimelineEventIds),
        deletedCustomUnitIds = keepIds(response.deletedCustomUnitIds)
    )
    return SyncResponseFilter(clean, skippedEntities, skippedTombstones)
}

// ========== Count limits ==========

private fun countCapError(batch: SyncBatch): String? = countCapError(
    batch.sessions.size, batch.doses.size, batch.substances.size,
    batch.effects.size, batch.interactions.size, batch.notes.size,
    batch.timelineEvents.size, batch.customUnits.size
)

private fun countCapError(
    sessions: Int, doses: Int, substances: Int, effects: Int,
    interactions: Int, notes: Int, events: Int, customUnits: Int
): String? {
    if (sessions > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many sessions (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (doses > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many doses (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (substances > SyncLimits.MAX_SUBSTANCES) return "Too many substances (max ${SyncLimits.MAX_SUBSTANCES})"
    if (notes > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many notes (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (events > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many events (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (interactions > SyncLimits.MAX_INTERACTIONS) return "Too many interactions (max ${SyncLimits.MAX_INTERACTIONS})"
    if (effects > SyncLimits.MAX_EFFECTS) return "Too many effects (max ${SyncLimits.MAX_EFFECTS})"
    if (customUnits > SyncLimits.MAX_CUSTOM_UNITS) return "Too many custom units (max ${SyncLimits.MAX_CUSTOM_UNITS})"
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
    if (sessions.size > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many deleted sessions (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (doses.size > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many deleted doses (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (substances.size > SyncLimits.MAX_SUBSTANCES) return "Too many deleted substances (max ${SyncLimits.MAX_SUBSTANCES})"
    if (notes.size > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many deleted notes (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (timelineEvents.size > SyncLimits.MAX_ITEMS_DEFAULT) return "Too many deleted events (max ${SyncLimits.MAX_ITEMS_DEFAULT})"
    if (interactions.size > SyncLimits.MAX_INTERACTIONS) return "Too many deleted interactions (max ${SyncLimits.MAX_INTERACTIONS})"
    if (effects.size > SyncLimits.MAX_EFFECTS) return "Too many deleted effects (max ${SyncLimits.MAX_EFFECTS})"
    if (customUnits.size > SyncLimits.MAX_CUSTOM_UNITS) return "Too many deleted custom units (max ${SyncLimits.MAX_CUSTOM_UNITS})"
    for (id in sessions + doses + substances + effects + interactions + notes + timelineEvents + customUnits) {
        if (id.isBlank() || id.length > SyncLimits.MAX_ID_LEN) return "Invalid deleted ID"
    }
    return null
}

// ========== Entity-level field validation ==========

private fun badId(id: String, what: String): String? =
    if (id.isBlank() || id.length > SyncLimits.MAX_ID_LEN) "Invalid $what" else null

private fun badName(name: String, what: String): String? =
    if (name.isBlank() || name.length > SyncLimits.MAX_NAME_LEN) "Invalid $what" else null

private fun badTime(ts: Long, what: String): String? =
    if (!EntityTimePolicy.isReasonableEntityTime(ts)) "$what out of range" else null

internal fun validateSession(s: Session): String? {
    badId(s.id, "session ID")?.let { return it }
    badTime(s.createdAt, "Session createdAt")?.let { return it }
    badTime(s.updatedAt, "Session updatedAt")?.let { return it }
    badTime(s.startTime, "Session startTime")?.let { return it }
    if (s.endTime != null) badTime(s.endTime, "Session endTime")?.let { return it }
    if (s.title.length > SyncLimits.MAX_TITLE_LEN) return "Session title too long"
    if ((s.set?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Session set too long"
    if ((s.setting?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Session setting too long"
    if ((s.intention?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Session intention too long"
    if ((s.outcome?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Session outcome too long"
    if ((s.notes?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Session notes too long"
    if (s.rating != null && (s.rating < SyncLimits.MIN_SESSION_RATING || s.rating > SyncLimits.MAX_SESSION_RATING)) {
        return "Invalid rating"
    }
    return null
}

internal fun validateDose(d: Dose): String? {
    badId(d.id, "dose ID")?.let { return it }
    badTime(d.createdAt, "Dose createdAt")?.let { return it }
    badTime(d.updatedAt, "Dose updatedAt")?.let { return it }
    badTime(d.timestamp, "Dose timestamp")?.let { return it }
    badId(d.sessionId, "dose sessionId")?.let { return it }
    badId(d.substanceId, "dose substanceId")?.let { return it }
    if (d.routeOfAdministration.length > SyncLimits.MAX_ROA_LEN) return "Invalid ROA length"
    if (d.unit.length > SyncLimits.MAX_UNIT_LEN) return "Invalid unit length"
    if (!d.amount.isFinite()) return "Dose amount must be finite"
    if (d.amount < 0 || d.amount > SyncLimits.MAX_DOSE_AMOUNT) return "Invalid dose amount"
    if ((d.notes?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Dose notes too long"
    return null
}

internal fun validateNote(n: Note): String? {
    badId(n.id, "note ID")?.let { return it }
    badTime(n.createdAt, "Note createdAt")?.let { return it }
    badTime(n.updatedAt, "Note updatedAt")?.let { return it }
    if (n.body.length > SyncLimits.MAX_FIELD_LEN) return "Note body too long"
    if ((n.title?.length ?: 0) > SyncLimits.MAX_TITLE_LEN) return "Note title too long"
    return null
}

internal fun validateSubstance(s: Substance): String? {
    badId(s.id, "substance ID")?.let { return it }
    badTime(s.createdAt, "Substance createdAt")?.let { return it }
    badTime(s.updatedAt, "Substance updatedAt")?.let { return it }
    badName(s.name, "substance name")?.let { return it }
    if (s.aliases.size > SyncLimits.MAX_SUBSTANCE_ALIASES) return "Too many substance aliases"
    if (s.aliases.any { it.length > SyncLimits.MAX_NAME_LEN }) return "Substance alias too long"
    if ((s.summary?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Substance summary too long"
    return null
}

internal fun validateInteraction(i: Interaction): String? {
    val knownRisk = setOf("DANGEROUS", "UNSAFE", "UNCERTAIN", "LOW", "UNKNOWN")
    badId(i.id, "interaction ID")?.let { return it }
    badTime(i.createdAt, "Interaction createdAt")?.let { return it }
    badTime(i.updatedAt, "Interaction updatedAt")?.let { return it }
    badId(i.substanceAId, "interaction substanceAId")?.let { return it }
    badId(i.substanceBId, "interaction substanceBId")?.let { return it }
    if (i.riskLevel.name !in knownRisk) return "Invalid interaction risk"
    // Never auto downgrade risk: rejecting unknown risk values keeps the
    // receiver's stored severity intact instead of mapping it to LOW.
    if ((i.description?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "Interaction description too long"
    if (i.sources.size > SyncLimits.MAX_INTERACTION_SOURCES) return "Too many interaction sources"
    if (i.sources.any { it.length > SyncLimits.MAX_INTERACTION_SOURCE_LEN }) return "Interaction source too long"
    return null
}

internal fun validateTimelineEvent(t: TimelineEvent): String? {
    badId(t.id, "event ID")?.let { return it }
    badTime(t.createdAt, "Event createdAt")?.let { return it }
    badTime(t.updatedAt, "Event updatedAt")?.let { return it }
    badTime(t.timestamp, "Event timestamp")?.let { return it }
    if (t.label.length > SyncLimits.MAX_LABEL_LEN) return "TimelineEvent label too long"
    if ((t.body?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) return "TimelineEvent body too long"
    return null
}

internal fun validateEffect(e: Effect): String? {
    badId(e.id, "effect ID")?.let { return it }
    badTime(e.createdAt, "Effect createdAt")?.let { return it }
    badTime(e.updatedAt, "Effect updatedAt")?.let { return it }
    badName(e.name, "effect name")?.let { return it }
    if (e.substanceIds.size > SyncLimits.MAX_EFFECT_SUBSTANCE_IDS) return "Too many effect substance IDs"
    if (e.substanceIds.any { id -> id.isBlank() || id.length > SyncLimits.MAX_ID_LEN }) return "Invalid effect substanceId"
    return null
}

internal fun validateCustomUnit(u: CustomUnit): String? {
    badId(u.id, "unit ID")?.let { return it }
    badTime(u.createdAt, "Unit createdAt")?.let { return it }
    badTime(u.updatedAt, "Unit updatedAt")?.let { return it }
    if (u.name.isBlank() || u.name.length > SyncLimits.MAX_UNIT_NAME_LEN) return "Invalid unit name"
    badId(u.substanceId, "unit substanceId")?.let { return it }
    return null
}

// ========== Internal list loops ==========

private fun <T> firstError(items: List<T>, check: (T) -> String?): String? {
    for (item in items) {
        check(item)?.let { return it }
    }
    return null
}

private fun validateSessions(sessions: List<Session>): String? = firstError(sessions) { validateSession(it) }
private fun validateDoses(doses: List<Dose>): String? = firstError(doses) { validateDose(it) }
private fun validateNotes(notes: List<Note>): String? = firstError(notes) { validateNote(it) }
private fun validateSubstances(substances: List<Substance>): String? = firstError(substances) { validateSubstance(it) }
private fun validateInteractions(interactions: List<Interaction>): String? = firstError(interactions) { validateInteraction(it) }
private fun validateTimelineEvents(events: List<TimelineEvent>): String? = firstError(events) { validateTimelineEvent(it) }
private fun validateEffects(effects: List<Effect>): String? = firstError(effects) { validateEffect(it) }
private fun validateCustomUnits(units: List<CustomUnit>): String? = firstError(units) { validateCustomUnit(it) }
