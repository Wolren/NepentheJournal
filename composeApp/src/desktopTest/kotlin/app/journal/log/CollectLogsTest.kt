package app.journal.log

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * Pins the diagnostics bundle behind the Settings > Developer export button.
 * PRIVACY.md and the in-app privacy card now promise users that rolling logs
 * and crash dumps can be exported by hand, so both must appear in the output.
 */
class CollectLogsTest {

    private fun tempAppDir(): File = Files.createTempDirectory("nepenthe-collectlogs").toFile()

    @Test
    fun bundleIncludesRollingLogsAndCrashDumps() {
        val dir = tempAppDir()
        try {
            File(dir, "nepenthe.log").writeText("1700000000000 [Main] INFO: started\\n")
            File(dir, "nepenthe.log.1").writeText("1699999990000 [Main] INFO: rotated\\n")
            val crashDir = File(dir, "crashlogs").apply { mkdirs() }
            File(crashDir, "crash-1700000001000.dump").writeText("Thread: main\\nboom")

            val bundle = collectLogs(dir.absolutePath)

            assertContains(bundle, "=== Nepenthe Journal Crash Log Bundle ===")
            assertContains(bundle, "--- Rolling log files (2 file(s)) ---")
            assertContains(bundle, ">>> nepenthe.log (")
            assertContains(bundle, ">>> nepenthe.log.1 (")
            assertContains(bundle, "started")
            assertContains(bundle, "rotated")
            assertContains(bundle, "=== Crash dumps (1 file(s)) ===")
            assertContains(bundle, "crash-1700000001000.dump")
            assertContains(bundle, "boom")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryIsReportedNotThrown() {
        val dir = tempAppDir()
        val missing = File(dir, "does-not-exist").absolutePath
        try {
            val bundle = collectLogs(missing)
            assertContains(bundle, "Log directory $missing does not exist")
            assertContains(bundle, "No crashlogs directory")
            assertFalse(bundle.startsWith("Error collecting"), bundle)
        } finally {
            dir.deleteRecursively()
        }
    }
}
