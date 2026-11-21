package app.journal.data

import app.journal.model.Dose
import app.journal.model.Session
import app.journal.sync.SyncResponse
import app.journal.sync.applySyncResponse
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.random.Random

/**
 * Fuzz writer for the crash tests: applies one store or sync mutation, saves, then
 * records the confirmed session count. The parent kills the process at a random
 * point, so the save can die in any phase.
 */
fun main(args: Array<String>) {
    val home = args.getOrNull(0) ?: return
    val mode = args.getOrNull(1) ?: "store"
    val seed = args.getOrNull(2)?.toLongOrNull() ?: 1L
    System.setProperty("user.home", home)

    val repo = JournalRepository()
    val store = JournalStore(repo)
    // Continue the journal the previous round left; without this load each child starts empty
    // and a later kill can leave fewer sessions than the baseline.
    store.load()
    val confirm = File(home, ".nepenthe/fuzz-confirmed.txt").apply { parentFile?.mkdirs() }
    val random = Random(seed)
    var n = repo.sessions.value.size

    while (true) {
        val now = System.currentTimeMillis()
        if (mode == "sync") {
            val batch = (0 until 3).map { i -> "fuzz:${n + i}" }
            val response = SyncResponse(
                success = true,
                sessions = batch.map { id ->
                    Session(
                        id = id,
                        title = UUID.randomUUID().toString(),
                        startTime = now,
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = "fuzz",
                    )
                },
                doses = (0 until 6).map { i ->
                    Dose(
                        id = "fuzzdose:${n + i}",
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = "fuzz",
                        sessionId = batch[i / 2],
                        substanceId = "substance:fuzz",
                        routeOfAdministration = "oral",
                        amount = 1.0,
                        unit = "mg",
                        timestamp = now,
                    )
                },
            )
            applySyncResponse(repo, response)
        } else {
            repo.applyBatch(
                sessions = listOf(
                    Session(
                        id = "fuzz:store:$n",
                        title = UUID.randomUUID().toString(),
                        startTime = now,
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = "fuzz",
                    )
                ),
                doses = listOf(
                    Dose(
                        id = "fuzzdose:store:$n",
                        createdAt = now,
                        updatedAt = now,
                        deviceOrigin = "fuzz",
                        sessionId = "fuzz:store:$n",
                        substanceId = "substance:fuzz",
                        routeOfAdministration = "oral",
                        amount = 1.0,
                        unit = "mg",
                        timestamp = now,
                    )
                ),
            )
        }
        store.save(fullBackup = random.nextBoolean())
        n = repo.sessions.value.size
        // Written only after save returned: the rename already landed for this count.
        FileOutputStream(confirm, true).use { out ->
            out.write("$n\n".toByteArray())
            out.fd.sync()
        }
    }
}
