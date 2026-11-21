package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.data.repository.FieldRegisters
import app.journal.model.Note
import app.journal.model.Session

/**
 * Shared session-outcome conflict merge (contract section c item 4): ONE
 * commonMain implementation that both sync hosts call, replacing the two
 * former platform copies (JVM SyncServerHandlers.applySessionsWithConflict
 * and the inline loop in iOS IosSyncServerRouter.applySyncBatch).
 *
 * Phase 3 made this a per-field merge: a session edit no longer replaces the whole
 * row. [FieldRegisters.merge] joins both copies field by field, so a title edit on
 * one peer and an outcome edit on the other both survive. A pair that includes a
 * pre-v10 row (blank clock) keeps the whole-row updatedAt rule.
 *
 * Semantics:
 *  - a session listed in [deletedIds] is skipped entirely;
 *  - a tombstone at least as new as the incoming session skips it (phase 1 rule);
 *  - the merged session is stored only when it differs from the local copy;
 *  - an outcome CONFLICT note (id `conflict:<sessionId>:<remoteDeviceId>`,
 *    title `Sync conflict: <title>`, body `Remote: <incoming.outcome>` +
 *    `\n\nLocal: <existing.outcome>`) is written only when the incoming outcome
 *    loses AND the incoming row stamped its outcome in its latest write. A stale
 *    outcome copied along with an unrelated title edit therefore stays silent;
 *  - the note's deterministic id makes re-applying the same batch idempotent.
 *
 * Device-name truncation stays at the two host call sites: both hosts do
 * `batch.copy(deviceName = batch.deviceName.take(200))` before applying
 * (contract c item 4).
 *
 * @return the number of incoming sessions resolved as outcome conflicts.
 */
fun mergeSessionConflict(
    repo: IJournalRepository,
    sessions: List<Session>,
    deletedIds: List<String>,
    remoteDeviceId: String
): Int {
    var conflicts = 0
    sessions.forEach { session ->
        if (session.id in deletedIds) return@forEach
        // A delete that is newer than this edit outranks it, so a peer that never saw the
        // delete cannot bring the session back.
        val deletedAt = repo.tombstoneDeletedAt("session", session.id)
        if (deletedAt != null && session.updatedAt <= deletedAt) return@forEach
        val existing = repo.getSession(session.id)
        if (existing == null) {
            repo.applyRemoteSession(
                session.copy(deviceOrigin = session.deviceOrigin.ifBlank { "sync:$remoteDeviceId" })
            )
            return@forEach
        }
        val merged = FieldRegisters.merge(existing, session, Session.serializer())
        if (merged != existing) {
            repo.applyRemoteSession(
                merged.copy(deviceOrigin = merged.deviceOrigin.ifBlank { "sync:$remoteDeviceId" })
            )
        }
        // Only a row that wrote its outcome in the latest write can conflict on it.
        // A pre-v10 row has no field clocks, so its row stamp stands in and the old
        // whole-row rule applies.
        val incomingWroteOutcome =
            FieldRegisters.fieldStamp(session, "outcome") == FieldRegisters.rowStamp(session)
        val outcomeLost = incomingWroteOutcome &&
            session.outcome != existing.outcome &&
            !FieldRegisters.fieldWins(session, existing, "outcome", Session.serializer())
        if (outcomeLost) {
            repo.upsertNote(Note(
                id = "conflict:${session.id}:$remoteDeviceId",
                sessionId = session.id,
                title = "Sync conflict: ${session.title}",
                body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                deviceOrigin = "sync:$remoteDeviceId"
            ))
            conflicts++
        }
    }
    return conflicts
}
