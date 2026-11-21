package app.journal.data.repository

import app.journal.data.JournalRepository
import app.journal.model.Session
import app.journal.sync.SyncResponse
import app.journal.sync.applySyncResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An exact ordering-key tie must not leave two peers each keeping their own copy. The
 * larger device origin wins, so both sides pick the same record whatever the order.
 * When both rows carry a clock, the clock decides before updatedAt does.
 */
class EntityOrderTest {

    @Test
    fun laterTimestampWins() {
        assertTrue(EntityOrder.incomingWins(200, "a", 100, "b"))
        assertFalse(EntityOrder.incomingWins(100, "b", 200, "a"))
    }

    @Test
    fun aTieIsDecidedByDeviceOrigin() {
        assertTrue(EntityOrder.incomingWins(100, "b", 100, "a"), "b outranks a")
        assertFalse(EntityOrder.incomingWins(100, "a", 100, "b"), "a loses to b")
    }

    @Test
    fun theSameDeviceKeepsItsLaterWrite() {
        assertTrue(EntityOrder.incomingWins(100, "a", 100, "a"))
    }

    @Test
    fun aClockDecidesBeforeUpdatedAtWhenBothSidesCarryOne() {
        assertTrue(
            EntityOrder.incomingWins(100, "200:0", "a", 999, "150:0", "b"),
            "the newer clock wins even with an older updatedAt",
        )
        assertFalse(EntityOrder.incomingWins(999, "150:0", "b", 100, "200:0", "a"))
    }

    @Test
    fun aBlankClockFallsBackToUpdatedAt() {
        assertTrue(
            EntityOrder.incomingWins(999, "", "a", 100, "200:0", "b"),
            "one side pre-v10: updatedAt decides",
        )
        assertFalse(EntityOrder.incomingWins(100, "", "a", 999, "150:0", "b"))
        assertTrue(EntityOrder.incomingWins(999, "999:0", "a", 100, "", "b"))
        assertFalse(EntityOrder.incomingWins(100, "999:0", "a", 999, "", "b"))
    }

    @Test
    fun equalClocksBreakByDeviceOrigin() {
        assertTrue(EntityOrder.incomingWins(100, "200:0", "b", 100, "200:0", "a"))
        assertFalse(EntityOrder.incomingWins(100, "200:0", "a", 100, "200:0", "b"))
    }

    @Test
    fun twoPeersWithEqualClocksConvergeOnOneRecord() {
        val now = System.currentTimeMillis() - 60_000
        val clock = "$now:0"
        val fromA = session("s:tie", now, "a", "from a", clock)
        val fromB = session("s:tie", now, "b", "from b", clock)

        // applyBatch preserves the peer stamp; upsertSession would restamp each side.
        val repoA = JournalRepository().apply {
            applyBatch(sessions = listOf(fromA), lastWriterWins = false)
        }
        val repoB = JournalRepository().apply {
            applyBatch(sessions = listOf(fromB), lastWriterWins = false)
        }

        applySyncResponse(repoA, SyncResponse(success = true, senderDeviceId = "b", sessions = listOf(fromB)))
        applySyncResponse(repoB, SyncResponse(success = true, senderDeviceId = "a", sessions = listOf(fromA)))

        assertEquals("from b", repoA.getSession("s:tie")?.outcome, "the larger device origin wins on both peers")
        assertEquals(repoA.getSession("s:tie")?.outcome, repoB.getSession("s:tie")?.outcome)
    }

    private fun session(id: String, updatedAt: Long, device: String, outcome: String, clock: String) =
        Session(
            id = id,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            deviceOrigin = device,
            clock = clock,
            title = "Session $id",
            startTime = updatedAt,
            outcome = outcome,
        )
}
