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
    val density: Density = Density.COMFORTABLE,
    val fontScale: Float = 1.0f,
    val fontFamily: AppFontFamily = AppFontFamily.SANS,
    val textColor: Long? = null,
    val animationScale: Float = 1.0f,
    val surfaceTint: Float? = null,
    val outlineStrength: Float? = null,
    val substanceAccentMode: SubstanceAccentMode = SubstanceAccentMode.ADAPTIVE,
    val githubGreenActivity: Boolean = false,
    val windowOpacity: Float = 1f,
    val cardOpacity: Float = 1f
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
            medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(10.dp),
            extraLarge = RoundedCornerShape(14.dp)
        )
        CornerRadius.MEDIUM -> Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(18.dp)
        )
        CornerRadius.LARGE -> Shapes(
            extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(18.dp),
            extraLarge = RoundedCornerShape(24.dp)
        )
    }
}

enum class BaseTheme { DARK, LIGHT, SYSTEM, CUSTOM }
enum class CardStyle { ELEVATED, FILLED, OUTLINED }
enum class CornerRadius { SMALL, MEDIUM, LARGE }
enum class Density { COMPACT, COMFORTABLE, SPACIOUS }
enum class AppFontFamily { SANS, SERIF, MONO }
enum class SubstanceAccentMode { ADAPTIVE, THEME, MONO }

object ThemeDefaults {
    /** Logo palette: cream lily on deep teal. */
    val Dark = ThemeConfig(
        baseTheme = BaseTheme.DARK,
        primaryColor = 0xFFEBD9B0,
        secondaryColor = 0xFF8FD8BE,
        tertiaryColor = 0xFFE4B363,
        errorColor = 0xFFF1A7A7,
        backgroundColor = 0xFF0A2621,
        surfaceColor = 0xFF10332B,
        textColor = 0xFFF2E7CF
    )
    val Light = ThemeConfig(
        baseTheme = BaseTheme.LIGHT,
        primaryColor = 0xFF1E5C4B,
        secondaryColor = 0xFF2F7D66,
        tertiaryColor = 0xFFA5741F,
        errorColor = 0xFFC62828,
        backgroundColor = 0xFFF4EFE3,
        surfaceColor = 0xFFFFFFFF,
        textColor = 0xFF1D2B27
    )
}

/**
 * A curated theme pair: one palette for dark rendering, one for light.
 * Applying a preset keeps the current base theme and picks the matching variant.
 */
@Immutable
@Serializable
data class ThemePreset(
    val name: String,
    val dark: ThemeConfig,
    val light: ThemeConfig,
    val cardStyle: CardStyle? = null,
    val cornerRadius: CornerRadius? = null,
    val density: Density? = null,
    val fontFamily: AppFontFamily? = null,
    val fontScale: Float? = null,
    val animationScale: Float? = null,
    val surfaceTint: Float? = null,
    val outlineStrength: Float? = null
) {
    fun variant(isDark: Boolean): ThemeConfig = if (isDark) dark else light

    /**
     * Applies this preset on top of [current]. Colors come from the variant
     * matching the current appearance; the non-color fields only override
     * when the preset declares them.
     */
    fun applyTo(current: ThemeConfig, isDark: Boolean): ThemeConfig {
        val v = variant(isDark)
        return current.copy(
            primaryColor = v.primaryColor,
            secondaryColor = v.secondaryColor,
            tertiaryColor = v.tertiaryColor,
            errorColor = v.errorColor,
            backgroundColor = v.backgroundColor,
            surfaceColor = v.surfaceColor,
            textColor = v.textColor,
            cardStyle = cardStyle ?: current.cardStyle,
            cornerRadius = cornerRadius ?: current.cornerRadius,
            density = density ?: current.density,
            fontFamily = fontFamily ?: current.fontFamily,
            fontScale = fontScale ?: current.fontScale,
            animationScale = animationScale ?: current.animationScale,
            surfaceTint = surfaceTint ?: current.surfaceTint,
            outlineStrength = outlineStrength ?: current.outlineStrength,
        )
    }

    fun matches(primary: Long, secondary: Long, tertiary: Long, bg: Long, surface: Long, isDark: Boolean): Boolean {
        val v = variant(isDark)
        return primary == v.primaryColor && secondary == v.secondaryColor &&
            tertiary == v.tertiaryColor && bg == v.backgroundColor && surface == v.surfaceColor
    }
}

