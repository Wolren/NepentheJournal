package app.journal.ui.substances

import app.journal.model.EffectArticle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectsGroupingTest {

    private fun article(name: String, vararg categories: String, summary: String = "") = EffectArticle(
        name = name,
        categories = categories.toList(),
        summary = summary,
        body = "Body of $name",
    )

    private val articles = listOf(
        article("Anxiety", "cognitive"),
        article("Visual drifting", "sensory", "visual"),
        article("Nausea", "physical"),
        article("Euphoria", summary = "A feeling of well being"),
    )

    @Test
    fun groupsInCategoryOrderWithAnOtherBucket() {
        val groups = groupEffects(articles, query = "")
        assertEquals(listOf("Cognitive", "Physical", "Sensory", "Other"), groups.map { it.label })
        assertEquals(listOf("Anxiety"), groups[0].articles.map { it.name })
        assertEquals(listOf("Nausea"), groups[1].articles.map { it.name })
        assertEquals(listOf("Visual drifting"), groups[2].articles.map { it.name })
        assertEquals(listOf("Euphoria"), groups[3].articles.map { it.name })
    }

    @Test
    fun searchMatchesNameSummaryAndBody() {
        assertEquals(listOf("Euphoria"), groupEffects(articles, "well being").flatMap { it.articles }.map { it.name })
        assertEquals(listOf("Visual drifting"), groupEffects(articles, "DRIFTING").flatMap { it.articles }.map { it.name })
        assertTrue(groupEffects(articles, "body of nausea").flatMap { it.articles }.any { it.name == "Nausea" })
        assertTrue(groupEffects(articles, "nothing here").isEmpty())
    }

    @Test
    fun broadFilterKeepsOnlyThatCategoryAndNamesTheList() {
        val groups = groupEffects(articles, query = "", broad = "physical")
        assertEquals(1, groups.size)
        assertEquals("Physical", groups.single().label)
        assertEquals(listOf("Nausea"), groups.single().articles.map { it.name })
    }

    @Test
    fun specificFilterWinsOverTheBroad() {
        val groups = groupEffects(articles, query = "", broad = "sensory", specific = "visual")
        assertEquals("Visual", groups.single().label)
        assertEquals(listOf("Visual drifting"), groups.single().articles.map { it.name })
    }

    @Test
    fun anArticleWithTwoCategoriesStaysInTheFilteredList() {
        val multi = listOf(article("Anxiety", "cognitive", "physical"))
        val groups = groupEffects(multi, query = "", broad = "physical")
        assertEquals("Physical", groups.single().label)
        assertEquals(listOf("Anxiety"), groups.single().articles.map { it.name })
    }

    @Test
    fun filterOptionsCountBroadsAndSensorySpecifics() {
        val options = effectFilterOptions(articles)
        assertEquals(listOf("cognitive", "physical", "sensory", "visual"), options.map { it.key })
        assertEquals(1, options.first { it.key == "cognitive" }.count)
        assertEquals(null, options.first { it.key == "sensory" }.parent)
        assertEquals("sensory", options.first { it.key == "visual" }.parent)
    }
    @Test
    fun articlesSortAlphabeticallyInsideAGroup() {
        val cognitive = listOf(article("Zoning out", "cognitive"), article("Analysis enhancement", "cognitive"))
        val group = groupEffects(cognitive, "").single()
        assertEquals(listOf("Analysis enhancement", "Zoning out"), group.articles.map { it.name })
    }
}