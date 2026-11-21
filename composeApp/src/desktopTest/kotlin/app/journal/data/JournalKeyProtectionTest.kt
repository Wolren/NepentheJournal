package app.journal.data

import app.journal.model.Session
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * On Windows the journal key is a DPAPI blob, not 32 raw bytes: a copied profile folder
 * must not carry a usable key. The same home must still open the sealed journal.
 */
class JournalKeyProtectionTest {

    private var previousHome: String? = null

    private fun withTempHome(test: (File) -> Unit) {
        val home = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-key-protect-${System.nanoTime()}")
        home.mkdirs()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            test(home)
        } finally {
            previousHome?.let { System.setProperty("user.home", it) }
            home.deleteRecursively()
        }
    }

    @AfterTest
    fun restoreHome() {
        previousHome?.let { System.setProperty("user.home", it) }
    }

    @Test
    fun windowsStoresTheKeyAsADpapiBlob() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return
        withTempHome { home ->
            val repo = JournalRepository()
            val now = 1_700_000_000_000L
            repo.upsertSession(
                Session(
                    id = "s:1",
                    title = "protected key",
                    startTime = now,
                    createdAt = now,
                    updatedAt = now,
                    deviceOrigin = "test",
                )
            )
            JournalStore(repo).save()

            val keyFile = File(home, ".nepenthe/journal.key")
            assertTrue(keyFile.isFile, "the key file must exist")
            assertNotEquals(32L, keyFile.length(),
                "a Windows key file must be a DPAPI blob, not the raw key")

            val reopened = JournalRepository()
            JournalStore(reopened).load()
            assertEquals(1, reopened.sessions.value.size,
                "the wrapped key must still open the journal")
        }
    }
}