object ThemePresets {
    val Nepenthe = ThemePreset(
        name = "Nepenthe",
        dark = ThemeDefaults.Dark,
        light = ThemeDefaults.Light
    )
    val Glass = ThemePreset(
        name = "Glass",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFBFEDE6, secondaryColor = 0xFF8FD1E8, tertiaryColor = 0xFFE8C98F,
            errorColor = 0xFFF1A7A7, backgroundColor = 0xFF0B1B1F, surfaceColor = 0xFF12292E,
            textColor = 0xFFEAF7F4
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF0E6B63, secondaryColor = 0xFF1E6F8C, tertiaryColor = 0xFF9A6A1E,
            errorColor = 0xFFC62828, backgroundColor = 0xFFEFF7F7, surfaceColor = 0xFFFFFFFF,
            textColor = 0xFF12221F
        ),
        cornerRadius = CornerRadius.LARGE,
        cardStyle = CardStyle.OUTLINED,
        fontFamily = AppFontFamily.MONO,
        fontScale = 1.0f,
        surfaceTint = 0.16f,
        outlineStrength = 0.32f
    )
    val Paper = ThemePreset(
        name = "Paper",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFD9C7A7, secondaryColor = 0xFFB8A27E, tertiaryColor = 0xFFC9A24B,
            errorColor = 0xFFE0A0A0, backgroundColor = 0xFF1A1611, surfaceColor = 0xFF241E17,
            textColor = 0xFFF0E7D8
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF5B4636, secondaryColor = 0xFF7A6248, tertiaryColor = 0xFF9A6A1E,
            errorColor = 0xFFB3261E, backgroundColor = 0xFFF6F1E6, surfaceColor = 0xFFFFFDF7,
            textColor = 0xFF2B241C
        ),
        cornerRadius = CornerRadius.SMALL,
        cardStyle = CardStyle.FILLED,
        density = Density.SPACIOUS,
        fontFamily = AppFontFamily.SERIF,
        fontScale = 1.04f,
        animationScale = 0.75f,
        surfaceTint = 0.08f,
        outlineStrength = 0.22f
    )
    val Neon = ThemePreset(
        name = "Neon",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF66F2D6, secondaryColor = 0xFFC56CFF, tertiaryColor = 0xFFFF6FAE,
            errorColor = 0xFFFF7A7A, backgroundColor = 0xFF05060A, surfaceColor = 0xFF0C0F1A,
            textColor = 0xFFEAFBFF
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF00796B, secondaryColor = 0xFF8E24AA, tertiaryColor = 0xFFD81B60,
            errorColor = 0xFFC62828, backgroundColor = 0xFFF5F7FF, surfaceColor = 0xFFFFFFFF,
            textColor = 0xFF101418
        ),
        cornerRadius = CornerRadius.MEDIUM,
        cardStyle = CardStyle.OUTLINED,
        fontScale = 0.96f,
        surfaceTint = 0.22f,
        outlineStrength = 0.5f
    )
    val Terminal = ThemePreset(
        name = "Terminal",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF7FE08A, secondaryColor = 0xFF4CC96B, tertiaryColor = 0xFFD8E36A,
            errorColor = 0xFFFF8A80, backgroundColor = 0xFF030A04, surfaceColor = 0xFF07130A,
            textColor = 0xFFCFF7D3
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF1B5E20, secondaryColor = 0xFF2E7D32, tertiaryColor = 0xFF827717,
            errorColor = 0xFFC62828, backgroundColor = 0xFFF2FBF2, surfaceColor = 0xFFFFFFFF,
            textColor = 0xFF102412
        ),
        cornerRadius = CornerRadius.SMALL,
        cardStyle = CardStyle.OUTLINED,
        density = Density.COMPACT,
        fontFamily = AppFontFamily.MONO,
        fontScale = 0.95f,
        animationScale = 0f,
        surfaceTint = 0.06f,
        outlineStrength = 0.35f
    )
    val Forest = ThemePreset(
        name = "Forest",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF6FCF97, secondaryColor = 0xFF43A047, tertiaryColor = 0xFFFFD54F,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0B1410, surfaceColor = 0xFF15241B
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF14632E, secondaryColor = 0xFF2E7D32, tertiaryColor = 0xFF9E7B1E,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFEFF6EF, surfaceColor = 0xFFFFFFFF
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
            primaryColor = 0xFF8FB8FF, secondaryColor = 0xFF5B8DEF, tertiaryColor = 0xFF6FE3C1,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF060B16, surfaceColor = 0xFF0E1728
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF1E40AF, secondaryColor = 0xFF0E7490, tertiaryColor = 0xFF047857,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFEEF3FA, surfaceColor = 0xFFFFFFFF
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

    val Meadow = ThemePreset(
        name = "Meadow",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF9CCC65, secondaryColor = 0xFFAED581, tertiaryColor = 0xFFFFF176,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0D140F, surfaceColor = 0xFF182420
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF2E7D32, secondaryColor = 0xFF558B2F, tertiaryColor = 0xFF9E9D24,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF1F8E9, surfaceColor = 0xFFFFFFFF
        )
    )
    val Copper = ThemePreset(
        name = "Copper",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFD69A63, secondaryColor = 0xFFB0764A, tertiaryColor = 0xFFE8C07A,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF130D08, surfaceColor = 0xFF211510
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF8C5A2B, secondaryColor = 0xFFA9713F, tertiaryColor = 0xFFB7791F,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFFAF4EA, surfaceColor = 0xFFFFFFFF
        )
    )
    val Slate = ThemePreset(
        name = "Slate",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFF90A4AE, secondaryColor = 0xFF78909C, tertiaryColor = 0xFFB0BEC5,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF0E1318, surfaceColor = 0xFF1A222B
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF37474F, secondaryColor = 0xFF455A64, tertiaryColor = 0xFF546E7A,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFECEFF1, surfaceColor = 0xFFFFFFFF
        )
    )
    val Amethyst = ThemePreset(
        name = "Amethyst",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFC9A6EC, secondaryColor = 0xFFA08CF0, tertiaryColor = 0xFFE3B778,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF1E1626, surfaceColor = 0xFF2C2139
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF8E24AA, secondaryColor = 0xFF5E35B1, tertiaryColor = 0xFF9A6B1F,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF9F3FC, surfaceColor = 0xFFFFFFFF
        )
    )
    val Rose = ThemePreset(
        name = "Rose",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFF06292, secondaryColor = 0xFFBA68C8, tertiaryColor = 0xFFFFD54F,
            errorColor = 0xFFEF9A9A, backgroundColor = 0xFF160D12, surfaceColor = 0xFF241419
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFFC2185B, secondaryColor = 0xFF7B1FA2, tertiaryColor = 0xFFF9A825,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFFDF0F4, surfaceColor = 0xFFFFFFFF
        )
    )
    /**
     * dose.wiki "fun" style, sampled from the live site stylesheet
     * (appearance-chroma.css, 20 Sep 2026): near-black plum ground #110617,
     * deep purple surface (color-mix of #1F1633 over #110617), orchid accent
     * #F0ABFC with soft #F5D0FE, gold highlights, pale lavender #F6EFFF
     * light mode. Pairs with outlined cards and large corners to echo the
     * site's bordered, generous-radius panels.
     */
    val DoseWiki = ThemePreset(
        name = "DoseWiki",
        dark = ThemeConfig(
            baseTheme = BaseTheme.DARK,
            primaryColor = 0xFFF0ABFC, secondaryColor = 0xFFF5D0FE, tertiaryColor = 0xFFFBBF24,
            errorColor = 0xFFFCA5A5, backgroundColor = 0xFF110617, surfaceColor = 0xFF1B122B
        ),
        light = ThemeConfig(
            baseTheme = BaseTheme.LIGHT,
            primaryColor = 0xFF932998, secondaryColor = 0xFFC026D3, tertiaryColor = 0xFFB45309,
            errorColor = 0xFFD32F2F, backgroundColor = 0xFFF6EFFF, surfaceColor = 0xFFFFFFFF
        ),
        cardStyle = CardStyle.OUTLINED,
        cornerRadius = CornerRadius.LARGE,
        density = Density.SPACIOUS
    )

    val all: List<ThemePreset> = listOf(
        Nepenthe, Glass, Paper, Neon, Terminal, Slate, Forest, Ocean, Sunset, Ember,
        Midnight, Mono, Meadow, Copper, Amethyst, Rose, DoseWiki
    )
}

val LocalThemeConfig = staticCompositionLocalOf { ThemeDefaults.Dark }
val LocalBackgroundPainter = staticCompositionLocalOf<Painter?> { null }
