package app.journal.data

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F-23 guard: DoseWikiLookup is a stateful singleton with a parsed-data
 * cache. reset() exists so tests cannot leak that cache into each other,
 * and these assertions pin the load / cache / reload behavior.
 */
class DoseWikiLookupTest {

    @Test
    fun resetClearsCacheAndForcesReload() {
        DoseWikiLookup.reset()
        val base = DoseWikiLookup.loadCount

        DoseWikiLookup.getSubstance("anything")
        assertEquals(base + 1, DoseWikiLookup.loadCount, "first lookup must load the bundled resource once")

        DoseWikiLookup.getSubstance("something else")
        assertEquals(base + 1, DoseWikiLookup.loadCount, "cached lookup must not reload")

        DoseWikiLookup.reset()
        DoseWikiLookup.getSubstance("anything")
        assertEquals(base + 2, DoseWikiLookup.loadCount, "reset must force a reload")
    }

    @Test
    fun adoptedListSkipsTheResourceReadAndParse() {
        DoseWikiLookup.reset()
        val base = DoseWikiLookup.loadCount
        val seeded = listOf(
            app.journal.model.DoseWikiSubstance(title = "Adopted Sample"),
            app.journal.model.DoseWikiSubstance(title = "Adopted Other", slug = "adopted-other")
        )

        DoseWikiLookup.adopt(seeded)

        assertEquals("Adopted Other", DoseWikiLookup.getSubstance("adopted other")?.title)
        assertEquals(
            base, DoseWikiLookup.loadCount,
            "an adopted list must serve lookups without reading the bundled asset again"
        )
    }
}
