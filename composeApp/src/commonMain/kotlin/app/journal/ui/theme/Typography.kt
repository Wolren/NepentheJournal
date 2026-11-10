package app.journal.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Nepenthe Journal typography scale.
 * Uses Inter-like system font for clean readability, monospace for data.
 *
 * The default sans-serif on each platform is used (Roboto on Android,
 * SF Pro on macOS, Segoe UI on Windows). When bundled fonts are added
 * later, swap fontFamily here once.
 */
val NepentheTypography = Typography(
    displayLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.2).sp
    ),
    displaySmall = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    headlineLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.015).sp
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.01).sp
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.01).sp
    ),
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.01.sp
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.01.sp
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.sp
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.01.sp
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.015.sp
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.02.sp
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.02.sp
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.sp
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.06.sp
    )
)

/**
 * Monospace style for data values: dose amounts, hex codes, timestamps.
 * Use when displaying user-visible numbers that should be easily scannable.
 */
val monospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    letterSpacing = 0.sp
)

/** Applies the chosen font family and font scale to the theme typography. */
fun nepentheTypography(family: AppFontFamily = AppFontFamily.SANS, scale: Float = 1f): Typography {
    val font = when (family) {
        AppFontFamily.SANS -> FontFamily.SansSerif
        AppFontFamily.SERIF -> FontFamily.Serif
        AppFontFamily.MONO -> FontFamily.Monospace
    }
    fun TextStyle.fit(): TextStyle = copy(
        fontFamily = font,
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
        letterSpacing = letterSpacing * scale,
    )
    return NepentheTypography.copy(
        displayLarge = NepentheTypography.displayLarge.fit(),
        displayMedium = NepentheTypography.displayMedium.fit(),
        displaySmall = NepentheTypography.displaySmall.fit(),
        headlineLarge = NepentheTypography.headlineLarge.fit(),
        headlineMedium = NepentheTypography.headlineMedium.fit(),
        headlineSmall = NepentheTypography.headlineSmall.fit(),
        titleLarge = NepentheTypography.titleLarge.fit(),
        titleMedium = NepentheTypography.titleMedium.fit(),
        titleSmall = NepentheTypography.titleSmall.fit(),
        bodyLarge = NepentheTypography.bodyLarge.fit(),
        bodyMedium = NepentheTypography.bodyMedium.fit(),
        bodySmall = NepentheTypography.bodySmall.fit(),
        labelLarge = NepentheTypography.labelLarge.fit(),
        labelMedium = NepentheTypography.labelMedium.fit(),
        labelSmall = NepentheTypography.labelSmall.fit(),
    )
}
