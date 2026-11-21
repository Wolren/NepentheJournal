package app.journal.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DurableFileTest {

    @Test
    fun replacesTheFileWithTheNewBytes() {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-durable-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val file = File(dir, "data.json").apply { writeText("old") }

            replaceDurably(file, "new".toByteArray())

            assertContentEquals("new".toByteArray(), file.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }
}
