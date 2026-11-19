package app.journal.model

import app.journal.catalog.CatalogSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The popularity table is keyed by catalog names, so it must not drift when
 * the bundled catalog is regenerated: a renamed substance silently loses its
 * tier, which would quietly undo the ranking the table exists for.
 */
class SubstancePopularityCatalogTest {

    @Test
    fun everyRankedNameStillExistsInTheBundledCatalog() {
        val available = CatalogSource.loadSubstances()
            .map { it.name.trim().lowercase() }
            .toSet()
        val missing = SubstancePopularity.tiers.keys
            .filterNot { it in available }
            .sorted()
        assertTrue(
            missing.isEmpty(),
            "popularity tiers name substances the bundled catalog no longer has: $missing",
        )
    }
}
