package app.journal.ui.theme

import kotlin.test.Test
import kotlin.test.assertTrue

/** Every derived on-* role must reach WCAG AA at 4.5:1 against the surface it sits on. */
class ThemeDerivedContrastTest {

    @Test
    fun everyPresetKeepsItsDerivedTextAboveAa() {
        val failures = mutableListOf<String>()
        for (preset in ThemePresets.all) {
            for (isDark in listOf(true, false)) {
                val scheme = ThemeManager.instance.recomputeColorScheme(preset.variant(isDark), isDark)
                val roles = listOf(
                    Triple("onPrimary", scheme.onPrimary, scheme.primary),
                    Triple("onPrimaryContainer", scheme.onPrimaryContainer, scheme.primaryContainer),
                    Triple("onSecondary", scheme.onSecondary, scheme.secondary),
                    Triple("onSecondaryContainer", scheme.onSecondaryContainer, scheme.secondaryContainer),
                    Triple("onTertiary", scheme.onTertiary, scheme.tertiary),
                    Triple("onTertiaryContainer", scheme.onTertiaryContainer, scheme.tertiaryContainer),
                    Triple("onError", scheme.onError, scheme.error),
                    Triple("onErrorContainer", scheme.onErrorContainer, scheme.errorContainer),
                    Triple("onSurfaceVariant", scheme.onSurfaceVariant, scheme.surfaceVariant)
                )
                for ((role, text, surface) in roles) {
                    val ratio = contrastRatio(text, surface)
                    if (ratio < 4.5f) {
                        failures += "${preset.name} ${if (isDark) "dark" else "light"} $role ${(ratio * 100).toInt() / 100f}"
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(), "roles below 4.5:1: ${failures.joinToString()}")
    }
}
