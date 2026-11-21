package app.journal.data

import app.journal.model.Dose
import app.journal.model.Session
import java.io.File
import kotlin.test.Test

/**
 * Manual timing harness for journal scaling: how long a save and a load take as the
 * history grows. Opt in with -Pnepenthe.bench=true; a normal test run returns
 * immediately. Numbers print with a BENCH prefix.
 */
class JournalScaleBenchmarkTest {

    @Test
    fun saveAndLoadScaleWithHistorySize() {
        if (System.getProperty("nepenthe.bench") != "true") return
        val home = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-bench-${System.nanoTime()}")
        home.mkdirs()
        val previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            for (count in listOf(100, 1000, 4000)) {
                val repo = JournalRepository()
                val now = 1_700_000_000_000L
                val sessions = (0 until count).map { i ->
                    Session(
                        id = "s$i",
                        createdAt = now + i,
                        updatedAt = now + i,
                        deviceOrigin = "bench",
                        title = "Session $i",
                        startTime = now + i * 86_400_000L,
                        set = "calm and curious",
                        setting = "home, dim light",
                        intention = "explore",
                        outcome = "clear afterwards",
                        notes = "n".repeat(200),
                        freeform = "## heading\n".repeat(20),
                        rating = 5,
                        tags = listOf("tagA", "tagB", "tagC"),
                    )
                }
                val doses = (0 until count * 3).map { i ->
                    Dose(
                        id = "d$i",
                        createdAt = now + i,
                        updatedAt = now + i,
                        deviceOrigin = "bench",
                        sessionId = "s${i % count}",
                        substanceId = "substance:$i",
                        routeOfAdministration = "oral",
                        amount = 10.0,
                        unit = "mg",
                        timestamp = now + i,
                    )
                }
                val applyStart = System.currentTimeMillis()
                repo.applyBatch(sessions = sessions, doses = doses)
                val applyMs = System.currentTimeMillis() - applyStart

                val store = JournalStore(repo)
                val saveStart = System.currentTimeMillis()
                store.save(fullBackup = false)
                val saveMs = System.currentTimeMillis() - saveStart

                val fresh = JournalRepository()
                val loadStart = System.currentTimeMillis()
                JournalStore(fresh).load()
                val loadMs = System.currentTimeMillis() - loadStart

                val dataKb = File(store.dataPath()).length() / 1024
                println(
                    "BENCH sessions=$count doses=${doses.size} apply=${applyMs}ms save=${saveMs}ms " +
                        "load=${loadMs}ms dataFile=${dataKb}KB loadedSessions=${fresh.sessions.value.size}"
                )
            }
        } finally {
            if (previousHome != null) System.setProperty("user.home", previousHome)
            home.deleteRecursively()
        }
    }
}
