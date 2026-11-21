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
 * An exact timestamp tie must not leave two peers each keeping their own copy. The
 * larger device origin wins, so both sides pick the same record whatever the order.
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
    fun twoPeersWithEqualTimestampsConvergeOnOneRecord() {
        val now = System.currentTimeMillis()
        val fromA = session("s:tie", now - 60_000, "a", "from a")
        val fromB = session("s:tie", now - 60_000, "b", "from b")

        val repoA = JournalRepository().apply { applyBatch(sessions = listOf(fromA)) }
        val repoB = JournalRepository().apply { applyBatch(sessions = listOf(fromB)) }

        applySyncResponse(repoA, SyncResponse(success = true, senderDeviceId = "b", sessions = listOf(fromB)))
        applySyncResponse(repoB, SyncResponse(success = true, senderDeviceId = "a", sessions = listOf(fromA)))

        assertEquals("from b", repoA.getSession("s:tie")?.outcome, "the larger device origin wins on both peers")
        assertEquals(repoA.getSession("s:tie")?.outcome, repoB.getSession("s:tie")?.outcome)
    }

    private fun session(id: String, updatedAt: Long, device: String, outcome: String) =
        Session(
            id = id,
            createdAt = updatedAt,
            updatedAt = updatedAt,
            deviceOrigin = device,
            title = "Session $id",
            startTime = updatedAt,
            outcome = outcome,
        )
}
