package app.journal.data

import app.journal.model.EffectArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the bundled artifact, not an inline fixture: a regenerated effects.json
 * with a broken shape must fail here instead of silently shipping an empty card.
 */
class EffectLibraryTest {

    @Test
    fun bundledEffectArticlesParseAndCoverBothSources() {
        assertTrue(EffectLibrary.ensureLoaded(), "the bundled effect library must load")

        val articles = EffectLibrary.articles.value
        assertTrue(articles.size >= 250, "expected the full bundle, got ${articles.size}")
        assertTrue(articles.all { it.name.isNotBlank() }, "every article needs a name")
        assertTrue(articles.all { it.hasText }, "every article needs text")

        val sources = articles.map { it.source }.toSet()
        assertEquals(setOf("dosewiki", "psychonautwiki"), sources, "both fallback sources must be present")

        val anxiety = EffectLibrary.article("Anxiety")
        assertNotNull(anxiety, "Anxiety must resolve by label")
        assertTrue(anxiety.body.length > 80, "the article body is the offline text")

        // Lookup ignores case and punctuation so a chip label still resolves.
        assertEquals(anxiety.name, EffectLibrary.article("anxiety")?.name)
        assertEquals(anxiety.name, EffectLibrary.article("  ANXIETY ")?.name)
    }

    @Test
    fun everySubstanceEffectLabelThatHasAnArticleResolvesThroughTheLookupForm() {
        assertTrue(EffectLibrary.ensureLoaded())
        val article = EffectLibrary.article("Visual drifting")
        assertNotNull(article, "a redirect-matched PsychonautWiki article must resolve")

        val matched = EffectArticle(name = "Auditory distortions", source = "psychonautwiki")
        assertEquals("psychonautwiki", matched.source)
    }
}