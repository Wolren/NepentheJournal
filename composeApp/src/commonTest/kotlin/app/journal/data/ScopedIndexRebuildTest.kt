package app.journal.data

import app.journal.model.Dose
import app.journal.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A bulk apply rebuilds only the index families its entity types feed.
 *
 * Every sync delta goes through [JournalRepository.applyBatch]. The old code
 * rebuilt all nine indices from all stores for any change, so one incoming
 * dose cost a scan of the whole journal. Each family derives from a single
 * store, so an untouched family cannot have changed.
 */
class ScopedIndexRebuildTest {

    private val now = 1_700_000_000_000L

    private fun session(id: String, updatedAt: Long = now) = Session(
        id = id,
        createdAt = now,
        updatedAt = updatedAt,
        deviceOrigin = "scoped-test",
        title = "session $id",
        startTime = now,
    )

    private fun dose(id: String, sessionId: String, updatedAt: Long = now, timestamp: Long = now) = Dose(
        id = id,
        createdAt = now,
        updatedAt = updatedAt,
        deviceOrigin = "scoped-test",
        sessionId = sessionId,
        substanceId = "cid:1",
        routeOfAdministration = "Oral",
        amount = 10.0,
        unit = "mg",
        timestamp = timestamp,
    )

    @Test
    fun `session-only apply does not rebuild the dose index`() {
        val repo = JournalRepository()
        repo.applyBatch(sessions = listOf(session("s:1")))
        val dosesBefore = repo.indexRebuildCount(IndexGroup.DOSES)
        val sessionsBefore = repo.indexRebuildCount(IndexGroup.SESSIONS_BY_DATE)

        repo.applyBatch(sessions = listOf(session("s:2")), lastWriterWins = true)

        assertEquals(dosesBefore, repo.indexRebuildCount(IndexGroup.DOSES),
            "a session write cannot change any dose index")
        assertTrue(repo.indexRebuildCount(IndexGroup.SESSIONS_BY_DATE) > sessionsBefore,
            "the date index still follows the session write")
    }

    @Test
    fun `dose-only apply does not rebuild the note or event index`() {
        val repo = JournalRepository()
        repo.applyBatch(doses = listOf(dose("d:1", "s:1")))
        val notesBefore = repo.indexRebuildCount(IndexGroup.NOTES)
        val eventsBefore = repo.indexRebuildCount(IndexGroup.EVENTS)
        val dosesBefore = repo.indexRebuildCount(IndexGroup.DOSES)

        repo.applyBatch(doses = listOf(dose("d:2", "s:1", updatedAt = now + 1)), lastWriterWins = true)

        assertEquals(notesBefore, repo.indexRebuildCount(IndexGroup.NOTES))
        assertEquals(eventsBefore, repo.indexRebuildCount(IndexGroup.EVENTS))
        assertTrue(repo.indexRebuildCount(IndexGroup.DOSES) > dosesBefore)
    }

    @Test
    fun `a re-parented dose leaves the old session index`() {
        val repo = JournalRepository()
        repo.applyBatch(doses = listOf(dose("d:1", "s:old")))
        assertEquals(listOf("d:1"), repo.dosesForSession("s:old").map { it.id })

        repo.applyBatch(doses = listOf(dose("d:1", "s:new", updatedAt = now + 1)), lastWriterWins = true)

        assertEquals(emptyList(), repo.dosesForSession("s:old").map { it.id },
            "the old session must not keep a stale dose entry")
        assertEquals(listOf("d:1"), repo.dosesForSession("s:new").map { it.id })
    }

    @Test
    fun `deleting a session through a batch clears its child indices`() {
        val repo = JournalRepository()
        repo.applyBatch(sessions = listOf(session("s:1")), doses = listOf(dose("d:1", "s:1")))
        assertEquals(1, repo.dosesForSession("s:1").size)

        repo.applyBatch(
            deletedSessionIds = listOf("s:1"),
            lastWriterWins = true,
            tombstoneCutoff = now + 1_000,
        )

        assertEquals(emptyList(), repo.dosesForSession("s:1").map { it.id })
        assertEquals(emptyList(), repo.sessionIdsOnDateRange())
    }
}
