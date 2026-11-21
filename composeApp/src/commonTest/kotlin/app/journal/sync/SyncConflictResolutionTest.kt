package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Note
import app.journal.model.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Conflict rules on the pull path: a delete outranks any write older than it, and a
 * newer remote session does not silently replace local session text. Both directions
 * now behave like the server push path.
 */
class SyncConflictResolutionTest {

    private fun session(id: String, updatedAt: Long, outcome: String?, title: String = "Session $id") =
        Session(
            id = id,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            deviceOrigin = "peer",
            title = title,
            startTime = updatedAt,
            outcome = outcome,
        )

    private fun response(
        sessions: List<Session> = emptyList(),
        notes: List<Note> = emptyList(),
        sender: String = "peer-a",
    ) = SyncResponse(
        success = true,
        senderDeviceId = sender,
        sessions = sessions,
        notes = notes,
    )

    @Test
    fun aStalePushDoesNotResurrectADeletedSession() {
        val repo = JournalRepository()
        val now = System.currentTimeMillis()
        repo.upsertSession(session("s:1", now - 60_000, outcome = "old text"))
        repo.deleteSession("s:1")
        assertNull(repo.getSession("s:1"), "the delete must take the session out")

        // A peer that never saw the delete pushes the same row with its old timestamp.
        applySyncResponse(repo, response(sessions = listOf(session("s:1", now - 60_000, outcome = "stale"))))

        assertNull(repo.getSession("s:1"), "a write older than the delete must not resurrect the row")
    }

    @Test
    fun anEditNewerThanTheDeleteIsKept() {
        val repo = JournalRepository()
        val now = System.currentTimeMillis()
        repo.upsertSession(session("s:2", now - 60_000, outcome = "deleted soon"))
        repo.deleteSession("s:2")

        // Timestamped after the delete, so the edit is a later operation and wins.
        applySyncResponse(repo, response(sessions = listOf(session("s:2", now + 1_000, outcome = "recreated"))))

        val restored = repo.getSession("s:2")
        assertNotNull(restored, "an edit newer than the delete must be applied")
        assertEquals("recreated", restored.outcome)
    }

    @Test
    fun anOlderRemoteSessionKeepsTheLocalTextAndRaisesAConflictNote() {
        val repo = JournalRepository()
        val now = System.currentTimeMillis()
        repo.upsertSession(session("s:3", now - 30_000, outcome = "local outcome", title = "Local title"))

        applySyncResponse(
            repo,
            response(
                sessions = listOf(
                    session("s:3", now - 60_000, outcome = "remote outcome", title = "Remote title")
                ),
                sender = "peer-a",
            ),
        )

        val local = repo.getSession("s:3")
        assertEquals("local outcome", local?.outcome, "the newer local session must survive a pull")
        assertEquals(now - 30_000, local?.updatedAt)

        val note = repo.notes.value.singleOrNull { it.id == "conflict:s:3:peer-a" }
        assertNotNull(note, "the pull must record the conflict the way a push does")
        assertEquals("Remote: remote outcome\n\nLocal: local outcome", note.body)
        assertEquals("sync:peer-a", note.deviceOrigin)
    }

    @Test
    fun applyingTheSameResponseTwiceChangesNothing() {
        val repo = JournalRepository()
        val now = System.currentTimeMillis()
        val incoming = response(
            sessions = (0 until 3).map { i -> session("s:idempotent:$i", now - 1_000 - i, outcome = "o$i") },
            notes = listOf(
                Note(
                    id = "n:1",
                    createdAt = now - 5_000,
                    updatedAt = now - 5_000,
                    deviceOrigin = "peer",
                    sessionId = "s:idempotent:0",
                    title = "peer note",
                    body = "body",
                )
            ),
        )

        applySyncResponse(repo, incoming)
        val firstSessions = repo.sessions.value.size
        val firstNotes = repo.notes.value.size
        val firstConflicts = runBlocking { repo.pendingConflictCount.first() }

        applySyncResponse(repo, incoming)

        assertEquals(firstSessions, repo.sessions.value.size, "a replay must not duplicate sessions")
        assertEquals(firstNotes, repo.notes.value.size, "a replay must not duplicate notes")
        assertEquals(firstConflicts, runBlocking { repo.pendingConflictCount.first() }, "a replay must not duplicate conflicts")
        assertTrue(repo.sessions.value.any { it.id == "s:idempotent:0" })
    }
}
