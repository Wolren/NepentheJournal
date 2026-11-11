package app.journal.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeDefaultTest {

    @Test
    fun freshInstallStartsOnNeon() {
        val defaults = ThemeManager.defaultConfig

        assertEquals(ThemePresets.Neon.dark.primaryColor, defaults.primaryColor)
        assertEquals(ThemePresets.Neon.dark.backgroundColor, defaults.backgroundColor)
        assertEquals(ThemePresets.Neon.dark.surfaceColor, defaults.surfaceColor)
        assertEquals(BaseTheme.DARK, defaults.baseTheme)
    }

    @Test
    fun aSavedThemeReplacesTheDefault() {
        val saved = ThemeManager.defaultConfig.copy(primaryColor = 0xFF112233)

        assertEquals(0xFF112233, saved.primaryColor)
        assertEquals(ThemePresets.Neon.dark.primaryColor, ThemeManager.defaultConfig.primaryColor)
    }
}
