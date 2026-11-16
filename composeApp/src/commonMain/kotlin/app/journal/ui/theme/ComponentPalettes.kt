package app.journal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Reactive darkness of the active scheme. Reads MaterialTheme, so every caller
 * recomposes on theme change. Prefer this over ThemeManager.isDarkTheme(),
 * which snapshots config outside composition and goes stale.
 */
@Composable
fun isDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.45f

/** Card fill the heatmap sits on, with the theme's card opacity already applied. */
@Composable
private fun cardBackdrop(): Color {
    val scheme = MaterialTheme.colorScheme
    val config = LocalThemeConfig.current
    val container = if (config.cardStyle == CardStyle.OUTLINED) scheme.surface else scheme.surfaceVariant
    return blend(scheme.background, container, config.cardOpacity.coerceIn(0f, 1f))
}

/** Moves [color] toward white or black until it clears [target] against [background]. */
private fun stepTowards(color: Color, background: Color, target: Float, towardWhite: Boolean): Color {
    if (contrastRatio(color, background) >= target) return color
    val anchor = if (towardWhite) Color.White else Color.Black
    var out = color
    repeat(30) {
        out = lerp(out, anchor, 0.08f)
        if (contrastRatio(out, background) >= target) return out
    }
    return anchor
}

/** Empty-cell tone keeps 1.35:1 against the card, so "no activity" reads as a cell in every theme. */
private const val EMPTY_VS_CARD = 1.35f

/** Each level keeps 1.22:1 against the level below it, so the ramp never flattens. */
private const val LEVEL_STEP = 1.22f

/**
 * Activity grid ramp: one tone for days without activity, then four levels.
 * Classic GitHub greens when [ThemeConfig.githubGreenActivity] is on, otherwise a
 * ramp from the empty tone to theme primary. Every step is contrast-checked.
 */
@Composable
fun activityLevelColors(): List<Color> {
    val scheme = MaterialTheme.colorScheme
    val card = cardBackdrop()
    val towardWhite = isDarkTheme()
    val empty = ensureContrast(blend(scheme.surfaceVariant, scheme.onSurfaceVariant, 0.22f), card, EMPTY_VS_CARD)

    val seeds = if (LocalThemeConfig.current.githubGreenActivity) {
        if (towardWhite) listOf(
            Color(0xFF1B4A1B), Color(0xFF2D6A2D), Color(0xFF3D8A3D), Color(0xFF4CAF50)
        ) else listOf(
            Color(0xFFB9DFB9), Color(0xFF8FCF8F), Color(0xFF66BB6A), Color(0xFF43A047)
        )
    } else {
        listOf(
            blend(empty, scheme.primary, 0.30f),
            blend(empty, scheme.primary, 0.55f),
            blend(empty, scheme.primary, 0.78f),
            scheme.primary,
        )
    }

    var previous = empty
    val levels = seeds.map { seed ->
        val next = stepTowards(seed, previous, LEVEL_STEP, towardWhite)
        previous = next
        next
    }
    return listOf(empty) + levels
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
