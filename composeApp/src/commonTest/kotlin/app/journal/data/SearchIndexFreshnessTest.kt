package app.journal.data

import app.journal.model.*
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Search freshness pins (wave4 tasks 1 and 3): rebuild-if-dirty still runs
 * under the repo lock before the snapshot is taken, so results are never
 * stale no matter where scoring happens; and upsertSessionChildren now flips
 * the O(1) dirty flag itself (it used to flip nothing, which silently hid
 * batch-saved children from search until some other mutation rebuilt the
 * index).
 *
 * Every test warms the index first: without a built index the dirty-flag
 * path would be trivially satisfied by the initial dirty = true state.
 */
class SearchIndexFreshnessTest {

    private fun session(id: String, title: String) = Session(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        title = title, startTime = 1720728000000L, endTime = 1720742400000L
    )

    private fun dose(id: String, sessionId: String, substanceId: String, notes: String) = Dose(
        id = id, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        sessionId = sessionId, substanceId = substanceId,
        routeOfAdministration = "Oral", amount = 10.0, unit = "mg",
        timestamp = 1720728000000L, notes = notes
    )

    @Test
    fun justUpsertedEntityIsFoundOnTheNextQuery() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:warm", "Launchpad"))
        // Warm the index so the dirty flag is cleared: what follows must come
        // from the rebuild-if-dirty path inside JournalSearch.search.
        assertTrue(repo.search("launchpad").isNotEmpty(), "fixture: index built and warm")

        repo.upsertNote(
            Note(
                id = "n:fresh", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                sessionId = null, title = "Fresh note", body = "zephyrgadget sighting"
            )
        )
        assertTrue(
            repo.search("zephyrgadget").any { it.entityId == "n:fresh" },
            "an entity upserted after the index was built must be found on the first query"
        )
    }

    @Test
    fun deletedEntityVanishesOnTheNextQuery() {
        val repo = JournalRepository()
        repo.upsertNote(
            Note(
                id = "n:gone", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                sessionId = null, title = "Temp", body = "halcyonparticle"
            )
        )
        assertTrue(repo.search("halcyonparticle").isNotEmpty(), "fixture: built and found")

        repo.deleteNote("n:gone")
        assertTrue(repo.search("halcyonparticle").isEmpty(),
            "deletion must invalidate the index before the snapshot is taken")
    }

    @Test
    fun sessionChildBatchSaveIsSearchFreshOnFirstQuery() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:batch", "Launchpad two"))
        assertTrue(repo.search("launchpad").isNotEmpty(), "fixture: index built and warm")

        // Task 3: the batch save writes stores directly; before wave4 it never
        // flipped the dirty flag, so BOTH searches below missed the children
        // (the index stayed clean from the warm-up query).
        repo.upsertSessionChildren(
            sessionId = "s:batch",
            doses = listOf(dose("d:batch", "draft:other", "sub:x", notes = "xylophonequark")),
            timelineEvents = listOf(
                TimelineEvent(
                    id = "e:batch", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                    sessionId = "draft:other", timestamp = 1720730000000L,
                    eventType = TimelineEventType.PEAK, label = "burbleflux"
                )
            )
        )

        assertTrue(
            repo.search("xylophonequark").any { it.entityId == "d:batch" },
            "dose saved through upsertSessionChildren must be searchable on the first query"
        )
        assertTrue(
            repo.search("burbleflux").any { it.entityId == "e:batch" },
            "timeline event saved through upsertSessionChildren must be searchable on the first query"
        )
        // Re-parenting still happened (draft session id -> real session id).
        assertTrue(
            repo.search("xylophonequark").any { it.entityId == "d:batch" } &&
                repo.doses.value.first().sessionId == "s:batch",
            "batch save re-parents children exactly as before"
        )
    }

    @Test
    fun emptySessionChildBatchStillASearchNoOp() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:noop", "Launchpad three"))
        assertTrue(repo.search("launchpad").isNotEmpty(), "fixture: index built and warm")

        repo.upsertSessionChildren("s:noop", emptyList(), emptyList())
        assertTrue(repo.search("launchpad").isNotEmpty(),
            "an empty batch must not disturb existing search results")
    }
}
