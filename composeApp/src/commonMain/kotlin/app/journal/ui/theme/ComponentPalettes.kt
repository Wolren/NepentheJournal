package app.journal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Reactive darkness of the active scheme. Reads MaterialTheme, so every caller
 * recomposes on theme change. Prefer this over ThemeManager.isDarkTheme(),
 * which snapshots config outside composition and goes stale.
 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.45f

/**
 * Activity grid ramp. Classic GitHub greens when [ThemeConfig.githubGreenActivity]
 * is on (the default), otherwise a ramp from the empty tone to theme primary.
 */
@Composable
fun activityLevelColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    // Empty-cell base is lifted off surfaceVariant (the card behind the grid
    // is surfaceVariant too, so a raw surfaceVariant grid goes invisible).
    val base = blend(scheme.surfaceVariant, scheme.onSurfaceVariant, 0.22f)
    if (LocalThemeConfig.current.githubGreenActivity) {
        // Classic moss greens, dark and light variants.
        val greens = if (isDarkTheme()) listOf(
            Color(0xFF1B4A1B), Color(0xFF2D6A2D), Color(0xFF3D8A3D), Color(0xFF4CAF50)
        ) else listOf(
            Color(0xFFB9DFB9), Color(0xFF8FCF8F), Color(0xFF66BB6A), Color(0xFF43A047)
        )
        return listOf(base) + greens
    }
    return listOf(
        base,
        blend(base, scheme.primary, 0.30f),
        blend(base, scheme.primary, 0.55f),
        blend(base, scheme.primary, 0.78f),
        scheme.primary
    )
}

/**
 * Categorical series for bar and route charts, derived from the theme accents
 * so custom palettes recolor every chart. Cycles primary, secondary, tertiary
 * with surface-blended variants past index 2.
 */
@Composable
fun chartSeriesColors(count: Int): List<Color> {
    val scheme = MaterialTheme.colorScheme
    val accents = listOf(scheme.primary, scheme.secondary, scheme.tertiary)
    val surf = scheme.surfaceVariant
    return List(count) { i ->
        val accent = accents[i % accents.size]
        when ((i / accents.size) % 3) {
            1 -> blend(surf, accent, 0.55f)
            2 -> blend(surf, accent, 0.30f)
            else -> accent
        }
    }
}
