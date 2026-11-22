package app.journal.ui.theme

import app.journal.util.PlatformLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Slider drags emit many configs; only the settled one is worth a disk write. */
internal const val THEME_SAVE_WINDOW_MS = 300L

/**
 * Coalesces theme writes: the latest config is saved once the window closes,
 * not once per slider tick. The in-memory config still updates immediately.
 */
internal class ThemeSaveCoalescer(
    private val save: (ThemeConfig) -> Unit,
    private val scope: CoroutineScope,
    private val windowMs: Long = THEME_SAVE_WINDOW_MS,
) {
    private val lock = PlatformLock()
    private var pending: ThemeConfig? = null
    private var job: Job? = null

    fun request(config: ThemeConfig) = lock.withLock {
        pending = config
        job?.cancel()
        job = scope.launch {
            delay(windowMs)
            lock.withLock {
                val toSave = pending
                pending = null
                if (toSave != null) save(toSave)
            }
        }
    }

    /** Write the newest request now; used on a window close and by tests. */
    fun flush() = lock.withLock {
        job?.cancel()
        job = null
        val toSave = pending ?: return@withLock
        pending = null
        save(toSave)
    }
}
