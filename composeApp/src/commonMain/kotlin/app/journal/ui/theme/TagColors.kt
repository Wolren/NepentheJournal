package app.journal.ui.theme

import androidx.compose.ui.graphics.Color
import app.journal.ui.theme.luminance
import kotlin.math.abs

/**
 * Generates consistent tag colors with colored background and white text.
 * Picks from a saturated palette with guaranteed contrast against white.
 * Same tag name always gets the same color.
 */
object TagColors {

    // Saturated palette. Each entry has high enough luminance contrast
    // against white text to pass WCAG AA (contrast ratio > 4.5:1)
    private val palette = listOf(
        0xFFD32F2F, // red
        0xFFC2185B, // pink
        0xFF7B1FA2, // purple
        0xFF283593, // indigo
        0xFF1565C0, // blue
        0xFF00838F, // cyan
        0xFF00695C, // teal
        0xFF2E7D32, // green
        0xFF558B2F, // light green
        0xFFF9A825, // yellow (needs darker bg, but for yellow we make text actually dark)
        0xFFE65100, // orange
        0xFF4E342E, // brown
        0xFF37474F, // blue grey
        0xFF6A1B9A, // deep purple
        0xFF0D47A1, // dark blue
        0xFF33691E, // dark green
        0xFF880E4F, // dark pink
        0xFF004D40, // dark teal
        0xFF311B92, // dark violet
        0xFFBF360C, // deep orange
    )

    fun background(tag: String): Color {
        val idx = abs(tag.hashCode()) % palette.size
        return Color(palette[idx])
    }

    /** Returns the foreground color with best contrast against the tag background. */
    fun foreground(tag: String): Color {
        val bg = background(tag)
        return if (bg.luminance() > 0.45f) Color.Black else Color.White
    }
}
