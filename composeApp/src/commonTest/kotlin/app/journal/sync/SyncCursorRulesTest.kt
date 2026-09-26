package app.journal.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two cursor rules that decide whether a sync cycle can lose data:
 *
 *  - [isAfterPullCursor]: which rows a host serves for a given cursor
 *    (composite (updatedAt, id) resume vs. wall-clock cycle cursor).
 *  - [cursorAfterCycle]: when a client may move its cursor at all
 *    (every slice acked AND the pull drain completed).
 *
 * Both are pure functions on purpose: every branch of the pagination
 * durability story is asserted here without a server.
 */
class SyncCursorRulesTest {

    private val tied = 1_783_942_757_808L

    // ==================== isAfterPullCursor ====================

    @Test
    fun `blank sinceId serves rows at exactly the cursor value`() {
        // Wall-clock cycle cursor: inclusive, because an entity stamped in
        // the cursor's millisecond but missed by the last cycle must be
        // re-served (idempotent) rather than skipped (invisible).
        assertTrue(isAfterPullCursor(tied, "a", since = tied, sinceId = ""))
        assertTrue(isAfterPullCursor(tied + 1, "a", since = tied, sinceId = ""))
        assertFalse(isAfterPullCursor(tied - 1, "a", since = tied, sinceId = ""))
    }

    @Test
    fun `composite cursor walks inside a tied timestamp group`() {
        // Strictly after (tied, "int:50") in (updatedAt, id) order.
        assertTrue(isAfterPullCursor(tied, "int:51", since = tied, sinceId = "int:50"))
        assertTrue(isAfterPullCursor(tied + 1, "int:00", since = tied, sinceId = "int:50"))
        assertFalse(isAfterPullCursor(tied, "int:50", since = tied, sinceId = "int:50"),
            "the cut row itself must not be re-served: that would loop forever")
        assertFalse(isAfterPullCursor(tied, "int:49", since = tied, sinceId = "int:50"),
            "rows before the cut were served by an earlier page")
        assertFalse(isAfterPullCursor(tied - 1, "int:99", since = tied, sinceId = "int:50"))
    }

    @Test
    fun `composite cursor walk over one tie group terminates and covers all rows`() {
        // The exact traversal a drain performs over 250 rows sharing one
        // timestamp with a page cap of 100.
        val ids = (1..250).map { "int:$it" }.sorted()
        var since = 0L
        var sinceId = ""
        val served = mutableListOf<String>()
        var pages = 0
        while (true) {
            val page = ids.filter { isAfterPullCursor(tied, it, since, sinceId) }.sorted()
                .take(100)
            val remaining = ids.count { isAfterPullCursor(tied, it, since, sinceId) }
            served += page
            if (remaining <= 100) break
            val cut = page.last()
            since = tied
            sinceId = cut
            assertTrue(++pages < 10, "composite walk must terminate, got ${pages + 1} pages")
        }
        assertEquals(ids, served.distinct().sorted(), "every row exactly once")
    }

    // ==================== cursorAfterCycle ====================

    private val ackOk = listOf(SyncResponse(success = true))
    private val ackFailed = listOf(SyncResponse(success = false, error = "rejected"))

    @Test
    fun `complete drain with all acks advances to the candidate`() {
        assertEquals(5_000L, cursorAfterCycle(1_000L, 5_000L, ackOk, drainComplete = true))
    }

    @Test
    fun `incomplete drain holds the cursor even with perfect acks`() {
        // The push half succeeded, but undelivered pull pages exist behind
        // the cursor; advancing to cycleStart would skip them for the rest
        // of the process (they stay unreachable until the next cold start).
        assertEquals(1_000L, cursorAfterCycle(1_000L, 5_000L, ackOk, drainComplete = false),
            "an incomplete pull drain must hold the cursor")
    }

    @Test
    fun `failed ack holds the cursor regardless of drain completion`() {
        assertEquals(1_000L, cursorAfterCycle(1_000L, 5_000L, ackFailed, drainComplete = true))
        assertEquals(1_000L, cursorAfterCycle(1_000L, 5_000L, emptyList(), drainComplete = true))
        assertEquals(1_000L, cursorAfterCycle(1_000L, 5_000L, emptyList(), drainComplete = false))
    }
}
