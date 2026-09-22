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

        // First lookup after reset must load and cache the bundled resource.
        DoseWikiLookup.getSubstance("anything")
        assertEquals(base + 1, DoseWikiLookup.loadCount, "first lookup must load the bundled resource once")

        // Subsequent lookups are served from cache: no second load.
        DoseWikiLookup.getSubstance("something else")
        assertEquals(base + 1, DoseWikiLookup.loadCount, "cached lookup must not reload")

        // reset() clears the cache so the next lookup reloads from disk.
        DoseWikiLookup.reset()
        DoseWikiLookup.getSubstance("anything")
        assertEquals(base + 2, DoseWikiLookup.loadCount, "reset must force a reload")
    }
}
