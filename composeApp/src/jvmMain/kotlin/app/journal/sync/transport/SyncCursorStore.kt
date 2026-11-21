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
    private val pullFile = File(dataDir, "sync-pull-cursor.txt")
    private var loaded = false
    private var cached: Long? = null
    private var pullLoaded = false
    private var cachedPull: Pair<Long, String>? = null

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

    /** The pull half of the cursor: the composite (updatedAt, id) cut after the last complete drain. */
    @Synchronized
    fun readPull(): Pair<Long, String>? {
        if (!pullLoaded) {
            pullLoaded = true
            cachedPull = runCatching {
                val lines = pullFile.takeIf { it.isFile }?.readLines().orEmpty()
                if (lines.size >= 2) lines[0].trim().toLongOrNull()?.let { it to lines[1] } else null
            }.getOrNull()
        }
        return cachedPull
    }

    @Synchronized
    fun writePull(cursor: Long, id: String) {
        pullLoaded = true
        cachedPull = cursor to id
        runCatching {
            pullFile.parentFile?.mkdirs()
            pullFile.writeText("$cursor\n$id")
        }.onFailure {
            Log.withTag("SyncCursor").w { "Failed to persist pull cursor: ${it.message}" }
        }
    }
}
