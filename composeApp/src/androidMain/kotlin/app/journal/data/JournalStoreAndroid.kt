package app.journal.data

import app.journal.NepentheApp
import java.io.File

/**
 * Android actual: stores journal data as a JSON file in app internal storage.
 *
 * Path: [appContext.filesDir]/.psychonautica/journal-data.json
 *
 * Uses atomic write pattern to prevent data loss on crash:
 * 1. Write to journal-data.json.tmp (temp file, same filesystem)
 * 2. Rename tmp -> target (atomic on the same filesystem)
 * 3. Keep journal-data.json.bak as the last successful save
 * 4. On load, clean up orphaned .tmp files from prior crashes
 */
actual class JournalStore actual constructor(private val repo: JournalRepository) {

    private val baseDir: File
        get() = File(NepentheApp.appContext.filesDir, ".psychonautica")

    actual fun dataPath(): String {
        return File(baseDir, "journal-data.json").absolutePath
    }

    private fun tempPath(): String = dataPath() + ".tmp"
    private fun backupPath(): String = dataPath() + ".bak"

    actual fun load() {
        val target = File(dataPath())
        val tmp = File(tempPath())

        // Clean up orphaned temp file from a prior crash
        if (tmp.exists()) {
            System.err.println("Cleaning orphaned temp file from prior save")
            tmp.delete()
        }

        if (!target.exists()) return
        try {
            val text = target.readText()
            val snapshot = JournalJson.json.decodeFromString<JournalSnapshot>(text)
            JournalJson.apply(repo, snapshot)
        } catch (e: Exception) {
            System.err.println("Failed to load journal data: ${e.message}")
        }
    }

    actual fun save() {
        val target = File(dataPath())
        val tmp = File(tempPath())
        val backup = File(backupPath())

        try {
            // Ensure directory exists
            baseDir.mkdirs()

            // Build snapshot
            val snapshot = JournalJson.snapshot(repo)
            val text = JournalJson.json.encodeToString(snapshot)

            // Atomic write: write to temp, then rename
            tmp.writeText(text)
            if (!tmp.exists()) {
                System.err.println("Failed to write temp file: ${tmp.absolutePath}")
                return
            }

            // Rotate backup: keep previous good save
            if (target.exists()) {
                target.copyTo(backup, overwrite = true)
            }

            // Atomic rename (same filesystem)
            tmp.renameTo(target)
            if (!target.exists()) {
                System.err.println("Atomic rename failed, falling back to direct write")
                target.writeText(text)
            }
        } catch (e: Exception) {
            System.err.println("Failed to save journal data: ${e.message}")
        }
    }
}
