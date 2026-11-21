package app.journal.data

import app.journal.serde.AppJson
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A killed process must not cost the journal: the crash test starts a writer,
 * kills it mid-save, and decodes whatever reached disk.
 */
class JournalCrashDurabilityTest {

    private val dir = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "nepenthe-journal-crash-${System.nanoTime()}"
    ).apply { mkdirs() }

    private var originalHome: String? = null

    @AfterTest
    fun cleanUp() {
        originalHome?.let { System.setProperty("user.home", it) }
        dir.deleteRecursively()
    }

    @Test
    fun aKilledSaveLeavesADecodableJournal() {
        val home = File(dir, "home").apply { mkdirs() }
        // The crash file is sealed with the child's key; decode with the same home.
        originalHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        val journal = File(home, ".nepenthe/journal-data.json")
        val java = File(System.getProperty("java.home") ?: ".", "bin/java").absolutePath
        val classpath = System.getProperty("java.class.path") ?: ""

        val childLog = File(dir, "child.log")
        val child = ProcessBuilder(
            java, "-Duser.home=${home.absolutePath}", "-cp", classpath,
            "app.journal.data.JournalCrashWriterMainKt"
        ).redirectErrorStream(true).redirectOutput(childLog).start()

        val deadline = System.currentTimeMillis() + 20_000
        var written = 0L
        while (System.currentTimeMillis() < deadline && written < 20_000) {
            written = if (journal.exists()) journal.length() else 0L
            if (written == 0L) Thread.sleep(20)
        }
        Thread.sleep(200)
        child.destroyForcibly()
        child.waitFor()

        assertTrue(
            written >= 20_000,
            "the child wrote no journal; files: ${journal.parentFile?.list()?.toList()}; " +
                "exit: ${child.exitValue()}; output: ${childLog.readText().take(2_000)}"
        )
        assertNull(decode(journal), "the main journal must decode after a killed save")
        val backup = File(journal.parentFile, "journal-data.json.bak")
        if (backup.exists()) {
            assertNull(decode(backup), "the recovery copy must decode after a killed save")
        }
    }

    /** Null when the file decodes as a journal, else the parse error. */
    private fun decode(file: File): String? {
        val raw = file.readBytes()
        assertTrue(raw.isNotEmpty(), "${file.name} must not be empty")
        val text = if (JournalAtRest.looksEncrypted(raw)) JournalAtRest.open(raw) else raw.decodeToString()
        val merged = JournalSplit.merge(text, null)
        val error = merged.userParseError
        if (error == null) {
            assertNotNull(merged.snapshot, "${file.name} decoded to nothing")
            assertTrue(AppJson.json.encodeToString(merged.snapshot).isNotEmpty())
        }
        return error
    }
}
