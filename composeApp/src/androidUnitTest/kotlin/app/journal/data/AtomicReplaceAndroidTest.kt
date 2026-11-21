package app.journal.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicReplaceAndroidTest {

    private fun tempDir(): File = File(
        System.getProperty("java.io.tmpdir") ?: ".",
        "nepenthe-atomic-${System.nanoTime()}"
    ).apply { mkdirs() }

    @Test
    fun aFailedRenameLeavesTheTargetUntouched() {
        val dir = tempDir()
        try {
            val target = File(dir, "journal-data.json").apply { writeText("old") }
            val tmp = File(dir, "journal-data.json.tmp").apply { writeText("new") }
            val ok = replaceFileAtomically(tmp, target, attempts = 3, delayMs = 0L) { _, _ -> false }
            assertFalse(ok, "a rename that never succeeds must report failure")
            assertEquals("old", target.readText(), "the target must never be written in place")
            assertEquals("new", tmp.readText(), "the caller keeps the temp for its own cleanup")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun aLaterRenameRetrySucceeds() {
        val dir = tempDir()
        try {
            val target = File(dir, "journal-data.json").apply { writeText("old") }
            val tmp = File(dir, "journal-data.json.tmp").apply { writeText("new") }
            var calls = 0
            val ok = replaceFileAtomically(tmp, target, attempts = 3, delayMs = 0L) { from, to ->
                calls++
                if (calls < 2) {
                    false
                } else {
                    java.nio.file.Files.move(
                        from.toPath(),
                        to.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                    true
                }
            }
            assertTrue(ok, "the second rename attempt must succeed")
            assertEquals("new", target.readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}
