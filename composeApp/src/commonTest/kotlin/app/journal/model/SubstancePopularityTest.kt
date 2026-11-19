package app.journal.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Popularity only ever breaks a tie in [SubstanceSearch]; these tests pin the
 * lookup itself and the behaviour the user asked for: the household compound
 * leads among equally good matches.
 */
class SubstancePopularityTest {

    @Test
    fun theHouseholdNamesAreRankedFirst() {
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("LSD"))
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("MDMA"))
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("Ketamine"))
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("2C-B"))
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("Alprazolam"))
        assertEquals(SubstancePopularity.TIER_HOUSEHOLD, SubstancePopularity.tierOf("Kratom"))
    }

    @Test
    fun lookupIgnoresCaseAndSurroundingSpace() {
        assertEquals(
            SubstancePopularity.tierOf("LSD"),
            SubstancePopularity.tierOf("  lsd  "),
        )
    }

    @Test
    fun analogsSitBelowTheParent() {
        assertTrue(SubstancePopularity.tierOf("1P-LSD") > SubstancePopularity.tierOf("LSD"))
        assertTrue(SubstancePopularity.tierOf("1B-LSD") > SubstancePopularity.tierOf("1P-LSD"))
        assertTrue(SubstancePopularity.tierOf("2C-T-7") > SubstancePopularity.tierOf("2C-B"))
    }

    @Test
    fun anUnlistedSubstanceRanksAfterEveryListedOne() {
        assertEquals(SubstancePopularity.TIER_UNRANKED, SubstancePopularity.tierOf("Some Obscure Compound"))
    }

    @Test
    fun everyTierHasEntries() {
        val counts = SubstancePopularity.tiers.values.groupingBy { it }.eachCount()
        assertTrue((counts[SubstancePopularity.TIER_HOUSEHOLD] ?: 0) >= 30)
        assertTrue((counts[SubstancePopularity.TIER_KNOWN] ?: 0) >= 60)
        assertTrue((counts[SubstancePopularity.TIER_NICHE] ?: 0) >= 50)
    }

    @Test
    fun rankingPrefersThePopularMatchWithinOneQualityTier() {
        fun sub(name: String) = Substance(
            id = "test:$name", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            name = name, cachedAt = 0L, sourceVersion = "test",
        )
        // Both are name-prefix hits for "2c": popularity must order them, and an
        // unlisted analog stays behind both.
        val ranked = SubstanceSearch.rank(
            listOf(sub("2C-T-7"), sub("2C-E"), sub("2C-B"), sub("2C-ZZZ")),
            "2c",
        ).map { it.name }
        assertEquals(listOf("2C-B", "2C-E", "2C-T-7", "2C-ZZZ"), ranked)
    }
}
