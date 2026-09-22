package app.journal.sync

import app.journal.data.IJournalRepository
import app.journal.model.Note
import app.journal.model.Session

/**
 * Shared session-outcome conflict merge (contract section c item 4): ONE
 * commonMain implementation that both sync hosts call, replacing the two
 * former platform copies (JVM SyncServerHandlers.applySessionsWithConflict
 * and the inline loop in iOS IosSyncServerRouter.applySyncBatch).
 *
 * The function takes the repository plus the batch context (the pushed
 * sessions, the batch's delete ids, and the authenticated sender device
 * id). The contract sketched a per-entry pure
 * `mergeSessionConflict(existing, incoming, deviceOrigin)`, but this branch
 * both READS local state and WRITES a conflict note, which is not a pure
 * per-entry merge; the pure note merge lives in the data layer as
 * IJournalRepository.upsertNoteWithConflict. The behavior below is the JVM
 * copy verbatim, so JVM output is byte-identical to before the extraction.
 *
 * Semantics (frozen from the JVM copy):
 *  - a session listed in [deletedIds] is skipped entirely;
 *  - when a LOCAL copy exists and is STRICTLY newer than the incoming
 *    session, the incoming session is NOT applied; a conflict note is
 *    upserted with id `conflict:<sessionId>:<remoteDeviceId>`, title
 *    `Sync conflict: <title>`, body `Remote: <incoming.outcome>` +
 *    `\n\nLocal: <existing.outcome>` (both outcomes preserved), and
 *    createdAt/updatedAt set to the newer of the two timestamps;
 *  - otherwise the incoming session wins (last writer wins) and is stored
 *    with deviceOrigin filled from `sync:<remoteDeviceId>` when blank, so
 *    provenance is never ambiguous;
 *  - the conflict note always carries deviceOrigin `sync:<remoteDeviceId>`
 *    (the pushing device), and its deterministic id makes re-applying the
 *    same batch idempotent: a replay overwrites the note with identical
 *    content and the returned count does not grow.
 *
 * Device-name truncation stays at the two host call sites: both hosts do
 * `batch.copy(deviceName = batch.deviceName.take(200))` before applying
 * (contract c item 4). Sessions and notes never embed deviceName; the
 * truncated name only shapes each host's own connection banner, so moving
 * it here would be dead code rather than shared behavior.
 *
 * iOS drift that this extraction reconciled (both copies diffed first; the
 * JVM copy won): the iOS inline loop discarded the conflict COUNT (it
 * returned Unit) and had a `tagged.deviceName` truncation whose result it
 * never read. Both hosts now receive the Int count; iOS still has no
 * push-connection banner, which is unchanged iOS behavior.
 *
 * @return the number of incoming sessions resolved as conflicts.
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
        val existing = repo.getSession(session.id)
        if (existing != null && existing.updatedAt > session.updatedAt) {
            repo.upsertNote(Note(
                id = "conflict:${session.id}:$remoteDeviceId",
                sessionId = session.id,
                title = "Sync conflict: ${session.title}",
                body = "Remote: ${session.outcome}\n\nLocal: ${existing.outcome}",
                createdAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                updatedAt = session.updatedAt.coerceAtLeast(existing.updatedAt),
                // Tag interaction provenance: conflict notes always carry
                // the pushing device so the origin is never ambiguous.
                deviceOrigin = "sync:$remoteDeviceId"
            ))
            conflicts++
        } else repo.upsertSession(session.copy(deviceOrigin = session.deviceOrigin.ifBlank { "sync:$remoteDeviceId" }))
    }
    return conflicts
}
