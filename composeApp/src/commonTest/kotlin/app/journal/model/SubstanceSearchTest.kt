package app.journal.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Search must lead with the compound the user names. Substring-only filtering
 * used to bury LSD under its analogs because "1B-LSD" sorts before "LSD".
 */
class SubstanceSearchTest {

    private fun substance(
        name: String,
        aliases: List<String> = emptyList(),
        classes: List<String> = emptyList(),
    ) = Substance(
        id = "test:${name.lowercase()}",
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "test",
        name = name,
        aliases = aliases,
        substanceClass = classes,
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private val lysergamides = listOf(
        substance("1B-LSD"),
        substance("1P-LSD"),
        substance("ALD-52"),
        substance("ETH-LAD"),
        substance("LSD"),
        substance("LSD-25"),
        substance("2C-B"),
    )

    @Test
    fun exactNameRanksFirstAndAnalogsFollow() {
        val ranked = SubstanceSearch.rank(lysergamides, "lsd").map { it.name }
        assertEquals("LSD", ranked.first(), "the exact compound must lead")
        assertEquals("LSD-25", ranked[1], "a name starting with the query comes next")
        assertTrue(ranked.indexOf("1B-LSD") > ranked.indexOf("LSD"))
        assertTrue(ranked.indexOf("1P-LSD") > ranked.indexOf("LSD"))
        assertTrue("ALD-52" !in ranked, "a substance that does not contain the query is not a match")
        assertTrue("2C-B" !in ranked)
    }

    @Test
    fun queryIsCaseInsensitive() {
        assertEquals(
            SubstanceSearch.rank(lysergamides, "lsd").map { it.name },
            SubstanceSearch.rank(lysergamides, "LSD").map { it.name },
        )
        assertEquals("LSD", SubstanceSearch.rank(lysergamides, "LSD").first().name)
    }

    @Test
    fun exactAliasBeatsANameThatOnlyContainsTheLetters() {
        val items = listOf(
            substance("1cP-LSD"),
            substance("Lysergic acid diethylamide", aliases = listOf("LSD")),
        )
        assertEquals(
            "Lysergic acid diethylamide",
            SubstanceSearch.rank(items, "lsd").first().name,
            "an alias exactly equal to the query must beat a substring hit in another name",
        )
    }

    @Test
    fun aClassLabelMatchRanksLast() {
        val items = listOf(
            substance("Ergot derivative", classes = listOf("LSD analog")),
            substance("1P-LSD"),
        )
        val ranked = SubstanceSearch.rank(items, "lsd")
        assertEquals(listOf("1P-LSD", "Ergot derivative"), ranked.map { it.name })
    }

    @Test
    fun tiesFallBackToShorterThenAlphabetical() {
        val items = listOf(
            substance("1P-LSD"),
            substance("1B-LSD"),
            substance("1V-LSD"),
        )
        assertEquals(
            listOf("1B-LSD", "1P-LSD", "1V-LSD"),
            SubstanceSearch.rank(items, "lsd").map { it.name },
            "equal ranks keep a deterministic order",
        )
    }

    @Test
    fun anEmptyQueryKeepsTheBrowseOrder() {
        assertEquals(
            lysergamides.map { it.name },
            SubstanceSearch.rank(lysergamides, "  ").map { it.name },
        )
    }

    @Test
    fun scoreTiersAreOrdered() {
        val exact = SubstanceSearch.score("lsd", "LSD")
        val prefix = SubstanceSearch.score("lsd", "LSD-25")
        val aliasExact = SubstanceSearch.score("lsd", "Lysergic acid diethylamide", aliases = listOf("LSD"))
        // The hyphen makes LSD a word of "1B-LSD", so an analog is a word-start hit,
        // still far behind the exact name above.
        val analogWordStart = SubstanceSearch.score("lsd", "1B-LSD")
        val aliasPrefix = SubstanceSearch.score("lsd", "Something", aliases = listOf("LSD dream"))
        // "Ergot LSD analog" carries the letters mid-label, the weakest tier.
        val classContains = SubstanceSearch.score("lsd", "Something", classes = listOf("Ergot LSD analog"))

        assertEquals(0, exact)
        assertEquals(10, prefix)
        assertEquals(20, aliasExact)
        assertEquals(30, analogWordStart)
        assertEquals(40, aliasPrefix)
        assertEquals(60, classContains)
        assertNull(SubstanceSearch.score("lsd", "Ketamine"))
        assertNull(SubstanceSearch.score("", "LSD"))
    }
}
