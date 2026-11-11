package app.journal.util

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.Frame
import java.awt.Rectangle

/**
 * Borderless full screen on a live frame. AWT refuses setUndecorated once the
 * frame has a peer, so the style bits are rewritten on the native handle.
 */
class NativeFullscreen(private val frame: Frame) {

    private val user32 = User32.INSTANCE
    private var savedStyle: Int? = null
    private var savedExStyle: Int? = null
    private var savedBounds: Rectangle? = null

    /** Returns false when the platform handle is missing, so the caller can fall back. */
    fun enter(): Boolean {
        if (savedStyle != null) return true
        val hwnd = handle() ?: return false
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        savedStyle = style
        savedExStyle = exStyle
        savedBounds = frame.bounds

        val keep = style and (WinUser.WS_VISIBLE or WinUser.WS_CLIPCHILDREN or WS_CLIPSIBLINGS)
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, WinUser.WS_POPUP or keep)
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle or WS_EX_TOPMOST)

        val monitor = user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
        val info = WinUser.MONITORINFO()
        info.cbSize = info.size()
        user32.GetMonitorInfo(monitor, info)
        val r = info.rcMonitor
        return user32.SetWindowPos(
            hwnd, topmost(),
            r.left, r.top, r.right - r.left, r.bottom - r.top,
            WinUser.SWP_FRAMECHANGED or WinUser.SWP_SHOWWINDOW
        )
    }

    /** Restores the frame style and the bounds captured by [enter]. */
    fun exit(): Boolean {
        val style = savedStyle ?: return false
        val exStyle = savedExStyle ?: style
        val bounds = savedBounds
        val hwnd = handle() ?: return false
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle)
        savedStyle = null
        savedExStyle = null
        savedBounds = null
        if (bounds == null) return false
        return user32.SetWindowPos(
            hwnd, notTopmost(),
            bounds.x, bounds.y, bounds.width, bounds.height,
            WinUser.SWP_FRAMECHANGED or WinUser.SWP_SHOWWINDOW
        )
    }

    private fun handle(): WinDef.HWND? = Native.getWindowPointer(frame)?.let { WinDef.HWND(it) }

    private fun topmost(): WinDef.HWND = WinDef.HWND(Pointer.createConstant(-1))

    private fun notTopmost(): WinDef.HWND = WinDef.HWND(Pointer.createConstant(-2))

    private companion object {
        const val WS_EX_TOPMOST = 0x00000008
        const val WS_CLIPSIBLINGS = 0x04000000
    }
}
