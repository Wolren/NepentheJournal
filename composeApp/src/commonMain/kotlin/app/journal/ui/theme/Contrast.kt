package app.journal.ui.theme

import androidx.compose.ui.graphics.Color

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
