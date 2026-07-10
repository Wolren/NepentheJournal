package app.journal.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

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
    val fontScale: Float = 1.0f
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

val LocalThemeConfig = staticCompositionLocalOf { ThemeDefaults.Dark }
val LocalBackgroundPainter = staticCompositionLocalOf<Painter?> { null }
