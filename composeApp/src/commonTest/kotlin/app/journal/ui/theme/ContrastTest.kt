package app.journal.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ContrastTest {

    @Test
    fun blackOnWhiteIsTheMaximum() {
        val ratio = contrastRatio(Color.Black, Color.White)

        assertTrue(abs(ratio - 21f) < 0.05f, "expected 21, was $ratio")
    }

    @Test
    fun aColourAgainstItselfIsOne() {
        val ratio = contrastRatio(Color(0xFF335577), Color(0xFF335577))

        assertTrue(abs(ratio - 1f) < 0.001f, "expected 1, was $ratio")
    }

    @Test
    fun readableTextPicksWhiteOnADarkPage() {
        assertEquals(0xFFFFFFFF, readableTextOn(Color(0xFF05060A)))
    }

    @Test
    fun readableTextPicksBlackOnALightPage() {
        assertEquals(0xFF000000, readableTextOn(Color(0xFFF5F7FF)))
    }

    @Test
    fun ensureContrastReachesTheTarget() {
        val fixed = ensureContrast(Color(0xFF333333), Color(0xFF000000), 3f)

        assertTrue(contrastRatio(fixed, Color(0xFF000000)) >= 3f, "fixed colour still too dark")
    }

    @Test
    fun ensureContrastLeavesAGoodColourAlone() {
        assertEquals(Color.White, ensureContrast(Color.White, Color.Black, 3f))
    }
}
