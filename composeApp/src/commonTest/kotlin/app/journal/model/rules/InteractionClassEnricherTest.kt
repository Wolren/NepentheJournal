package app.journal.model.rules

import app.journal.model.Substance
import kotlin.test.*

class InteractionClassEnricherTest {

    private fun substance(
        name: String,
        substanceClass: List<String> = emptyList(),
        interactionClasses: List<String> = emptyList(),
    ) = Substance(
        id = "cid:${name.hashCode()}",
        name = name,
        createdAt = 0L,
        updatedAt = 0L,
        deviceOrigin = "test",
        substanceClass = substanceClass,
        interactionClasses = interactionClasses,
        cachedAt = 0L,
        sourceVersion = "test",
    )

    @Test
    fun ssriNameGetsSsriAndSerotonergicClasses() {
        val enriched = InteractionClassEnricher.enrich(substance("Sertraline", listOf("antidepressant")))
        assertTrue("ssri" in enriched.interactionClasses)
        assertTrue("serotonergic" in enriched.interactionClasses)
    }

    @Test
    fun chemicalBenzodiazepineLabelGetsBenzodiazepineClass() {
        val enriched = InteractionClassEnricher.enrich(
            substance("Alprazolam", listOf("depressant", "benzodiazepine (triazolobenzodiazepine)"))
        )
        assertTrue("benzodiazepine" in enriched.interactionClasses)
    }

    @Test
    fun entactogenClassGetsSerotoninReleaser() {
        val enriched = InteractionClassEnricher.enrich(
            substance("MDMA", listOf("entactogen", "stimulant"))
        )
        assertTrue("serotonin_releaser" in enriched.interactionClasses)
    }

    @Test
    fun lithiumNameGetsLithiumClass() {
        assertTrue("lithium" in InteractionClassEnricher.enrich(substance("Lithium")).interactionClasses)
    }

    @Test
    fun moclobemideGetsMaoiClass() {
        assertTrue("maoi" in InteractionClassEnricher.enrich(substance("Moclobemide")).interactionClasses)
    }

    @Test
    fun tramadolGetsSerotonergicClass() {
        assertTrue(
            "serotonergic" in InteractionClassEnricher.enrich(substance("Tramadol")).interactionClasses
        )
    }

    @Test
    fun plainAntidepressantDoesNotGetSsriClass() {
        val enriched = InteractionClassEnricher.enrich(
            substance("Bupropion", listOf("antidepressant", "stimulant"))
        )
        assertFalse("ssri" in enriched.interactionClasses)
        assertFalse("serotonergic" in enriched.interactionClasses)
    }

    @Test
    fun nameContainingBenzIsNotABenzodiazepine() {
        val enriched = InteractionClassEnricher.enrich(
            substance("Benzedrex", listOf("stimulant"))
        )
        assertFalse("benzodiazepine" in enriched.interactionClasses)
    }

    @Test
    fun enrichKeepsExistingClasses() {
        val enriched = InteractionClassEnricher.enrich(
            substance("MDMA", listOf("entactogen"), listOf("stimulant", "custom"))
        )
        assertEquals(listOf("stimulant", "custom", "serotonin_releaser"), enriched.interactionClasses)
    }

    @Test
    fun enrichIsIdempotent() {
        val once = InteractionClassEnricher.enrich(substance("Sertraline"))
        val twice = InteractionClassEnricher.enrich(once)
        assertSame(once, twice)
        assertTrue(InteractionClassEnricher.missingClasses(once).isEmpty())
    }

    @Test
    fun unknownSubstanceIsReturnedUnchanged() {
        val original = substance("Unobtainium", listOf("stimulant"))
        assertSame(original, InteractionClassEnricher.enrich(original))
    }
}
