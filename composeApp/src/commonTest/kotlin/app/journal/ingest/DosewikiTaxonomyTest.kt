package app.journal.ingest

import app.journal.model.CuratedSection
import kotlin.test.*

class DosewikiTaxonomyTest {

    private val miniIndex = """
        {"version":"test","categories":[
          {"key":"stimulant","label":"Stimulant","drugs":[],"sections":[
            {"key":"cathinone","label":"Cathinone","drugs":["2-mmc","mdmc"]},
            {"key":"amphetamine","label":"Amphetamine","drugs":["amphetamine"]}]},
          {"key":"hallucinogen","label":"A-typical Hallucinogen","drugs":["salvia"],"sections":[]}
        ]}
    """.trimIndent()

    @Test
    fun parseMapsSlugsToSectionTags() {
        val index = DosewikiTaxonomy.parse(miniIndex)
        assertEquals(
            listOf(
                CuratedSection("stimulant", "cathinone", "Cathinone"),
            ),
            index["2-mmc"],
        )
    }

    @Test
    fun parseGivesCategoryLevelDrugsAnAllSection() {
        val index = DosewikiTaxonomy.parse(miniIndex)
        assertEquals(
            listOf(
                CuratedSection("hallucinogen", "all", "A-typical Hallucinogen"),
            ),
            index["salvia"],
        )
    }

    @Test
    fun slugifyMatchesDoseWikiSlugs() {
        assertEquals("salvia-divinorum", DosewikiTaxonomy.slugify("Salvia divinorum"))
        assertEquals("2c-b", DosewikiTaxonomy.slugify("2C-B"))
        assertEquals(
            "salvinorin-b-methoxymethyl-ether",
            DosewikiTaxonomy.slugify("Salvinorin-B-Methoxymethyl-Ether"),
        )
    }

    @Test
    fun tagsForPrefersDwId() {
        val index = DosewikiTaxonomy.parse(miniIndex)
        val tags = DosewikiTaxonomy.tagsFor("dw:2-mmc", "Mephedrone?", emptyList(), index)
        assertEquals(1, tags.size)
        assertEquals("Cathinone", tags.first().label)
    }

    @Test
    fun tagsForFallsBackToNameAndAlias() {
        val index = DosewikiTaxonomy.parse(miniIndex)
        assertEquals(
            "Cathinone",
            DosewikiTaxonomy.tagsFor("cid:1", "2-MMC", emptyList(), index).single().label,
        )
        assertEquals(
            "Cathinone",
            DosewikiTaxonomy.tagsFor("cid:2", "Something", listOf("2-MMC"), index).single().label,
        )
    }

    @Test
    fun tagsForUnknownIsEmpty() {
        val index = DosewikiTaxonomy.parse(miniIndex)
        assertTrue(DosewikiTaxonomy.tagsFor("cid:9", "Nope", emptyList(), index).isEmpty())
    }
}
