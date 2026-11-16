package app.journal.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PharmacologyProvenanceTest {

    private fun contribution(source: String, value: Double?, type: String = "Ki") =
        PharmacologyContribution(source = source, type = type, nanoMolar = value)

    @Test
    fun pubmedIdsBecomeLinks() {
        val citation = Citation(pmid = "12213075")
        assertEquals("https://pubmed.ncbi.nlm.nih.gov/12213075/", citation.url)
        assertEquals("PubMed 12213075", citation.label)
        assertEquals(true, citation.isLink)
    }

    @Test
    fun doiAndPlainTextStayReadable() {
        assertEquals("https://doi.org/10.1000/xyz", Citation(doi = "10.1000/xyz").url)
        assertEquals("DOI 10.1000/xyz", Citation(doi = "10.1000/xyz").label)
        val textOnly = Citation(text = "Runyon SP et al., 2008")
        assertNull(textOnly.url)
        assertEquals("Runyon SP et al., 2008", textOnly.label)
    }

    @Test
    fun priorityOrdersTheSheetFirst() {
        val rows = listOf(
            contribution("PDSP", 12.0),
            contribution("IUPHAR", 158.4),
            contribution("Wikipedia", 117.75),
        )
        val ordered = sortBySourcePriority(rows, listOf("Wikipedia", "IUPHAR", "PDSP"))
        assertEquals(listOf("Wikipedia", "IUPHAR", "PDSP"), ordered.map { it.source })
        val unknownLast = sortBySourcePriority(rows, listOf("IUPHAR"))
        assertEquals("IUPHAR", unknownLast.first().source)
    }

    @Test
    fun spreadNeedsTwoValues() {
        assertNull(spreadOf(listOf(contribution("PDSP", 12.0))))
        assertEquals(12.0 to 158.4, spreadOf(listOf(contribution("PDSP", 12.0), contribution("IUPHAR", 158.4))))
    }
}
