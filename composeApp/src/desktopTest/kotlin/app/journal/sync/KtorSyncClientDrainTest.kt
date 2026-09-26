package app.journal.sync

import app.journal.data.JournalRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Unit coverage for [KtorSyncClient.drainPullPages], the loop that walks a
 * paginated pull to its end.
 *
 * The contract under test: `complete` is true ONLY when a page arrived with
 * truncated=false. Every other exit — page-budget exhaustion, a cursor that
 * cannot advance inside a tie group, a failed fetch, a rejected page — must
 * report complete=false, because the transport holds its sync cursor
 * ([cursorAfterCycle]) whenever the drain is incomplete. A complete=true
 * here is what lets the cursor advance to cycleStart, so a false positive
 * would skip whatever never arrived.
 *
 * Scripted fetch/apply lambdas stand in for the network, so each failure
 * branch is reachable deterministically.
 */
class KtorSyncClientDrainTest {

    private val client = KtorSyncClient(repo = JournalRepository())
    private val tied = 1_783_942_757_808L

    @AfterTest
    fun tearDown() {
        client.close()
    }

    private fun page(
        truncated: Boolean,
        nextSince: Long = 0L,
        nextSinceId: String = "",
        success: Boolean = true
    ) = SyncResponse(success = success, truncated = truncated, nextSince = nextSince, nextSinceId = nextSinceId)

    @Test
    fun `untruncated first page completes without fetching`() = runBlocking {
        val first = page(truncated = false)
        val fetched = mutableListOf<Pair<Long, String>>()
        val applied = mutableListOf<SyncResponse>()

        val r = client.drainPullPages(
            first = first, firstSince = 0L, firstSinceId = "", pageLimit = 10,
            fetchPage = { ts, id -> fetched += ts to id; Result.success(page(truncated = false)) },
            applyPage = { p, _ -> applied += p }
        )

        assertTrue(r.complete, "a single untruncated page is a complete drain")
        assertEquals(listOf(first), applied, "the first page must be applied exactly once")
        assertTrue(fetched.isEmpty(), "no continuation fetch is needed")
    }

    @Test
    fun `composite cut is forwarded verbatim on every continuation fetch`() = runBlocking {
        // The tie-group shape: every resume is inside ONE timestamp, so the
        // id half is the only thing that moves the cursor.
        val p1 = page(truncated = true, nextSince = tied, nextSinceId = "int:100")
        val p2 = page(truncated = true, nextSince = tied, nextSinceId = "int:200")
        val p3 = page(truncated = false)
        val byCursor = mapOf((tied to "int:100") to p2, (tied to "int:200") to p3)
        val fetched = mutableListOf<Pair<Long, String>>()
        val applied = mutableListOf<SyncResponse>()

        val r = client.drainPullPages(
            first = p1, firstSince = 0L, firstSinceId = "", pageLimit = 10,
            fetchPage = { ts, id ->
                fetched += ts to id
                Result.success(byCursor[ts to id] ?: error("unexpected cursor $ts/$id"))
            },
            applyPage = { p, since -> applied += p; assertTrue(since >= 0L) }
        )

        assertTrue(r.complete, "a walk that ends untruncated is complete")
        assertEquals(
            listOf(tied to "int:100", tied to "int:200"),
            fetched,
            "each fetch must resume at the server's composite (nextSince, nextSinceId)"
        )
        assertEquals(listOf(p1, p2, p3), applied, "pages must be applied in order, once each")
        assertFalse(r.lastPage.truncated, "the last page ends the drain")
    }

    @Test
    fun `page budget exhaustion reports incomplete instead of advancing`() = runBlocking {
        // limit=2: first page applied (pages=1), one continuation fetched
        // (pages=2), then the bound trips BEFORE a third page could arrive.
        val p1 = page(truncated = true, nextSince = tied, nextSinceId = "int:1")
        val p2 = page(truncated = true, nextSince = tied, nextSinceId = "int:2")
        val fetched = mutableListOf<Pair<Long, String>>()
        val applied = mutableListOf<SyncResponse>()

        val r = client.drainPullPages(
            first = p1, firstSince = 0L, firstSinceId = "", pageLimit = 2,
            fetchPage = { ts, id ->
                fetched += ts to id
                Result.success(if (fetched.size == 1) p2 else page(truncated = false))
            },
            applyPage = { p, _ -> applied += p }
        )

        assertFalse(r.complete, "hitting the page bound must NOT report a complete drain")
        assertEquals(1, fetched.size, "the bound stops further fetches")
        assertEquals(listOf(p1, p2), applied)
        assertTrue(r.lastPage.truncated, "the incomplete flag travels with the last arrived page")
    }

    @Test
    fun `cursor that cannot advance stalls the drain as incomplete`() = runBlocking {
        // An old server that splits a tie group AT the cursor value: it
        // reports truncated but its nextSince equals the cursor, so the
        // remaining rows of the group are unreachable by this protocol.
        // Looping would re-serve page one forever; stopping is correct.
        val first = page(truncated = true, nextSince = 500L, nextSinceId = "")
        var fetches = 0

        val r = client.drainPullPages(
            first = first, firstSince = 500L, firstSinceId = "", pageLimit = 10,
            fetchPage = { _, _ -> fetches++; Result.success(page(truncated = false)) },
            applyPage = { _, _ -> }
        )

        assertFalse(r.complete, "a stalled cursor is an incomplete drain")
        assertEquals(0, fetches, "no progress means no continuation fetch")
    }

    @Test
    fun `fetch failure reports incomplete`() = runBlocking {
        val first = page(truncated = true, nextSince = tied, nextSinceId = "int:1")

        val r = client.drainPullPages(
            first = first, firstSince = 0L, firstSinceId = "", pageLimit = 10,
            fetchPage = { _, _ -> Result.failure(Exception("connection reset")) },
            applyPage = { _, _ -> }
        )

        assertFalse(r.complete, "a failed continuation fetch must hold the cursor")
        assertEquals(first, r.lastPage, "the last ARRIVED page is reported, not a fabricated one")
    }

    @Test
    fun `rejected page reports incomplete`() = runBlocking {
        val first = page(truncated = true, nextSince = tied, nextSinceId = "int:1")
        val rejected = page(truncated = false, success = false)

        val r = client.drainPullPages(
            first = first, firstSince = 0L, firstSinceId = "", pageLimit = 10,
            fetchPage = { _, _ -> Result.success(rejected) },
            applyPage = { _, _ -> }
        )

        assertFalse(r.complete, "a rejected page must not be counted as delivered")
        assertFalse(r.lastPage.success, "the caller sees the rejected page")
    }
}
