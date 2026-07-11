package app.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
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

    // Cache computed ColorScheme to avoid blend/contrast math on every
    // recomposition. Recomputes only when config content changes.
    private var cachedConfigHash: Int = 0
    private var cachedDarkScheme: ColorScheme? = null
    private var cachedLightScheme: ColorScheme? = null

    fun update(newConfig: ThemeConfig) {
        _config.value = newConfig
        // Invalidate cache so next colorScheme() call recomputes
        cachedConfigHash = 0
    }

    fun presetDark() { update(ThemeDefaults.Dark) }
    fun presetLight() { update(ThemeDefaults.Light) }

    /**
     * Builds a full Material3 ColorScheme from the editable ThemeConfig,
     * deriving container/on-* colors with proper contrast instead of naive alpha copies.
     *
     * Result is cached: only recomputes when the underlying ThemeConfig content changes.
     */
    fun colorScheme(isDark: Boolean): ColorScheme {
        val cfg = _config.value
        val hash = cfg.hashCode() xor if (isDark) 1 else 0
        if (hash == cachedConfigHash) {
            val cached = if (isDark) cachedDarkScheme else cachedLightScheme
            if (cached != null) return cached
        }
        return recomputeColorScheme(cfg, isDark).also {
            if (hash != cachedConfigHash) {
                cachedConfigHash = hash
            }
            if (isDark) cachedDarkScheme = it else cachedLightScheme = it
        }
    }

    private fun recomputeColorScheme(cfg: ThemeConfig, isDark: Boolean): ColorScheme {
        val bg = cfg.background ?: if (isDark) Color(0xFF0E1511) else Color(0xFFF3F8EF)
        val surf = cfg.surface ?: if (isDark) Color(0xFF16211A) else Color(0xFFFFFFFF)
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        val d = if (isDark) 0.30f else 0.16f

        fun container(accent: Color): Color = blend(bg, accent, d)
        fun surfaceVariant(accent: Color, factor: Float = if (isDark) 0.12f else 0.06f): Color =
            blend(surf, accent, factor)

        return base.copy(
            primary = cfg.primary,
            onPrimary = contrastColor(cfg.primary),
            primaryContainer = container(cfg.primary),
            onPrimaryContainer = contrastColor(container(cfg.primary)),
            secondary = cfg.secondary,
            onSecondary = contrastColor(cfg.secondary),
            secondaryContainer = container(cfg.secondary),
            onSecondaryContainer = contrastColor(container(cfg.secondary)),
            tertiary = cfg.tertiary,
            onTertiary = contrastColor(cfg.tertiary),
            tertiaryContainer = container(cfg.tertiary),
            onTertiaryContainer = contrastColor(container(cfg.tertiary)),
            error = cfg.error,
            onError = contrastColor(cfg.error),
            errorContainer = blend(bg, cfg.error, 0.30f),
            onErrorContainer = contrastColor(blend(bg, cfg.error, 0.30f)),
            background = bg,
            onBackground = contrastColor(bg),
            surface = surf,
            onSurface = contrastColor(surf),
            surfaceVariant = surfaceVariant(cfg.primary),
            onSurfaceVariant = contrastColor(surfaceVariant(cfg.primary)),
            outline = surfaceVariant(cfg.primary, if (isDark) 0.28f else 0.18f),
            outlineVariant = surfaceVariant(cfg.primary, if (isDark) 0.16f else 0.10f)
        )
    }

    @Composable
    fun isDarkTheme(): Boolean = when (_config.value.baseTheme) {
        BaseTheme.DARK -> true
        BaseTheme.LIGHT -> false
        BaseTheme.SYSTEM -> isSystemInDarkTheme()
        BaseTheme.CUSTOM -> isSystemInDarkTheme()
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
