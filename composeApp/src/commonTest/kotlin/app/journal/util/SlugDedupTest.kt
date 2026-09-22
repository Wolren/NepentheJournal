package app.journal.util

import app.journal.export.obsidian.slugify as obsidianSlugify
import app.journal.ingest.DosewikiTaxonomy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Wave4 helper dedup pin (task 5): slugify used to exist twice with DIFFERENT
 * Unicode rules and DIFFERENT empty fallbacks. Both bodies were unified into
 * util/Slug.kt, and rendered slug text is a persisted contract (Obsidian
 * export file names, dw:{slug} entity IDs), so this test proves byte-for-byte
 * output invariance by comparing the single implementation against verbatim
 * copies of BOTH pre-dedup bodies, plus the two package-local delegates that
 * existing frozen tests call.
 */
class SlugDedupTest {

    /** Verbatim copy of the pre-dedup ObsidianNoteRenderer.slugify body. */
    private fun oldObsidianSlugify(s: String): String {
        val out = StringBuilder()
        var prevDash = false
        for (ch in s) {
            if (ch.isLetterOrDigit()) {
                out.append(ch.lowercaseChar())
                prevDash = false
            } else if (!prevDash && out.isNotEmpty()) {
                out.append('-')
                prevDash = true
            }
        }
        while (out.endsWith('-')) out.deleteAt(out.length - 1)
        return if (out.isEmpty()) "untitled" else out.toString()
    }

    /** Verbatim copy of the pre-dedup DosewikiTaxonomy.slugify body. */
    private fun oldDoseWikiSlugify(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private val corpus = listOf(
        "LSD Exploration!",
        "A Wild/Trip: 2026!",
        "",
        "   ",
        "  hello world  ",
        "a!!!b...c",
        "Salvia divinorum",
        "2C-B",
        "Salvinorin-B-Methoxymethyl-Ether",
        "café",
        "ÉÉÉ",
        "İstanbul",
        "straße",
        "日本語",
        "!!!",
        "---",
        "a_b_c",
        "Ñandú 100mg",
        "tab\tseparated\nlines",
        "x.",
        "1.2.3",
        "Fée and Øresund",
        "en–dash – and em",
        "Ünicode 100 MG",
        "42"
    )

    @Test
    fun filenameFormMatchesPreDedupBodyByteForByte() {
        for (input in corpus) {
            assertEquals(
                oldObsidianSlugify(input), slugify(input),
                "filename slug for ${input.quote()} must not change"
            )
        }
    }

    @Test
    fun doseWikiFormMatchesPreDedupBodyByteForByte() {
        for (input in corpus) {
            assertEquals(
                oldDoseWikiSlugify(input), slugify(input, asciiOnly = true, fallback = ""),
                "DoseWiki slug for ${input.quote()} must not change"
            )
        }
    }

    @Test
    fun packageLocalDelegatesRouteToOneImplementation() {
        // The names existing (frozen) tests call are zero-logic delegates:
        // both must agree with the single util implementation for every input.
        for (input in corpus) {
            assertEquals(slugify(input), obsidianSlugify(input),
                "export.obsidian.slugify must be the util filename form")
            assertEquals(
                slugify(input, asciiOnly = true, fallback = ""),
                DosewikiTaxonomy.slugify(input),
                "DosewikiTaxonomy.slugify must be the util ascii form"
            )
        }
    }

    @Test
    fun historicPinnedExamplesStayExact() {
        // Pre-existing expectations from ObsidianNoteRendererTest and
        // DosewikiTaxonomyTest, restated here so this file alone documents
        // the rendered-text contract.
        assertEquals("lsd-exploration", slugify("LSD Exploration!"))
        assertEquals("a-wild-trip-2026", slugify("A Wild/Trip: 2026!"))
        assertEquals("untitled", slugify(""))
        assertEquals("hello-world", slugify("  hello world  "))
        assertEquals("a-b-c", slugify("a!!!b...c"))
        assertEquals("salvia-divinorum", DosewikiTaxonomy.slugify("Salvia divinorum"))
        assertEquals("2c-b", DosewikiTaxonomy.slugify("2C-B"))
        assertEquals(
            "salvinorin-b-methoxymethyl-ether",
            DosewikiTaxonomy.slugify("Salvinorin-B-Methoxymethyl-Ether")
        )
        // The two forms still differ exactly where they always did. Non-ASCII
        // is written as \u escapes so the assertions pin NFC input explicitly
        // (an editor silently rewriting to NFD would otherwise change slugs).
        assertEquals("caf\u00e9", slugify("caf\u00e9"), "filename form keeps Unicode letters")
        assertEquals("caf", DosewikiTaxonomy.slugify("caf\u00e9"), "ascii form strips them")
        assertEquals("untitled", slugify("!!!"), "filename fallback is untitled")
        assertEquals("", DosewikiTaxonomy.slugify("!!!"), "ascii fallback stays empty")
    }

    private fun String.quote(): String = "\"$this\""
}
