package app.journal.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThemePresetTest {

    @Test
    fun personalityPresetCarriesTypographyAndMaterial() {
        val next = ThemePresets.Paper.applyTo(ThemeConfig(), isDark = true)

        assertEquals(AppFontFamily.SERIF, next.fontFamily)
        assertEquals(CornerRadius.SMALL, next.cornerRadius)
        assertEquals(0.75f, next.animationScale)
        assertEquals(0.08f, next.surfaceTint)
        assertEquals(0.22f, next.outlineStrength)
        assertEquals(ThemePresets.Paper.dark.primaryColor, next.primaryColor)
        assertEquals(ThemePresets.Paper.dark.backgroundColor, next.backgroundColor)
    }

    @Test
    fun colourOnlyPresetKeepsTheCurrentTypographyAndShape() {
        val base = ThemeConfig(fontFamily = AppFontFamily.MONO, cornerRadius = CornerRadius.LARGE)
        val next = ThemePresets.Slate.applyTo(base, isDark = true)

        assertEquals(AppFontFamily.MONO, next.fontFamily)
        assertEquals(CornerRadius.LARGE, next.cornerRadius)
        assertEquals(ThemePresets.Slate.dark.primaryColor, next.primaryColor)
    }

    @Test
    fun applicationKeepsTheBaseThemeAndBackgroundImage() {
        val base = ThemeConfig(
            baseTheme = BaseTheme.SYSTEM,
            backgroundImagePath = "wallpaper.png",
            backgroundOpacity = 0.6f
        )
        val next = ThemePresets.Neon.applyTo(base, isDark = false)

        assertEquals(BaseTheme.SYSTEM, next.baseTheme)
        assertEquals("wallpaper.png", next.backgroundImagePath)
        assertEquals(0.6f, next.backgroundOpacity)
        assertEquals(ThemePresets.Neon.light.primaryColor, next.primaryColor)
    }

    @Test
    fun themeJsonRoundTrips() {
        val config = ThemePresets.Neon.applyTo(ThemeConfig(), isDark = true)

        assertEquals(config, ThemeStore.decode(ThemeStore.encode(config)))
    }

    @Test
    fun invalidThemeJsonReturnsNull() {
        assertNull(ThemeStore.decode("not a theme"))
    }

    @Test
    fun opacitySurvivesTheStore() {
        val config = ThemeConfig(windowOpacity = 0.8f, cardOpacity = 0.55f)

        val decoded = ThemeStore.decode(ThemeStore.encode(config))

        assertEquals(0.8f, decoded?.windowOpacity)
        assertEquals(0.55f, decoded?.cardOpacity)
    }

    @Test
    fun presetKeepsTheCurrentOpacity() {
        val base = ThemeConfig(windowOpacity = 0.7f, cardOpacity = 0.5f)

        val next = ThemePresets.Paper.applyTo(base, isDark = true)

        assertEquals(0.7f, next.windowOpacity)
        assertEquals(0.5f, next.cardOpacity)
    }

    @Test
    fun backdropSurvivesTheStore() {
        val config = ThemeConfig(backdropGradient = BackdropGradient.VIGNETTE, backdropScrim = 0.4f)

        val decoded = ThemeStore.decode(ThemeStore.encode(config))

        assertEquals(BackdropGradient.VIGNETTE, decoded?.backdropGradient)
        assertEquals(0.4f, decoded?.backdropScrim)
    }
}
