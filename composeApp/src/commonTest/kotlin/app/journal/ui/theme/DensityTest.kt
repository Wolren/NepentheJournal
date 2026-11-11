package app.journal.ui.theme

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DensityTest {

    @Test
    fun tokensWidenFromCompactToSpacious() {
        val compact = Density.COMPACT.spacing
        val comfortable = Density.COMFORTABLE.spacing
        val spacious = Density.SPACIOUS.spacing

        assertTrue(compact.cardPadding < comfortable.cardPadding)
        assertTrue(comfortable.cardPadding < spacious.cardPadding)
        assertTrue(compact.densePadding < comfortable.densePadding)
        assertTrue(compact.cardGap < comfortable.cardGap)
        assertTrue(comfortable.innerGap < spacious.innerGap)
        assertTrue(compact.screenPadding < spacious.screenPadding)
    }

    @Test
    fun denseStepStaysTighterThanTheRegularStep() {
        Density.entries.forEach { density ->
            val tokens = density.spacing
            assertTrue(tokens.densePadding < tokens.cardPadding, "$density dense step must stay tighter")
        }
    }

    @Test
    fun comfortableIsTheDefault() {
        assertEquals(Density.COMFORTABLE, ThemeConfig().density)
        assertEquals(16.dp, Density.COMFORTABLE.spacing.cardPadding)
    }

    @Test
    fun presetDeclaringDensityOverridesTheCurrentStep() {
        val base = ThemeConfig(density = Density.COMFORTABLE)

        val terminal = ThemePresets.Terminal.applyTo(base, isDark = true)
        val paper = ThemePresets.Paper.applyTo(base, isDark = true)

        assertEquals(Density.COMPACT, terminal.density)
        assertEquals(Density.SPACIOUS, paper.density)
    }

    @Test
    fun colourOnlyPresetKeepsTheCurrentDensity() {
        val base = ThemeConfig(density = Density.SPACIOUS)

        val next = ThemePresets.Slate.applyTo(base, isDark = true)

        assertEquals(Density.SPACIOUS, next.density)
    }

    @Test
    fun densityRoundTripsThroughTheStore() {
        val config = ThemeConfig(density = Density.SPACIOUS)

        assertEquals(config, ThemeStore.decode(ThemeStore.encode(config)))
    }

    @Test
    fun themeJsonWithoutDensityDecodesToComfortable() {
        val legacy = """{"baseTheme":"DARK","primaryColor":-14286992,"cardStyle":"OUTLINED"}"""

        val decoded = ThemeStore.decode(legacy)

        assertEquals(Density.COMFORTABLE, decoded?.density)
        assertEquals(CardStyle.OUTLINED, decoded?.cardStyle)
    }
}
