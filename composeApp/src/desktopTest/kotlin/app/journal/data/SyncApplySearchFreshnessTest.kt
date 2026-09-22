package app.journal.data

import app.journal.model.Substance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Search freshness for the sync bulk-apply paths (wave5 task 3):
 * JournalSyncBridge.applyBatch and applySnapshot flip the search dirty flag
 * under the repository lock, so entities that arrive from a peer sync (or
 * from a snapshot apply) are visible to the FIRST search after the apply
 * instead of waiting for an unrelated single-entity mutation (wave4
 * residual "applyBatch/applySnapshot never mark the search index dirty").
 *
 * Both tests start from an EMPTY repository and warm the index first (an
 * empty search still runs rebuild-if-dirty and clears the flag), so the
 * property under test is exactly "cold index + bulk apply + first query
 * finds the applied entity". HONEST NOTE (measured, not assumed): a
 * red-proof run with the JournalSyncBridge dirty flip commented out still
 * passed for applyBatch, because JournalIndices.rebuildAllIndices() ends
 * with rebuildSearchIndexLocked() and therefore rebuilds search eagerly on
 * every apply that changed anything. The tests pin the END-TO-END
 * freshness behavior; the explicit dirty flip in the bridge is the
 * belt-and-braces wiring the task mandates, not the only thing holding it.
 */
class SyncApplySearchFreshnessTest {

    private fun substance(id: String, name: String) = Substance(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test", name = name,
        cachedAt = 1000L, sourceVersion = "test"
    )

    /** Build (and clear) the dirty flag on an empty repository. */
    private fun warmEmptyIndex(repo: JournalRepository) {
        assertTrue(repo.search("warmupquery").isEmpty(),
            "fixture: repository starts empty and the search still builds the index (clearing the dirty flag)")
    }

    @Test
    fun applyBatchSyncedInSubstanceIsFoundOnTheFirstSearch() {
        val repo = JournalRepository()
        warmEmptyIndex(repo)

        // A sync pull lands a substance with no other mutation around it.
        repo.applyBatch(substances = listOf(substance("sub:synced", "Zyncfreshin")))

        assertTrue(
            repo.search("zyncfreshin").any { it.entityId == "sub:synced" },
            "an entity applied by applyBatch must be found on the FIRST search after the apply"
        )
    }

    @Test
    fun applySnapshotSyncedInSubstanceIsFoundOnTheFirstSearch() {
        val repo = JournalRepository()
        warmEmptyIndex(repo)

        // A snapshot apply (seed load / restore path) with no other mutation.
        repo.applySnapshot(
            JournalSnapshot(
                savedAt = 1000L,
                substances = listOf(substance("sub:snap", "Snapquill"))
            )
        )

        assertTrue(
            repo.search("snapquill").any { it.entityId == "sub:snap" },
            "an entity applied by applySnapshot must be found on the FIRST search after the apply"
        )
    }
}
