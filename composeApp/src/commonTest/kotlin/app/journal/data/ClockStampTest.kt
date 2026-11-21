package app.journal.data

import app.journal.model.Session
import app.journal.sync.mergeSessionConflict
import app.journal.util.Hlc
import app.journal.util.currentTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Write-path clock discipline: a local upsert always takes a fresh stamp, a sync
 * apply keeps the peer stamp, and the local clock moves past everything it sees.
 */
class ClockStampTest {

    private val base = 1_700_000_000_000L

    private fun session(
        id: String,
        updatedAt: Long = base,
        clock: String = "",
        outcome: String = "same",
    ) = Session(
        id = id,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        deviceOrigin = "a",
        clock = clock,
        title = "Session $id",
        startTime = updatedAt,
        outcome = outcome,
    )

    @Test
    fun aLocalWriteStampsABlankClock() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1"))
        val stored = repo.getSession("s:1")!!
        assertTrue(stored.clock.isNotBlank(), "a local write must carry a stamp")
        assertNotNull(Hlc.parseOrNull(stored.clock))
    }

    @Test
    fun eachLocalWriteGetsAHigherStamp() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1"))
        val first = Hlc.parseOrNull(repo.getSession("s:1")!!.clock)!!
        repo.upsertSession(session("s:1", outcome = "edited"))
        val second = Hlc.parseOrNull(repo.getSession("s:1")!!.clock)!!
        assertTrue(second > first, "the second local write must outrank the first")
    }

    @Test
    fun aDeadlineInTheFutureLiftsTheStamp() {
        val repo = JournalRepository()
        val deadline = currentTimeMillis() + 5_000L
        repo.upsertSession(session("s:future", updatedAt = deadline))
        val stamp = Hlc.parseOrNull(repo.getSession("s:future")!!.clock)!!
        assertTrue(stamp.physicalMs >= deadline, "the clock must not trail the row's own updatedAt")
    }

    @Test
    fun aSyncApplyPreservesThePeerStamp() {
        val repo = JournalRepository()
        val incoming = session("s:1", clock = "$base:3")
        repo.applyRemoteSession(incoming)
        assertEquals("$base:3", repo.getSession("s:1")!!.clock)

        repo.applyBatch(
            sessions = listOf(incoming.copy(outcome = "batch", clock = "$base:9")),
            lastWriterWins = false,
        )
        assertEquals("$base:9", repo.getSession("s:1")!!.clock, "bulk apply must not restamp either")
    }

    @Test
    fun aHigherClockBeatsANewerUpdatedAtOnSync() {
        val repo = JournalRepository()
        repo.applyRemoteSession(session("s:1", clock = "$base:2", outcome = "clock winner"))
        val newerTimestamp = session(
            "s:1",
            updatedAt = base + 60_000L,
            clock = "$base:1",
            outcome = "timestamp winner",
        )

        val conflicts = mergeSessionConflict(repo, listOf(newerTimestamp), emptyList(), "peer")

        assertEquals(1, conflicts, "the losing edit must be reported")
        assertEquals("clock winner", repo.getSession("s:1")!!.outcome)
        assertTrue(
            repo.notes.value.any { it.body.contains("Remote: timestamp winner") },
            "the losing outcome stays in a conflict note",
        )
    }

    @Test
    fun aLocalWriteAfterObservingAPeerClockIsCausallyLater() {
        val repo = JournalRepository()
        val remote = Hlc.of(currentTimeMillis() + 60_000L, 5L)
        repo.applyRemoteSession(session("s:remote", clock = remote.encoded))

        repo.upsertSession(session("s:local"))

        val local = Hlc.parseOrNull(repo.getSession("s:local")!!.clock)!!
        assertTrue(local > remote, "the local write must advance past the observed stamp")
    }
}
