package app.journal.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** WCAG contrast ratio of two colours, from 1 to 21. */
fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance()
    val second = b.luminance()
    val light = maxOf(first, second)
    val dark = minOf(first, second)
    return (light + 0.05f) / (dark + 0.05f)
}

/** Black or white, whichever reads better on [background]. */
fun readableTextOn(background: Color): Long =
    if (contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)) 0xFFFFFFFF else 0xFF000000

/** Moves [foreground] toward white or black until it reaches [target] against [background]. */
fun ensureContrast(foreground: Color, background: Color, target: Float): Color {
    if (contrastRatio(foreground, background) >= target) return foreground
    val towardWhite = contrastRatio(Color.White, background) >= contrastRatio(Color.Black, background)
    val anchor = if (towardWhite) Color.White else Color.Black
    var result = foreground
    repeat(24) {
        result = lerp(result, anchor, 0.08f)
        if (contrastRatio(result, background) >= target) return result
    }
    return anchor
}
