package app.journal.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reads the catalog the build script produced. The Gradle test task passes its
 * path as nepenthe.catalog; without that file the test reports nothing to check.
 */
class SubstanceCatalogTest {

    private val available: Boolean = System.getProperty("nepenthe.catalog") != null

    @Test
    fun readsTheBuiltCatalog() {
        if (!available) return
        assertTrue(SubstanceCatalog.count() > 500, "catalog should carry the merged library")
        assertNotNull(SubstanceCatalog.contentVersion())
    }

    @Test
    fun readsSubstanceChildren() {
        if (!available) return
        val lsd = assertNotNull(SubstanceCatalog.byName("LSD"))
        assertEquals("cid:5761", lsd.id)
        assertEquals(180.0, lsd.halfLifeMinutes)
        assertTrue("oral" in lsd.routes, "LSD should carry an oral route")
        val common = lsd.doseRanges.firstOrNull { it.route == "oral" && it.tier == "common" }
        assertNotNull(common)
        assertEquals(75.0, common.min)
        assertEquals(150.0, common.max)
        assertEquals("µg", common.unit)
    }

    @Test
    fun searchesByAlias() {
        if (!available) return
        val hits = SubstanceCatalog.search("acid")
        assertTrue(hits.isNotEmpty(), "alias search should find LSD by its street name")
    }

    @Test
    fun reportsWhichSourceSuppliedEachField() {
        if (!available) return
        assertEquals(listOf("dosewiki"), CatalogSource.provenanceFor("cid:5761")["dosage"])
        assertEquals(listOf("drug-community"), CatalogSource.provenanceFor("dc:2c-ef")["dosage"])
    }

    @Test
    fun shipsEverySourceWithItsLicence() {
        if (!available) return
        val sources = CatalogSource.sources().associateBy { it.name }
        assertTrue("dosewiki" in sources, "DoseWiki rows need a source entry")
        assertTrue("drug-community" in sources, "drug.community rows need a source entry")
        assertTrue(sources.values.all { it.licence.isNotBlank() && it.url.isNotBlank() })
    }
}
