package app.journal.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReceptorLabelTest {

    private fun parts(label: String): List<String> =
        receptorSegments(label).map { if (it.subscript) "[${it.text}]" else it.text }

    @Test
    fun subtypeRendersAsSubscript() {
        assertEquals(listOf("5-HT", "[2A]"), parts("5-HT2A"))
        assertEquals(listOf("5-HT", "[1A]"), parts("5-HT1A"))
        assertEquals(listOf("D", "[2]"), parts("D2"))
        assertEquals(listOf("H", "[1]"), parts("H1"))
        assertEquals(listOf("M", "[2]"), parts("M2"))
        assertEquals(listOf("Alpha-", "[1A]"), parts("Alpha-1A"))
        assertEquals(listOf("Beta-", "[2]"), parts("Beta-2"))
        assertEquals(listOf("TAAR", "[1]"), parts("TAAR1"))
        assertEquals(listOf("GABA-A \u03b1", "[6]"), parts("GABA-A \u03b16"))
        assertEquals(listOf("P2X purinoceptor ", "[7]"), parts("P2X purinoceptor 7"))
    }

    @Test
    fun unknownShapesKeepTheirText() {
        assertEquals(listOf("SERT"), parts("SERT"))
        assertEquals(listOf("GAT-1"), parts("GAT-1"))
        assertEquals(emptyList(), parts("   "))
    }

    @Test
    fun everyGreekSubunitGetsLowered() {
        assertEquals(listOf("GABA-A \u03b1", "[1]", "\u03b2", "[2]", "\u03b3", "[2]"), parts("GABA-A \u03b11\u03b22\u03b32"))
        assertEquals(listOf("\u03b1", "[4]", "\u03b2", "[2]", " nAChR"), parts("\u03b14\u03b22 nAChR"))
        assertEquals("GABA-A \u03b11\u03b22\u03b32", receptorAnnotatedString("GABA-A \u03b11\u03b22\u03b32").text)
    }

    @Test
    fun annotatedStringKeepsEveryCharacter() {
        assertEquals("5-HT2A", receptorAnnotatedString("5-HT2A").text)
    }

    @Test
    fun labelsSortNaturally() {
        val labels = listOf("5-HT10", "5-HT2C", "5-HT1A", "5-HT2A", "5-HT1B", "D10", "D2", "D1")
        val sorted = labels.sortedWith { a, b -> compareReceptorLabels(a, b) }
        assertEquals(listOf("5-HT1A", "5-HT1B", "5-HT2A", "5-HT2C", "5-HT10", "D1", "D2", "D10"), sorted)
        assertTrue(compareReceptorLabels("5-HT1A", "5-HT2A") < 0)
        assertTrue(compareReceptorLabels("5-HT2A", "5-HT2A") == 0)
    }
}
