package app.journal.sync

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncCursorStoreTest {

    @Test
    fun pullCursorRoundTripsIndependently() {
        val dir = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe-test-cursor-${System.nanoTime()}"
        ).apply { mkdirs() }
        try {
            val store = SyncCursorStore(dir.absolutePath)
            assertNull(store.read())
            assertNull(store.readPull())

            store.write(1000L)
            store.writePull(2000L, "s:9")

            assertEquals(1000L, store.read())
            assertEquals(2000L to "s:9", store.readPull())

            val reopened = SyncCursorStore(dir.absolutePath)
            assertEquals(2000L to "s:9", reopened.readPull())
        } finally {
            dir.deleteRecursively()
        }
    }
}
