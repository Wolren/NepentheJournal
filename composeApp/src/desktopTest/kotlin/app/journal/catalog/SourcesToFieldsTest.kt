package app.journal.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provenance lists one line per source. A source that feeds four fields must
 * appear once, not four times.
 */
class SourcesToFieldsTest {

    @Test
    fun repeatedSourceAppearsOnce() {
        val grouped = CatalogSource.sourcesToFields(
            mapOf(
                "dosage" to listOf("dosewiki"),
                "duration" to listOf("dosewiki"),
                "effects" to listOf("dosewiki"),
                "interactions" to listOf("dosewiki"),
                "name" to listOf("psychonautwiki"),
                "classes" to listOf("psychonautwiki"),
            ),
        )
        assertEquals(2, grouped.size, "expected one line per source")
        assertEquals("dosewiki", grouped[0].first)
        assertEquals(listOf("dosage", "duration", "effects", "interactions"), grouped[0].second)
        assertEquals("psychonautwiki", grouped[1].first)
        assertEquals(listOf("classes", "name"), grouped[1].second)
    }

    @Test
    fun singleSourceCoveringEverythingIsOneLine() {
        val grouped = CatalogSource.sourcesToFields(
            mapOf(
                "name" to listOf("dosewiki"),
                "dosage" to listOf("dosewiki"),
                "duration" to listOf("dosewiki"),
            ),
        )
        assertEquals(1, grouped.size)
        assertEquals(3, grouped[0].second.size)
    }

    @Test
    fun sameFieldFromTwoSourcesListsBoth() {
        val grouped = CatalogSource.sourcesToFields(mapOf("pharmacology" to listOf("chembl", "bindingdb")))
        assertEquals(2, grouped.size)
        assertTrue(grouped.all { it.second == listOf("pharmacology") })
    }

    @Test
    fun emptyProvenanceGivesNothing() {
        assertTrue(CatalogSource.sourcesToFields(emptyMap()).isEmpty())
    }
}
