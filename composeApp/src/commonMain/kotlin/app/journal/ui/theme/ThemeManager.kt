package app.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemeManager private constructor() {

    private val _config = MutableStateFlow(ThemeDefaults.Dark)
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    fun update(newConfig: ThemeConfig) {
        _config.value = newConfig
    }

    fun presetDark() { update(ThemeDefaults.Dark) }
    fun presetLight() { update(ThemeDefaults.Light) }

    /**
     * Builds a full Material3 ColorScheme from the editable ThemeConfig,
     * deriving container/on-* colors with proper contrast instead of naive alpha copies.
     */
    fun colorScheme(isDark: Boolean) = with(_config.value) {
        val bg = background ?: if (isDark) Color(0xFF0E1511) else Color(0xFFF3F8EF)
        val surf = surface ?: if (isDark) Color(0xFF16211A) else Color(0xFFFFFFFF)
        val base = if (isDark) darkColorScheme() else lightColorScheme()

        base.copy(
            primary = primary,
            onPrimary = contrastColor(primary),
            primaryContainer = blend(bg, primary, if (isDark) 0.30f else 0.16f),
            onPrimaryContainer = contrastColor(blend(bg, primary, if (isDark) 0.30f else 0.16f)),
            secondary = secondary,
            onSecondary = contrastColor(secondary),
            secondaryContainer = blend(bg, secondary, if (isDark) 0.30f else 0.16f),
            onSecondaryContainer = contrastColor(blend(bg, secondary, if (isDark) 0.30f else 0.16f)),
            tertiary = tertiary,
            onTertiary = contrastColor(tertiary),
            tertiaryContainer = blend(bg, tertiary, if (isDark) 0.30f else 0.16f),
            onTertiaryContainer = contrastColor(blend(bg, tertiary, if (isDark) 0.30f else 0.16f)),
            error = error,
            onError = contrastColor(error),
            errorContainer = blend(bg, error, 0.30f),
            onErrorContainer = contrastColor(blend(bg, error, 0.30f)),
            background = bg,
            onBackground = contrastColor(bg),
            surface = surf,
            onSurface = contrastColor(surf),
            surfaceVariant = blend(surf, primary, if (isDark) 0.12f else 0.06f),
            onSurfaceVariant = contrastColor(blend(surf, primary, if (isDark) 0.12f else 0.06f)),
            outline = blend(surf, primary, if (isDark) 0.28f else 0.18f),
            outlineVariant = blend(surf, primary, if (isDark) 0.16f else 0.10f)
        )
    }

    @Composable
    fun isDarkTheme(): Boolean = when (_config.value.baseTheme) {
        BaseTheme.DARK -> true
        BaseTheme.LIGHT -> false
        BaseTheme.SYSTEM -> isSystemInDarkTheme()
    }

    companion object {
        val instance: ThemeManager by lazy { ThemeManager() }
    }
}

/** Relative luminance (WCAG) of a color, 0..1. */
fun Color.luminance(): Float {
    fun channel(c: Float): Float = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

/** Returns black or white depending on which has better contrast against [c]. */
fun contrastColor(c: Color): Color = if (c.luminance() > 0.45f) Color.Black else Color.White

/** Linearly blends [base] with [overlay] at the given [ratio] (0 = base, 1 = overlay). */
fun blend(base: Color, overlay: Color, ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1 - r) + overlay.red * r,
        green = base.green * (1 - r) + overlay.green * r,
        blue = base.blue * (1 - r) + overlay.blue * r,
        alpha = 1f
    )
}

private fun Float.pow(e: Float): Float = Math.pow(this.toDouble(), e.toDouble()).toFloat()
