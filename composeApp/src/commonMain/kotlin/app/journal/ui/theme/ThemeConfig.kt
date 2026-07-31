package app.journal.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ThemeConfig(
    val baseTheme: BaseTheme = BaseTheme.DARK,
    val primaryColor: Long = 0xFF90CAF9,
    val secondaryColor: Long = 0xFFCE93D8,
    val tertiaryColor: Long = 0xFFA5D6A7,
    val backgroundColor: Long? = null,
    val surfaceColor: Long? = null,
    val errorColor: Long = 0xFFEF9A9A,
    val backgroundImagePath: String? = null,
    val backgroundOpacity: Float = 0.3f,
    val cardStyle: CardStyle = CardStyle.ELEVATED,
    val cornerRadius: CornerRadius = CornerRadius.MEDIUM,
    val fontScale: Float = 1.0f,
    val animationScale: Float = 1.0f
) {
    val primary: Color get() = Color(primaryColor)
    val secondary: Color get() = Color(secondaryColor)
    val tertiary: Color get() = Color(tertiaryColor)
    val error: Color get() = Color(errorColor)
    val background: Color? get() = backgroundColor?.let { Color(it) }
    val surface: Color? get() = surfaceColor?.let { Color(it) }

    val shapes: Shapes get() = when (cornerRadius) {
        CornerRadius.SMALL -> Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(12.dp),
            extraLarge = RoundedCornerShape(16.dp)
        )
        CornerRadius.MEDIUM -> Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
        CornerRadius.LARGE -> Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(32.dp)
        )
    }
}

enum class BaseTheme { DARK, LIGHT, SYSTEM, CUSTOM }
enum class CardStyle { ELEVATED, FILLED, OUTLINED }
enum class CornerRadius { SMALL, MEDIUM, LARGE }

object ThemeDefaults {
    val Dark = ThemeConfig(
        baseTheme = BaseTheme.DARK,
        primaryColor = 0xFFDCA2F4,
        secondaryColor = 0xFFAD89D6,
        tertiaryColor = 0xFF8100F5,
        errorColor = 0xFFEF9A9A,
        backgroundColor = 0xFF0E1511,
        surfaceColor = 0xFF16211A
    )
    val Light = ThemeConfig(
        baseTheme = BaseTheme.LIGHT,
        primaryColor = 0xFFDCA2F4,
        secondaryColor = 0xFFAD89D6,
        tertiaryColor = 0xFF8100F5,
        errorColor = 0xFFD32F2F,
        backgroundColor = 0xFFF3F8EF,
        surfaceColor = 0xFFFFFFFF
    )
}

/**
 * A curated theme pair: one palette for dark rendering, one for light.
 * Applying a preset keeps the current base theme and picks the matching variant.
 */
data class ThemePreset(
    val name: String,
    val dark: ThemeConfig,
    val light: ThemeConfig
) {
    /** The variant for [isDark] rendering. */
    fun variant(isDark: Boolean): ThemeConfig = if (isDark) dark else light

    /** True when the given edit colors match this preset's variant for [isDark]. */
    fun matches(primary: Long, secondary: Long, tertiary: Long, bg: Long, surface: Long, isDark: Boolean): Boolean {
        val v = variant(isDark)
        return primary == v.primaryColor && secondary == v.secondaryColor &&
            tertiary == v.tertiaryColor && bg == v.backgroundColor && surface == v.surfaceColor
    }
}

object ThemePresets {
    val Forest = ThemePreset(
        name = "Forest",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFDCA2F4, secondaryColor = 0xFFAD89D6, tertiaryColor = 0xFF8100F5,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0E1511, surfaceColor = 0xFF16211A
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF7B1FA2, secondaryColor = 0xFF5E35B1, tertiaryColor = 0xFF8100F5,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF3F8EF, surfaceColor = 0xFFFFFFFF
        )
    )
    val Ocean = ThemePreset(
        name = "Ocean",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF81D4FA, secondaryColor = 0xFF80CBC4, tertiaryColor = 0xFFB39DDB,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0B1219, surfaceColor = 0xFF13202B
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF0277BD, secondaryColor = 0xFF00897B, tertiaryColor = 0xFF5E35B1,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF2F8FC, surfaceColor = 0xFFFFFFFF
        )
    )
    val Sunset = ThemePreset(
        name = "Sunset",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFFFB74D, secondaryColor = 0xFFFF8A65, tertiaryColor = 0xFFF48FB1,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF1A1210, surfaceColor = 0xFF261813
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFFE65100, secondaryColor = 0xFFD84315, tertiaryColor = 0xFFC2185B,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFFFF6EF, surfaceColor = 0xFFFFFFFF
        )
    )
    val Ember = ThemePreset(
        name = "Ember",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFEF9A9A, secondaryColor = 0xFFFFAB91, tertiaryColor = 0xFFFFE082,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF170B0C, surfaceColor = 0xFF221112
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFFC62828, secondaryColor = 0xFFD84315, tertiaryColor = 0xFFF9A825,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFFDF3F3, surfaceColor = 0xFFFFFFFF
        )
    )
    val Midnight = ThemePreset(
        name = "Midnight",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFB39DDB, secondaryColor = 0xFF80CBC4, tertiaryColor = 0xFFF48FB1,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0E0E1A, surfaceColor = 0xFF191928
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF4527A0, secondaryColor = 0xFF00695C, tertiaryColor = 0xFFAD1457,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF4F2FA, surfaceColor = 0xFFFFFFFF
        )
    )
    val Mono = ThemePreset(
        name = "Mono",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFBDBDBD, secondaryColor = 0xFF9E9E9E, tertiaryColor = 0xFF757575,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF111111, surfaceColor = 0xFF1C1C1C
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF424242, secondaryColor = 0xFF616161, tertiaryColor = 0xFF757575,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFFAFAFA, surfaceColor = 0xFFFFFFFF
        )
    )

    val all: List<ThemePreset> = listOf(Forest, Ocean, Sunset, Ember, Midnight, Mono)
}

val LocalThemeConfig = staticCompositionLocalOf { ThemeDefaults.Dark }
val LocalBackgroundPainter = staticCompositionLocalOf<Painter?> { null }
