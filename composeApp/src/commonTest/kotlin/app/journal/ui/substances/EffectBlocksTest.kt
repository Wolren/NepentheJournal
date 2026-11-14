package app.journal.ui.substances

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EffectBlocksTest {

    @Test
    fun headingsParagraphsAndBulletsSplit() {
        val body = """
            First paragraph line one
            continues on line two.

            ## Level 1

            Second paragraph.

            - first item
            - second item
        """.trimIndent()

        val blocks = parseEffectBlocks(body)
        assertEquals(EffectBlock.Paragraph("First paragraph line one continues on line two."), blocks[0])
        assertEquals(EffectBlock.Heading("Level 1"), blocks[1])
        assertEquals(EffectBlock.Paragraph("Second paragraph."), blocks[2])
        assertEquals(EffectBlock.Bullet("first item"), blocks[3])
        assertEquals(EffectBlock.Bullet("second item"), blocks[4])
        assertEquals(5, blocks.size)
    }

    @Test
    fun blankLinesCollapseAndEmptyInputYieldsNoBlocks() {
        assertTrue(parseEffectBlocks("").isEmpty())
        assertTrue(parseEffectBlocks("\n\n  \n").isEmpty())
        assertEquals(
            listOf(EffectBlock.Paragraph("one")),
            parseEffectBlocks("one\n\n\n\n"),
        )
    }

    @Test
    fun headingAtTheEndStillRenders() {
        val blocks = parseEffectBlocks("Text\n\n## Tail")
        assertEquals(listOf(EffectBlock.Paragraph("Text"), EffectBlock.Heading("Tail")), blocks)
    }

    @Test
    fun articlesWithoutMarkersStayOneParagraphPerBlankLine() {
        val blocks = parseEffectBlocks("Line one.\n\nLine two.")
        assertEquals(2, blocks.size)
        assertTrue(blocks.all { it is EffectBlock.Paragraph })
    }
}