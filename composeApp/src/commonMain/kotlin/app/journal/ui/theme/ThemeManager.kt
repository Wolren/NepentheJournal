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
import kotlin.math.pow

class ThemeManager private constructor() {

    /** Neon is the shipped look; a saved theme replaces it on load. */
    private val _config = MutableStateFlow(ThemePresets.Neon.applyTo(ThemeDefaults.Dark, isDark = true))
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private var cachedConfigHash: Int = 0
    private var cachedDarkScheme: ColorScheme? = null
    private var cachedLightScheme: ColorScheme? = null

    fun update(newConfig: ThemeConfig) {
        _config.value = newConfig
        cachedConfigHash = 0
        ThemeStore.save(newConfig)
    }

    /** Loads the saved theme once at startup; tests keep the in-memory default. */
    fun restore() {
        ThemeStore.load()?.let { _config.value = it; cachedConfigHash = 0 }
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
        // Container roles lift away from the surface; in dark mode they go toward white, in light toward black.
        val lift = if (isDark) Color.White else Color.Black
        val invert = contrastColor(surf)
        val tint = cfg.surfaceTint ?: if (isDark) 0.12f else 0.06f
        val outlineStrength = cfg.outlineStrength ?: if (isDark) 0.28f else 0.18f

        fun container(accent: Color): Color = blend(bg, accent, d)
        fun surfaceVariant(accent: Color, factor: Float = tint): Color =
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
            onBackground = cfg.textColor?.let { Color(it) } ?: contrastColor(bg),
            surface = surf,
            onSurface = cfg.textColor?.let { Color(it) } ?: contrastColor(surf),
            surfaceVariant = surfaceVariant(cfg.primary),
            onSurfaceVariant = contrastColor(surfaceVariant(cfg.primary)),
            outline = surfaceVariant(cfg.primary, outlineStrength),
            outlineVariant = surfaceVariant(cfg.primary, outlineStrength * 0.57f),
            // Dialogs, sheets, menus and snackbars read these roles; unset, they fall back to the baseline palette.
            surfaceTint = cfg.primary,
            surfaceContainerLowest = blend(surf, bg, if (isDark) 0.60f else 0.03f),
            surfaceContainerLow = blend(surf, bg, if (isDark) 0.35f else 0.02f),
            surfaceContainer = blend(surf, bg, if (isDark) 0.18f else 0.01f),
            surfaceContainerHigh = blend(surf, lift, if (isDark) 0.05f else 0.03f),
            surfaceContainerHighest = blend(surf, lift, if (isDark) 0.09f else 0.06f),
            surfaceDim = blend(surf, bg, if (isDark) 0.25f else 0.08f),
            surfaceBright = blend(surf, lift, if (isDark) 0.14f else 0.02f),
            inverseSurface = blend(surf, invert, 0.88f),
            inverseOnSurface = surf,
            inversePrimary = blend(cfg.primary, invert, if (isDark) 0.25f else 0.15f)
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

private fun Float.pow(e: Float): Float = this.toDouble().pow(e.toDouble()).toFloat()
