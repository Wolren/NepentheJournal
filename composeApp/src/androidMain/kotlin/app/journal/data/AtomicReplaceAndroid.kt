package app.journal.data

import app.journal.log.Log
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Moves [tmp] over [target] with retries. Never writes [target] in place: a torn
 * target after a crash cannot be told apart from a corrupt journal. A failed move
 * leaves [target] untouched and returns false.
 */
internal fun replaceFileAtomically(
    tmp: File,
    target: File,
    attempts: Int = 3,
    delayMs: Long = 50L,
    rename: (File, File) -> Boolean = ::moveReplacing,
): Boolean {
    repeat(attempts) { attempt ->
        if (rename(tmp, target)) return true
        if (attempt < attempts - 1 && delayMs > 0) {
            runCatching { Thread.sleep(delayMs) }
        }
    }
    Log.withTag("JournalStore").w { "Atomic rename failed after $attempts attempts; previous file kept" }
    return false
}

private fun moveReplacing(from: File, to: File): Boolean = try {
    Files.move(
        from.toPath(),
        to.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.ATOMIC_MOVE,
    )
    true
} catch (e: AtomicMoveNotSupportedException) {
    runCatching {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }.isSuccess
} catch (e: Exception) {
    false
}

