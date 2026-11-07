package app.journal.sync

import app.journal.log.Log
import java.io.File

/**
 * This device's own sync cursor, persisted next to the sync identity.
 *
 * In memory only, so every restart re-pushed the whole library: 54 slices,
 * 77 s from a phone (2026-10 log). A cursor is written only after a fully
 * acked cycle, so a restart resumes where the last complete cycle finished.
 */
internal class SyncCursorStore(private val dataDir: String) {

    private val file = File(dataDir, "sync-cursor.txt")
    private var loaded = false
    private var cached: Long? = null

    @Synchronized
    fun read(): Long? {
        if (!loaded) {
            loaded = true
            cached = runCatching {
                file.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
            }.getOrNull()
        }
        return cached
    }

    @Synchronized
    fun write(cursor: Long) {
        loaded = true
        cached = cursor
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(cursor.toString())
        }.onFailure {
            Log.withTag("SyncCursor").w { "Failed to persist sync cursor: ${it.message}" }
        }
    }
}
