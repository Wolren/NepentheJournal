package app.journal.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Generates substance-specific colors from their name.
 * Same substance always gets the same color regardless of theme.
 * Provides both light and dark variants with sufficient contrast.
 *
 * Based on the AdaptiveColor approach from PsychonautWiki Journal:
 * hash the substance name into HSL space, then adjust lightness
 * per theme for readability.
 */
data class SubstanceColor(
    val light: Color,
    val dark: Color
) {
    @Composable
    fun getComposeColor(isDark: Boolean): Color = if (isDark) dark else light
}

object AdaptiveColors {

    private val colorCache = mutableMapOf<String, SubstanceColor>()

    fun colorFor(name: String): SubstanceColor {
        return colorCache.getOrPut(name.lowercase()) {
            val hash = abs(name.hashCode())
            val hue = (hash % 360).toFloat()
            // Vary saturation between 55-85% based on hash
            val sat = 55f + (hash / 360) % 31
            // Light: medium lightness (40-60%) for dark text on light bg
            val lightL = 40f + (hash / 7) % 21
            // Dark: lower lightness (25-45%) for light text on dark bg
            val darkL = 25f + (hash / 11) % 21
            SubstanceColor(
                light = hslToColor(hue, sat / 100f, lightL / 100f),
                dark = hslToColor(hue, sat / 100f, darkL / 100f)
            )
        }
    }

    private fun hslToColor(hue: Float, sat: Float, light: Float): Color {
        val c = (1f - abs(2f * light - 1f)) * sat
        val x = c * (1f - abs((hue / 60f) % 2f - 1f))
        val m = light - c / 2f
        val (r, g, b) = when {
            hue < 60 -> Triple(c, x, 0f)
            hue < 120 -> Triple(x, c, 0f)
            hue < 180 -> Triple(0f, c, x)
            hue < 240 -> Triple(0f, x, c)
            hue < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r + m), (g + m), (b + m))
    }
}
