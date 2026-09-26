package app.journal.sync

import app.journal.data.JournalRepository
import app.journal.model.Interaction
import app.journal.model.InteractionRisk
import app.journal.model.Session
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pull-pagination durability for the REAL seed shape: thousands of rows
 * sharing ONE updatedAt.
 *
 * The bundled seed's interactions are all stamped with the ETL timestamp
 * (2015 rows @ 1783942757808). The previous timestamp-only cursor reported
 * `nextSince = T` after page one and then filtered `updatedAt > T`, which
 * matched ZERO remaining rows of the group: the drain ended having
 * delivered 100 of 2015, silently, and the rest became unreachable for
 * that cursor. These tests walk the production handlePull with the
 * composite (nextSince, nextSinceId) cursor and assert complete delivery.
 */
class SyncServerHandlersPullPaginationTest {

    private val repo = JournalRepository()
    private val tied = 1_783_942_757_808L
    private val base = 1_700_000_000_000L

    private fun handlers() = SyncServerHandlers(repo, onConnection = {}, persistAfterApply = null)

    private fun seedTiedInteractions(count: Int, stamp: Long = tied) {
        repeat(count) { i ->
            repo.upsertInteraction(Interaction(
                id = "int:$i",
                createdAt = stamp,
                updatedAt = stamp,
                deviceOrigin = "host",
                substanceAId = "sub:a",
                substanceBId = "sub:b",
                riskLevel = InteractionRisk.LOW,
                description = "tied interaction $i"
            ))
        }
    }

    /** Walk pages the way a client does; fails if the walk cannot terminate. */
    private fun drain(startSince: Long = 0L, startSinceId: String = "", pageBudget: Int = 50): List<SyncResponse> {
        val h = handlers()
        val pages = mutableListOf<SyncResponse>()
        var since = startSince
        var sinceId = startSinceId
        var page = h.handlePull(since, sinceId)
        pages += page
        var guard = 0
        while (page.truncated) {
            assertTrue(++guard < pageBudget, "pull drain must terminate (served ${pages.size} pages)")
            assertTrue(page.nextSince > 0L, "a truncated page must carry a resume cursor")
            assertTrue(page.nextSinceId.isNotEmpty(),
                "a truncated page must carry the id half, else a tie group cannot be walked")
            val nextSince = page.nextSince
            val nextSinceId = page.nextSinceId
            assertTrue(nextSince > since || (nextSince == since && nextSinceId > sinceId),
                "the composite cursor must strictly advance (no stall, no loop)")
            since = nextSince
            sinceId = nextSinceId
            page = h.handlePull(since, sinceId)
            pages += page
        }
        return pages
    }

    private fun allInteractionIds(pages: List<SyncResponse>): Set<String> =
        pages.flatMap { it.interactions.map { i -> i.id } }.toSet()

    @Test
    fun `tied-timestamp interactions are delivered across pages without loss`() {
        seedTiedInteractions(250)

        val first = handlers().handlePull(0L)
        assertTrue(first.truncated, "250 rows exceed the 100-row interaction cap")
        assertEquals(SyncLimits.MAX_INTERACTIONS, first.interactions.size)
        assertEquals(tied, first.nextSince)
        assertTrue(first.nextSinceId.isNotEmpty())

        val pages = drain()
        val served = allInteractionIds(pages)
        assertEquals(250, served.size,
            "every tied row must cross the drain (previous cursor stopped at 100)")
        // Pages after the first may re-serve rows of OTHER types, but within
        // this single-type drain each row must be cut-progressively: no page
        // may be empty while truncated (the old silent-stall signature).
        pages.dropLast(1).forEachIndexed { index, p ->
            assertTrue(p.interactions.isNotEmpty(),
                "page ${index + 2} must contain rows; an empty truncated page means the tie group stalled")
        }
    }

    @Test
    fun `blunt tie group larger than the page cap still terminates`() {
        // 2015 = the real bundled seed size, all one timestamp.
        seedTiedInteractions(2015)
        val pages = drain(pageBudget = 30)
        assertEquals(2015, allInteractionIds(pages).size)
        assertTrue(pages.size <= 25, "expected ceil(2015/100)=21 pages, got ${pages.size}")
    }

    @Test
    fun `untruncated response carries no resume cursor`() {
        seedTiedInteractions(3)
        val page = handlers().handlePull(0L)
        assertFalse(page.truncated)
        assertEquals(0L, page.nextSince)
        assertEquals("", page.nextSinceId)
        assertEquals(3, page.interactions.size)
    }

    @Test
    fun `mixed types are all delivered when only one type truncates`() {
        // Two sessions at distinct timestamps + a tied interaction group
        // that truncates. The low-water cut must not strand the sessions or
        // the tail of the tie group.
        repo.upsertSession(Session(
            id = "s:early", title = "Early", startTime = base,
            createdAt = base, updatedAt = base, deviceOrigin = "host"
        ))
        repo.upsertSession(Session(
            id = "s:late", title = "Late", startTime = tied + 5_000,
            createdAt = tied + 5_000, updatedAt = tied + 5_000, deviceOrigin = "host"
        ))
        seedTiedInteractions(250)

        val pages = drain()
        assertEquals(setOf("s:early", "s:late"),
            pages.flatMap { it.sessions.map { s -> s.id } }.toSet(),
            "sessions must survive a drain truncated by another type")
        assertEquals(250, allInteractionIds(pages).size)
    }

    @Test
    fun `wall-clock cursor is inclusive for rows stamped at exactly since`() {
        // An entity stamped in the cursor's millisecond but missed by the
        // previous cycle would be invisible to a strict `>` filter.
        repo.upsertSession(Session(
            id = "s:edge", title = "Edge", startTime = base,
            createdAt = base, updatedAt = base, deviceOrigin = "host"
        ))
        val page = handlers().handlePull(base)
        assertEquals(listOf("s:edge"), page.sessions.map { it.id },
            "updatedAt == since must be served when the cursor has no id half")
        // A composite cursor, in contrast, is strictly after the cut row.
        val none = handlers().handlePull(base, "s:edge")
        assertTrue(none.sessions.isEmpty())
    }
}
