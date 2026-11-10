package app.journal.util

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition

/** Window geometry persisted as x,y,width,height in dp so the next launch reopens in place. */
object WindowGeometryStore {
    private fun path(): String = "${PlatformFile.dataDir()}/window.txt"

    fun load(): Pair<WindowPosition, DpSize>? {
        return try {
            if (PlatformFile.size(path()) < 0) return null
            val parts = PlatformFile.readText(path()).split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (parts.size != 4) return null
            val (x, y, w, h) = parts
            if (w < 480f || h < 480f || w > 4000f || h > 4000f) null
            else WindowPosition(x.dp, y.dp) to DpSize(w.dp, h.dp)
        } catch (_: Exception) {
            null
        }
    }

    fun save(position: WindowPosition, size: DpSize) {
        try {
            PlatformFile.writeText(
                path(),
                "${position.x.value},${position.y.value},${size.width.value},${size.height.value}"
            )
        } catch (_: Exception) {
            // Best effort: geometry is a convenience.
        }
    }
}
