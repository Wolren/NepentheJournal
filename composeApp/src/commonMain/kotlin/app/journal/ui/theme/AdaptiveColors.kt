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

    // Curated forest-mauve palette — deterministic per substance, harmonious together.
    // Full 360 deg hue + 55-85% sat was confetti on a muted forest. 8 tones, 38-52% sat.
    private val curatedLight = listOf(
        Color(0xFF8BA888), Color(0xFF9E8AC7), Color(0xFFC19AA6), Color(0xFF7BAFAF),
        Color(0xFFC4A46A), Color(0xFFB5876A), Color(0xFFA8B5A0), Color(0xFF7A9A7D)
    )
    private val curatedDark = listOf(
        Color(0xFF6B8A6E), Color(0xFF7D6BA8), Color(0xFF9A7A86), Color(0xFF5E8E8E),
        Color(0xFF9E8548), Color(0xFF8F6B4E), Color(0xFF8A9A85), Color(0xFF5C7A5E)
    )

    private val colorCache = mutableMapOf<String, SubstanceColor>()

    fun colorFor(name: String): SubstanceColor {
        return colorCache.getOrPut(name.lowercase()) {
            val idx = abs(name.hashCode()) % curatedLight.size
            SubstanceColor(light = curatedLight[idx], dark = curatedDark[idx])
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

/**
 * Picks black or white for text on top of [color], whichever has better
 * contrast. Hardcoding white text on adaptive substance colors fails WCAG
 * in both themes because the light/dark variants sit at 25-60% lightness.
 */
fun foregroundFor(color: Color): Color = if (color.luminance() > 0.45f) Color.Black else Color.White
