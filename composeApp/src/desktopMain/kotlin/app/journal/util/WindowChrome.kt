package app.journal.util

import app.journal.log.Log
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Dark caption and border for the system frame. AWT leaves the title bar in
 * the light system theme, which reads as a white strip above a dark app.
 */
object WindowChrome {
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val CAPTION_BYTES = 4

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwmapi by lazy { Native.load("dwmapi", Dwmapi::class.java) }

    /** [argb] is the app background; Windows stores colors as blue, green, red. */
    fun applyDark(handle: Long, argb: Long) {
        if (handle == 0L) return
        val hwnd = Pointer.createConstant(handle)
        try {
            setInt(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, 1)
            val colorRef = toColorRef(argb)
            setInt(hwnd, DWMWA_CAPTION_COLOR, colorRef)
            setInt(hwnd, DWMWA_BORDER_COLOR, colorRef)
        } catch (e: Exception) {
            Log.withTag("Window").w(e) { "title bar color failed" }
        }
    }

    private fun setInt(hwnd: Pointer, attribute: Int, value: Int) {
        val memory = Memory(CAPTION_BYTES.toLong())
        memory.setInt(0, value)
        dwm.DwmSetWindowAttribute(hwnd, attribute, memory, CAPTION_BYTES)
    }

    private fun toColorRef(argb: Long): Int {
        val red = ((argb shr 16) and 0xFF).toInt()
        val green = ((argb shr 8) and 0xFF).toInt()
        val blue = (argb and 0xFF).toInt()
        return (blue shl 16) or (green shl 8) or red
    }
}
