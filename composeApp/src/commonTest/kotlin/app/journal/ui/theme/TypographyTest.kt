package app.journal.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class TypographyTest {

    private val phone = nepentheTypography(AppFontFamily.SANS, 1f, desktop = false)
    private val pc = nepentheTypography(AppFontFamily.SANS, 1f, desktop = true)

    @Test
    fun desktopLiftsTheSmallEndOfTheScale() {
        assertEquals(12f, phone.bodySmall.fontSize.value, 0.01f)
        assertEquals(11f, phone.labelSmall.fontSize.value, 0.01f)
        assertEquals(14f, phone.bodyMedium.fontSize.value, 0.01f)

        assertEquals(13.5f, pc.bodySmall.fontSize.value, 0.01f)
        assertEquals(12.5f, pc.labelSmall.fontSize.value, 0.01f)
        assertEquals(15f, pc.bodyMedium.fontSize.value, 0.01f)
    }

    @Test
    fun desktopLeavesTheSixteenStepAndAboveAlone() {
        assertEquals(16f, pc.titleMedium.fontSize.value, 0.01f)
        assertEquals(18f, pc.titleLarge.fontSize.value, 0.01f)
        assertEquals(20f, pc.headlineSmall.fontSize.value, 0.01f)
    }

    @Test
    fun lineHeightMovesWithItsFontSize() {
        assertEquals(pc.bodySmall.fontSize.value + 4f, pc.bodySmall.lineHeight.value, 0.01f)
        assertEquals(pc.labelSmall.fontSize.value + 5f, pc.labelSmall.lineHeight.value, 0.01f)
    }

    @Test
    fun userScaleAppliesOnTopOfTheLift() {
        val scaled = nepentheTypography(AppFontFamily.SANS, 1.2f, desktop = true)

        assertEquals(12f * 1.2f + 1.5f, scaled.bodySmall.fontSize.value, 0.01f)
        assertEquals(16f * 1.2f, scaled.titleMedium.fontSize.value, 0.01f)
    }
}
