package app.journal.ui.substances

import app.journal.model.EffectArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The effect search runs on every keystroke. The lowercased article text must
 * be built once per loaded library, not once per article per keystroke.
 */
class EffectSearchTextTest {

    private fun article(name: String) = EffectArticle(
        name = name,
        summary = "summary of $name",
        body = "body of $name",
    )

    @Test
    fun aLoadedLibraryIsLowercasedOncePerArticle() {
        val articles = (1..6).map { article("Effect $it") }
        val index = EffectSearchText(articles)

        repeat(300) { round -> index.matches(articles[round % articles.size], "effect") }

        assertEquals(
            articles.size,
            index.lowercaseCalls,
            "one lowercase build per article, never one per search",
        )
    }

    @Test
    fun matchingStillFindsNameSummaryAndBody() {
        val effect = article("Visual drifting")
        val index = EffectSearchText(listOf(effect))

        assertTrue(index.matches(effect, "drifting"), "name must match")
        assertTrue(index.matches(effect, "summary"), "summary must match")
        assertTrue(index.matches(effect, "body"), "body must match")
        assertFalse(index.matches(effect, "zzz"), "an absent needle must not match")
    }
}
