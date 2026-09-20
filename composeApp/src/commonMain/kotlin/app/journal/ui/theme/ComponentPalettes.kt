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
 * Activity grid ramp derived from the theme accent. Index 0 is the empty-cell
 * color, 1..4 are ascending activity levels toward primary.
 */
@Composable
fun activityLevelColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    // Empty-cell base is lifted off surfaceVariant (the card behind the grid
    // is surfaceVariant too, so a raw surfaceVariant grid goes invisible).
    val base = blend(scheme.surfaceVariant, scheme.onSurfaceVariant, 0.22f)
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
