package app.journal.data

import app.journal.model.Session
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformPathTest {

    @Test
    fun journalRoundTripsUnderASpaceAndUnicodeHome() {
        val home = File(
            System.getProperty("java.io.tmpdir") ?: ".",
            "nepenthe home-\u00fcnicode-\u6e2c\u8a66-${System.nanoTime()}",
        ).apply { mkdirs() }
        val previous = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.absolutePath)
            val repo = JournalRepository()
            repo.upsertSession(Session(
                id = "s:platform-path",
                createdAt = 1L,
                updatedAt = 1L,
                deviceOrigin = "test",
                title = "space and unicode",
                startTime = 1L,
            ))
            JournalStore(repo).save()

            val loaded = JournalRepository()
            JournalStore(loaded).load()
            assertEquals(
                listOf("s:platform-path"),
                loaded.sessions.value.map { it.id },
                "a home path with a space and non-ASCII characters must round trip",
            )
        } finally {
            System.setProperty("user.home", previous)
            home.deleteRecursively()
        }
    }
}
