package app.journal.data

import app.journal.model.*
import kotlin.test.*

class InteractionCheckerTest {

    private val now = 1_000_000L

    private fun interaction(id: String, a: String, b: String, risk: InteractionRisk) = Interaction(
        id = id, createdAt = now, updatedAt = now, deviceOrigin = "test",
        substanceAId = a, substanceBId = b, riskLevel = risk,
        description = "test interaction"
    )

    @Test
    fun pairwiseCheckFindsDangerousPair() {
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1", "cid:2"),
            allInteractions = listOf(
                interaction("i:1", "cid:1", "cid:2", InteractionRisk.DANGEROUS)
            )
        )
        assertTrue(result.hasIssues)
        assertEquals(1, result.dangerous.size)
        assertTrue(result.unsafe.isEmpty())
    }

    @Test
    fun pairwiseCheckFindsUnsafePair() {
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1", "cid:2"),
            allInteractions = listOf(
                interaction("i:1", "cid:1", "cid:2", InteractionRisk.UNSAFE)
            )
        )
        assertFalse(result.dangerous.isNotEmpty())
        assertEquals(1, result.unsafe.size)
    }

    @Test
    fun singleIdReturnsEmpty() {
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1"),
            allInteractions = emptyList()
        )
        assertFalse(result.hasIssues)
        assertEquals(0, result.totalCount)
    }

    @Test
    fun emptyIdsReturnsEmpty() {
        val result = InteractionChecker.checkPairwise(
            ids = emptyList(),
            allInteractions = emptyList()
        )
        assertFalse(result.hasIssues)
    }

    @Test
    fun noKnownInteractionReturnsEmpty() {
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1", "cid:2", "cid:3"),
            allInteractions = listOf(
                interaction("i:1", "cid:4", "cid:5", InteractionRisk.DANGEROUS)
            )
        )
        assertFalse(result.hasIssues)
    }

    @Test
    fun pairwiseChecksAllPairsInNIds() {
        // With 4 IDs there are 6 pairs to check
        val allInteractions = listOf(
            interaction("i:1", "cid:1", "cid:2", InteractionRisk.DANGEROUS),
            interaction("i:2", "cid:3", "cid:4", InteractionRisk.UNSAFE),
            interaction("i:3", "cid:1", "cid:3", InteractionRisk.UNCERTAIN)
        )
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1", "cid:2", "cid:3", "cid:4"),
            allInteractions = allInteractions
        )
        assertEquals(1, result.dangerous.size)
        assertEquals(1, result.unsafe.size)
        assertEquals(1, result.uncertain.size)
    }

    @Test
    fun checkAgainstExistingWorks() {
        val allInteractions = listOf(
            interaction("i:1", "cid:1", "cid:2", InteractionRisk.DANGEROUS)
        )
        val result = InteractionChecker.checkAgainstExisting(
            newId = "cid:1",
            existingIds = listOf("cid:2", "cid:3"),
            allInteractions = allInteractions
        )
        assertEquals(1, result.dangerous.size)
        assertTrue(result.unsafe.isEmpty())
    }

    @Test
    fun checkAgainstExistingSkipsSelf() {
        val allInteractions = emptyList<Interaction>()
        val result = InteractionChecker.checkAgainstExisting(
            newId = "cid:1",
            existingIds = listOf("cid:1"),
            allInteractions = allInteractions
        )
        assertFalse(result.hasIssues)
    }

    @Test
    fun indexCacheCachesAcrossCalls() {
        val interactions = listOf(
            interaction("i:1", "cid:1", "cid:2", InteractionRisk.DANGEROUS)
        )
        // First call builds cache
        InteractionChecker.checkPairwise(listOf("cid:1", "cid:2"), interactions)
        // Second call should use cached index
        val result = InteractionChecker.checkPairwise(listOf("cid:1", "cid:2"), interactions)
        assertEquals(1, result.dangerous.size)
    }

    @Test
    fun indexRebuildsOnDifferentData() {
        val interactions1 = listOf(
            interaction("i:1", "cid:1", "cid:2", InteractionRisk.DANGEROUS)
        )
        val interactions2 = listOf(
            interaction("i:2", "cid:1", "cid:3", InteractionRisk.UNSAFE)
        )
        val r1 = InteractionChecker.checkPairwise(listOf("cid:1", "cid:2"), interactions1)
        assertEquals(1, r1.dangerous.size)
        val r2 = InteractionChecker.checkPairwise(listOf("cid:1", "cid:3"), interactions2)
        assertEquals(1, r2.unsafe.size)
    }

    @Test
    fun idOrderingDoesNotMatter() {
        val interactions = listOf(
            interaction("i:1", "cid:2", "cid:1", InteractionRisk.DANGEROUS)
        )
        val result = InteractionChecker.checkPairwise(
            ids = listOf("cid:1", "cid:2"),
            allInteractions = interactions
        )
        assertEquals(1, result.dangerous.size)
    }
}
