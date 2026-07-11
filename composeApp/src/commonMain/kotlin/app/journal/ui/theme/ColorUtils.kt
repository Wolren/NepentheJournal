package app.journal.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Pure color math: HSV <-> ARGB conversion, hex formatting, hex parsing.
 * No Compose UI dependencies — usable anywhere color values are needed.
 */
object ColorUtils {

    fun hsvToArgb(h: Float, s: Float, v: Float, a: Int): Long {
        val hh = (h % 360f).coerceAtLeast(0f)
        val c = v * s
        val x = c * (1f - kotlin.math.abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val ri = ((r + m) * 255).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255).toInt().coerceIn(0, 255)
        val ai = a.coerceIn(0, 255)
        return (ai.toLong() shl 24) or (ri.toLong() shl 16) or (gi.toLong() shl 8) or bi.toLong()
    }

    /** Hue-only color for the hue slider track. */
    fun hueColor(h: Float): Color = Color(hsvToArgb(h, 1f, 1f, 255))

    fun toHsvHue(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val d = max - min
        if (d == 0f) return 0f
        val h = when (max) {
            color.red -> ((color.green - color.blue) / d) % 6f
            color.green -> (color.blue - color.red) / d + 2f
            else -> (color.red - color.green) / d + 4f
        }
        return ((h * 60f) + 360f) % 360f
    }

    fun toHsvSat(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        if (max == 0f) return 0f
        return (max - minOf(color.red, color.green, color.blue)) / max
    }

    fun toHsvVal(color: Color): Float = maxOf(color.red, color.green, color.blue)

    fun toHexColor(argb: Long): String {
        val a = (argb ushr 24).toInt() and 0xFF
        fun two(v: Float) = ((v * 255).toInt().coerceIn(0, 255)).toString(16).padStart(2, '0')
        val c = Color(argb)
        return "#${two(c.alpha)}${two(c.red)}${two(c.green)}${two(c.blue)}".uppercase()
    }

    fun parseArgb(hex: String): Long? {
        val clean = hex.removePrefix("#")
        val (a, rg, gg, bg) = when (clean.length) {
            8 -> listOf(clean.substring(0, 2), clean.substring(2, 4), clean.substring(4, 6), clean.substring(6, 8))
            6 -> listOf("FF", clean.substring(0, 2), clean.substring(2, 4), clean.substring(4, 6))
            else -> return null
        }
        return try {
            val aI = a.toInt(16); val rI = rg.toInt(16); val gI = gg.toInt(16); val bI = bg.toInt(16)
            (aI.toLong() shl 24) or (rI.toLong() shl 16) or (gI.toLong() shl 8) or bI.toLong()
        } catch (_: Exception) { null }
    }
}
