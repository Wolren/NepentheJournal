package app.journal.util

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import app.journal.log.Log

/**
 * Uniform window transparency. Frame.setOpacity throws on Windows for a
 * decorated frame, so the alpha goes through a layered window instead.
 */
object WindowOpacity {
    private const val WS_EX_LAYERED = 0x00080000
    private const val LWA_ALPHA = 0x2

    /** Returns false when the handle is missing or the call fails. */
    fun apply(handle: Long, opacity: Float): Boolean {
        if (handle == 0L) {
            Log.withTag("Window").w { "window opacity skipped: no handle" }
            return false
        }
        val user32 = User32.INSTANCE
        val hwnd = WinDef.HWND(Pointer.createConstant(handle))
        return try {
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            if (opacity >= 0.999f) {
                if ((style and WS_EX_LAYERED) != 0) {
                    user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, style and WS_EX_LAYERED.inv())
                }
                true
            } else {
                if ((style and WS_EX_LAYERED) == 0) {
                    user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, style or WS_EX_LAYERED)
                }
                val alpha = (opacity.coerceIn(0.2f, 1f) * 255).toInt().toByte()
                user32.SetLayeredWindowAttributes(hwnd, 0, alpha, LWA_ALPHA)
            }
        } catch (e: Exception) {
            Log.withTag("Window").w(e) { "window opacity failed" }
            false
        }
    }
}
