package app.journal.data

import app.journal.serde.AppJson
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Repeated kill fuzz over the two persistence paths: the plain store save and the
 * sync apply-then-save. Each round kills a writer process at a different point in
 * the save cycle, then checks that the journal still decodes and never loses a save
 * the writer had already confirmed.
 *
 * Rounds default to two per mode; raise with -Pnepenthe.fuzzRounds=N.
 */
class JournalCrashFuzzTest {

    private val root = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "nepenthe-crash-fuzz-${System.nanoTime()}"
    ).apply { mkdirs() }

    private var previousHome: String? = null

    @AfterTest
    fun cleanUp() {
        previousHome?.let { System.setProperty("user.home", it) }
        root.deleteRecursively()
    }

    private val rounds: Int
        get() = System.getProperty("nepenthe.fuzzRounds")?.toIntOrNull()?.coerceIn(1, 30) ?: 2

    @Test
    fun storeSavesSurviveRepeatedKills() = fuzz("store")

    @Test
    fun syncApplyThenSaveSurvivesRepeatedKills() = fuzz("sync")

    private fun fuzz(mode: String) {
        val delays = listOf(80L, 250L, 450L)
        for (round in 1..rounds) {
            val home = File(root, "$mode-$round").apply { mkdirs() }
            previousHome = System.getProperty("user.home")
            System.setProperty("user.home", home.absolutePath)
            try {
                // First run establishes a key and a base journal, so later rounds have a file to keep.
                runChild(home, mode, seed = round.toLong(), killAfterMs = 700L)
                val baseline = decode(home.absolutePath, "journal-data.json")

                // Three kills on the same home: each continues from a crashed state, so the
                // recovery path is fuzzed too, not just a single clean save.
                for (kill in 1..3) {
                    val killDelay = delays[(round + kill) % delays.size]
                    runChild(home, mode, seed = round * 100L + kill, killAfterMs = killDelay)
                    val after = decode(home.absolutePath, "journal-data.json")
                    val label = "$mode round $round kill $kill"

                    assertNotNull(after.snapshot, "$label: the journal must decode after a kill")
                    assertNull(after.error, "$label: the journal must not report a parse error")
                    assertTrue(after.confirmed > 0, "$label: the writer never confirmed a save")
                    assertTrue(after.sessions >= after.confirmed,
                        "$label: lost a confirmed save (disk ${after.sessions}, confirmed ${after.confirmed})")
                    assertTrue(after.sessions >= baseline.sessions,
                        "$label: the journal went backwards (${after.sessions} < ${baseline.sessions})")
                    decode(home.absolutePath, "journal-data.json.bak").let { backup ->
                        assertNull(backup.error, "$label: the recovery copy must decode")
                    }
                }
            } finally {
                previousHome?.let { System.setProperty("user.home", it) }
            }
        }
    }

    private class Decoded(
        val sessions: Int,
        val confirmed: Int,
        val error: String?,
        val snapshot: Any?,
    )

    /** Session count on disk, the last confirmed count from the writer, and any parse error. */
    private fun decode(home: String, name: String): Decoded {
        val file = File(home, ".nepenthe/$name")
        val confirmed = File(home, ".nepenthe/fuzz-confirmed.txt").let { confirm ->
            if (!confirm.exists()) 0
            else confirm.readLines().lastOrNull { it.isNotBlank() }?.toIntOrNull() ?: 0
        }
        if (!file.exists()) return Decoded(0, confirmed, null, null)
        val raw = file.readBytes()
        if (raw.isEmpty()) return Decoded(0, confirmed, "empty file", null)
        val text = if (JournalAtRest.looksEncrypted(raw)) {
            runCatching { JournalAtRest.open(raw) }.getOrElse { return Decoded(0, confirmed, it.message, null) }
        } else {
            raw.decodeToString()
        }
        val merged = JournalSplit.merge(text, null)
        if (merged.userParseError != null) return Decoded(0, confirmed, merged.userParseError, null)
        assertNotNull(merged.snapshot, "$name decoded to nothing")
        assertTrue(AppJson.json.encodeToString(merged.snapshot).isNotEmpty())
        return Decoded(merged.snapshot.sessions.size, confirmed, null, merged.snapshot)
    }

    private fun runChild(home: File, mode: String, seed: Long, killAfterMs: Long) {
        val java = File(System.getProperty("java.home") ?: ".", "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path") ?: ""
        val confirm = File(home, ".nepenthe/fuzz-confirmed.txt")
        val child = ProcessBuilder(
            java, "-Duser.home=${home.absolutePath}", "-cp", classpath,
            "app.journal.data.CrashFuzzWriterMainKt",
            home.absolutePath, mode, seed.toString(),
        ).redirectErrorStream(true).redirectOutput(File(home, "child.log")).start()
        try {
            // Kill only after the writer confirmed one save, so the kill lands inside the cycle.
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline && (!confirm.isFile || confirm.length() == 0L)) {
                Thread.sleep(10)
            }
            Thread.sleep(killAfterMs)
        } finally {
            child.destroyForcibly()
            child.waitFor()
        }
    }
}
