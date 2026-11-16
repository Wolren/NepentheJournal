package app.journal.catalog

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The catalog must carry the measured pharmacology, not just the empty model.
 * A zero here means the export pipeline dropped the values before the UI could see them.
 */
class CatalogPharmacologyTest {

    private val available: Boolean = System.getProperty("nepenthe.catalog") != null

    @Test
    fun catalogCarriesMeasuredAffinities() {
        if (!available) return
        val catalog = CatalogSource.loadSubstances().associateBy { it.id }

        val lsd = catalog["cid:5761"]
        assertTrue(lsd != null, "LSD missing from catalog")
        val bdb = lsd.bindingdbData?.records.orEmpty()
        val chembl = lsd.chemblData?.bioactivities.orEmpty()
        val pdsp = lsd.pdspData?.records.orEmpty()
        assertTrue(bdb.isNotEmpty(), "LSD has no BindingDB records")
        assertTrue(chembl.isNotEmpty(), "LSD has no ChEMBL records")
        assertTrue(pdsp.isNotEmpty(), "LSD has no PDSP records")

        val withBindingdb = catalog.values.count { !it.bindingdbData?.records.isNullOrEmpty() }
        val withChembl = catalog.values.count { !it.chemblData?.bioactivities.isNullOrEmpty() }
        assertTrue(withBindingdb >= 100, "only $withBindingdb substances carry BindingDB rows")
        assertTrue(withChembl >= 100, "only $withChembl substances carry ChEMBL rows")

        val totalRows = catalog.values.sumOf {
            it.bindingdbData?.records.orEmpty().size +
                it.chemblData?.bioactivities.orEmpty().size +
                it.pdspData?.records.orEmpty().size +
                it.iupharData?.interactions.orEmpty().size
        }
        assertTrue(totalRows >= 15000, "only $totalRows affinity rows reached the catalog")
    }
}
