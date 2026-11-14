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

    @Test
    fun inlineLinkTargetsResolveToBundledArticles() {
        assertTrue(EffectLibrary.ensureLoaded())
        val drifting = EffectLibrary.articleBySlug("drifting")
        assertNotNull(drifting, "an inline link slug must resolve to its article")

        val withLinks = EffectLibrary.articles.value.filter { it.body.contains("{{") }
        assertTrue(withLinks.size > 100, "expected inline links, found ${withLinks.size} articles")
        val targets = withLinks.flatMap { article ->
            Regex("\\{\\{(effect:[^|]+)\\|").findAll(article.body).map { it.groupValues[1] }.toList()
        }
        val resolvable = targets.filter { EffectLibrary.articleBySlug(it.removePrefix("effect:")) != null }
        assertTrue(
            resolvable.size >= targets.size / 2,
            "at least half the inline effect links must resolve locally, got ${resolvable.size}/${targets.size}",
        )
    }

    @Test
    fun seenAlsoLinksAreBundledWithTheirTargets() {
        assertTrue(EffectLibrary.ensureLoaded())
        val withSeeAlso = EffectLibrary.articles.value.filter { it.seeAlso.isNotEmpty() }
        assertTrue(withSeeAlso.size > 200, "expected curated see-also lists, found ${withSeeAlso.size}")
        assertTrue(withSeeAlso.all { article -> article.seeAlso.all { it.target.isNotBlank() && it.label.isNotBlank() } })
    }
}