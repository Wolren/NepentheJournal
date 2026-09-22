package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract section c: the session-outcome conflict branch exists in exactly
 * ONE place, commonMain [mergeSessionConflict], and every host routes
 * through it (JVM SyncServerHandlers.handlePush / its WS forwarder, and iOS
 * IosSyncServerRouter.applySyncBatch; the JVM HTTP leg is pinned end to end
 * in KtorSyncServerIntegrationTest, the iOS leg is reading-verified only
 * because iosMain cannot be compiled on this host).
 *
 * These tests pin the shared function itself against a real repository:
 * both LWW orderings, sender provenance, deleted-id skipping, and
 * idempotent re-application of the same batch context.
 */
class SyncSessionConflictMergeTest {

    private fun session(
        id: String,
        updatedAt: Long,
        title: String = "Session $id",
        outcome: String? = null,
        deviceOrigin: String = "test"
    ) = Session(
        id = id, createdAt = 0L, updatedAt = updatedAt, deviceOrigin = deviceOrigin,
        title = title, startTime = 1_700_000_000_000L, outcome = outcome
    )

    @Test
    fun localNewerThanIncomingCreatesConflictNoteWithBothOutcomes() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1", updatedAt = 200L, title = "Local title", outcome = "local outcome"))
        val incoming = session(
            "s:1", updatedAt = 100L, title = "Remote title",
            outcome = "remote outcome", deviceOrigin = ""
        )
        val conflicts = mergeSessionConflict(repo, listOf(incoming), emptyList(), "peer-a")
        assertEquals(1, conflicts, "local newer than incoming must resolve as one conflict")

        // The newer LOCAL session is untouched.
        val local = repo.getSession("s:1")
        assertEquals(200L, local?.updatedAt, "the newer local session must not be clobbered")
        assertEquals("local outcome", local?.outcome)

        // Both outcomes survive in the deterministic conflict note.
        val note = repo.notes.value.single { it.id == "conflict:s:1:peer-a" }
        assertEquals("s:1", note.sessionId)
        assertEquals("Sync conflict: Remote title", note.title)
        assertEquals("Remote: remote outcome\n\nLocal: local outcome", note.body)
        assertEquals(200L, note.createdAt, "conflict note stamps the newer of the two timestamps")
        assertEquals(200L, note.updatedAt)
        assertEquals("sync:peer-a", note.deviceOrigin, "provenance must name the pushing device")
        assertTrue(note.conflictSiblings.isEmpty())
    }

    @Test
    fun incomingNewerThanLocalWinsAndKeepsProvenance() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1", updatedAt = 100L, outcome = "local outcome"))
        val incoming = session(
            "s:1", updatedAt = 200L, outcome = "remote outcome", deviceOrigin = ""
        )
        val conflicts = mergeSessionConflict(repo, listOf(incoming), emptyList(), "peer-b")
        assertEquals(0, conflicts, "the newer incoming session is not a conflict")
        val applied = repo.getSession("s:1")
        assertEquals(200L, applied?.updatedAt, "newer incoming wins last-writer-wins")
        assertEquals("remote outcome", applied?.outcome)
        assertEquals("sync:peer-b", applied?.deviceOrigin, "blank provenance is filled from the sender")
        assertTrue(repo.notes.value.none { it.id.startsWith("conflict:") },
            "no conflict note may be created when the incoming session wins")
    }

    @Test
    fun nonBlankIncomingProvenanceIsPreserved() {
        val repo = JournalRepository()
        val incoming = session("s:2", updatedAt = 200L, deviceOrigin = "original-origin")
        mergeSessionConflict(repo, listOf(incoming), emptyList(), "peer-b")
        assertEquals("original-origin", repo.getSession("s:2")?.deviceOrigin,
            "an existing deviceOrigin is never rewritten")
    }

    @Test
    fun sessionsListedAsDeletedAreSkippedEntirely() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1", updatedAt = 200L, outcome = "local outcome"))
        val incoming = session("s:1", updatedAt = 100L, outcome = "remote outcome")
        val conflicts = mergeSessionConflict(repo, listOf(incoming), listOf("s:1"), "peer-a")
        assertEquals(0, conflicts, "a session queued for deletion must not conflict")
        assertEquals(200L, repo.getSession("s:1")?.updatedAt, "local state must be untouched")
        assertTrue(repo.notes.value.none { it.id.startsWith("conflict:") })
    }

    @Test
    fun absentLocalSessionIsAppliedDirectly() {
        val repo = JournalRepository()
        val incoming = session("s:new", updatedAt = 100L, deviceOrigin = "")
        val conflicts = mergeSessionConflict(repo, listOf(incoming), emptyList(), "peer-a")
        assertEquals(0, conflicts, "no local copy means no conflict")
        assertEquals("sync:peer-a", repo.getSession("s:new")?.deviceOrigin)
    }

    /**
     * The common applyBatch path, shaped exactly like BOTH host push
     * handlers: truncate the batch context, bulk-apply everything except
     * sessions with last-writer-wins and the batch.since tombstone cutoff,
     * then merge sessions through the shared function. Applying the SAME
     * batch twice must be idempotent: identical state and a conflict count
     * that does not grow.
     */
    @Test
    fun hostShapedBatchApplyIsIdempotentThroughSharedMerge() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:conflict", updatedAt = 200L, outcome = "local outcome"))
        val batch = SyncBatch(
            deviceId = "peer-a", deviceName = "Peer".repeat(100), since = 150L,
            sessions = listOf(session("s:conflict", updatedAt = 100L, outcome = "remote outcome", deviceOrigin = "")),
            deletedSessionIds = listOf("s:deleted-remotely")
        )

        fun applyHostShaped(): Int {
            val tagged = batch.copy(deviceName = batch.deviceName.take(200))
            repo.applyBatch(
                sessions = emptyList(), // hosts never hand sessions to applyBatch
                lastWriterWins = true,
                deletedSessionIds = tagged.deletedSessionIds,
                tombstoneCutoff = tagged.since
            )
            return mergeSessionConflict(repo, tagged.sessions, tagged.deletedSessionIds, tagged.deviceId)
        }

        assertEquals(200, batch.deviceName.take(200).length,
            "deviceName truncation to 200 chars stays at the host call sites")
        val firstCount = applyHostShaped()
        assertEquals(1, firstCount, "the first apply resolves one conflict")
        val conflictsAfterFirst = repo.notes.value.filter { it.id.startsWith("conflict:") }
        assertEquals(1, conflictsAfterFirst.size)
        assertEquals("local outcome", repo.getSession("s:conflict")?.outcome)
        assertNull(repo.getSession("s:deleted-remotely"),
            "a delete id that has no local entity never materializes locally")

        val secondCount = applyHostShaped()
        assertEquals(1, secondCount, "re-applying the same batch context returns the same count")
        assertEquals(conflictsAfterFirst, repo.notes.value.filter { it.id.startsWith("conflict:") },
            "replaying the batch must not duplicate or mutate the conflict note")
        assertEquals("local outcome", repo.getSession("s:conflict")?.outcome,
            "the newer local session still survives the replay")
        assertEquals(1, repo.notes.value.count { it.id == "conflict:s:conflict:peer-a" })
    }
}
