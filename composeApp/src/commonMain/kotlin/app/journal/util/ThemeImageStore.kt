package app.journal.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeps a picked backdrop image alive across restarts.
 *
 * The Android picker hands back a content:// URI whose read grant dies with the
 * activity, and the iOS picker a copy in the purgeable tmp directory. Both leave
 * a theme pointing at nothing. The pick is copied into the app data directory
 * instead, so the stored path stays readable for good.
 */
object ThemeImageStore {

    private const val DIR = "backdrop"
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "avif")

    private fun dir(): String = "${PlatformFile.dataDir()}/$DIR"

    /**
     * Copies [source] into the backdrop directory and returns its path. Remote
     * URLs and paths already inside that directory pass through unchanged.
     * Returns null when the copy fails.
     */
    suspend fun adopt(source: String): String? = withContext(Dispatchers.Default) {
        val trimmed = source.trim()
        if (trimmed.isEmpty()) return@withContext null
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://") || trimmed.startsWith("data:")) {
            return@withContext trimmed
        }
        if (trimmed.startsWith("${dir()}/")) return@withContext trimmed
        val ext = trimmed.substringAfterLast('.', "").lowercase().takeIf { it in IMAGE_EXTENSIONS } ?: "img"
        val target = "${dir()}/background-${currentTimeMillis()}.$ext"
        if (PlatformFile.copyToLocal(trimmed, target)) target else null
    }

    /** Deletes a stored backdrop, and only a stored backdrop: URLs and user files stay. */
    suspend fun discard(path: String?) = withContext(Dispatchers.Default) {
        if (path != null && path.startsWith("${dir()}/")) PlatformFile.delete(path)
    }
}
