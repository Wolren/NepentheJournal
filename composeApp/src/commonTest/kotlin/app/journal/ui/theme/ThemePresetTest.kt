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
}
