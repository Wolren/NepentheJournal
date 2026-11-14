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
        assertEquals(5, blocks.size)
        assertEquals(
            listOf(EffectInline.Text("First paragraph line one continues on line two.")),
            (blocks[0] as EffectBlock.Paragraph).spans,
        )
        assertEquals(EffectBlock.Heading("Level 1"), blocks[1])
        assertEquals(
            listOf(EffectInline.Text("Second paragraph.")),
            (blocks[2] as EffectBlock.Paragraph).spans,
        )
        assertEquals(listOf(EffectInline.Text("first item")), (blocks[3] as EffectBlock.Bullet).spans)
        assertEquals(listOf(EffectInline.Text("second item")), (blocks[4] as EffectBlock.Bullet).spans)
    }

    @Test
    fun blankLinesCollapseAndEmptyInputYieldsNoBlocks() {
        assertTrue(parseEffectBlocks("").isEmpty())
        assertTrue(parseEffectBlocks("\n\n  \n").isEmpty())
        assertEquals(1, parseEffectBlocks("one\n\n\n\n").size)
    }

    @Test
    fun boldItalicAndLinksBecomeSpans() {
        val spans = parseInline("An **abnormal heartbeat** is *rarely* a {{effect:drifting|drifting}} state")
        assertEquals(EffectInline.Text("An "), spans[0])
        assertEquals(EffectInline.Bold("abnormal heartbeat"), spans[1])
        assertEquals(EffectInline.Text(" is "), spans[2])
        assertEquals(EffectInline.Italic("rarely"), spans[3])
        assertEquals(EffectInline.Text(" a "), spans[4])
        assertEquals(EffectInline.Link("effect:drifting", "drifting"), spans[5])
        assertEquals(EffectInline.Text(" state"), spans[6])
    }

    @Test
    fun anUnclosedMarkerStaysLiteralText() {
        val spans = parseInline("50% ** not bold")
        assertEquals(1, spans.size)
        assertTrue((spans[0] as EffectInline.Text).text.contains("**"))
    }

    @Test
    fun levelBoxesKeepLabelHeaderAndContent() {
        val body = """
            Before.

            :::level Level 1 | Fleeting hallucinations
            At the lowest level the **sound** is faint.

            A second paragraph.
            :::

            After.
        """.trimIndent()

        val blocks = parseEffectBlocks(body)
        assertEquals(3, blocks.size)
        val box = blocks[1] as EffectBlock.LevelBox
        assertEquals("Level 1", box.label)
        assertEquals("Fleeting hallucinations", box.header)
        assertEquals(2, box.paragraphs.size)
        assertEquals(EffectInline.Bold("sound"), box.paragraphs[0][1])
        assertEquals(
            listOf(EffectInline.Text("A second paragraph.")),
            box.paragraphs[1],
        )
    }
}
