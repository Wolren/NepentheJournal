package app.journal.data

import app.journal.model.Session
import java.util.UUID

/**
 * Saves growing journals until the crash test kills the process, so the killed
 * writer dies inside a save instead of unwinding.
 */
fun main() {
    val repo = JournalRepository()
    val store = JournalStore(repo)
    val start = System.currentTimeMillis()
    var n = 0
    while (true) {
        repo.upsertSession(
            Session(
                id = "s:$n",
                title = "crash $n",
                startTime = start + n,
                createdAt = start,
                updatedAt = start + n,
                deviceOrigin = "crash-test",
                // Random text keeps zstd from shrinking the file to a few bytes.
                notes = (0 until 3).joinToString(" ") { UUID.randomUUID().toString() },
            )
        )
        store.save(fullBackup = n % 4 == 0)
        n++
    }
}
