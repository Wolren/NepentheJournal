package app.journal.ui.charts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Axis labels used to be placed at their fraction unconditionally, so a phone
 * width stacked "1 Aug 2026" on top of "10 Aug 2026". Labels now drop out when
 * the gap to the neighbour would fall below the minimum.
 */
class AxisLabelsTest {

    private val fractions = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
    private fun widths(labelWidth: Int) = List(fractions.size) { labelWidth }

    @Test
    fun wideAxisKeepsEveryLabelAtItsFraction() {
        val xs = placeTickLabels(fractions, widths(60), width = 800, gap = 6)
        assertEquals(listOf(0, 170, 370, 570, 740), xs)
    }

    @Test
    fun narrowAxisKeepsBothEndsAndDropsTheCollisions() {
        val xs = placeTickLabels(fractions, widths(60), width = 240, gap = 6)
        assertEquals(listOf(0, null, 90, null, 180), xs)
    }

    @Test
    fun axisTooNarrowForTheFirstLabelKeepsTheEndMarker() {
        val xs = placeTickLabels(fractions, widths(60), width = 100, gap = 6)
        assertEquals(listOf(null, null, null, null, 40), xs)
    }

    @Test
    fun noTwoPlacedLabelsComeCloserThanTheGapAtAnyWidth() {
        for (width in 60..900 step 3) {
            val xs = placeTickLabels(fractions, widths(60), width = width, gap = 6)
            assertNotNull(xs.last(), "the end marker must survive at width $width")
            val placed = xs.withIndex().filter { it.value != null }
            placed.forEach { (index, x) ->
                assertTrue(x!! + 60 <= width, "label $index overflows at width $width")
            }
            placed.zipWithNext().forEach { (left, right) ->
                assertTrue(
                    right.value!! >= left.value!! + 60 + 6,
                    "labels ${left.index} and ${right.index} collide at width $width",
                )
            }
        }
    }

    @Test
    fun singleLabelIsAlwaysPlaced() {
        assertEquals(listOf(0), placeTickLabels(listOf(0f), listOf(40), width = 200, gap = 6))
    }
}