package app.journal.data

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalCipherDesktopTest {

    @Test
    fun quarantineKeyFileMovesTheCorruptKeyAside() {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-test-cipher-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val key = File(dir, "journal.key").apply { writeBytes(ByteArray(5)) }

            val moved = quarantineKeyFile(key)

            assertNotNull(moved)
            assertTrue(moved.name.startsWith("journal.key.corrupt-"), "moved to ${moved.name}")
            assertEquals(5L, moved.length())
        } finally {
            dir.deleteRecursively()
        }
    }
}
