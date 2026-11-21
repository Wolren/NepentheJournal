package app.journal.data

import app.journal.model.Session
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * A light save (autosave, appending a sync delta) must not rewrite the recovery copy.
 *
 * Every accepted push or delta ends in a light save. The store used to refresh
 * the whole `.bak` file on every one of them, so each delta wrote the journal
 * twice. The `.bak` is still created when missing, so recovery is never lost.
 */
class LightSaveBackupTest {

    private val home = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-light-${System.nanoTime()}")
    private var previousHome: String? = null

    @AfterTest
    fun tearDown() {
        previousHome?.let { System.setProperty("user.home", it) }
        home.deleteRecursively()
    }

    private fun session(id: String, title: String) = Session(
        id = id,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "light-test",
        title = title,
        startTime = 1_700_000_000_000L,
    )

    @Test
    fun aLightSaveKeepsAnExistingRecoveryCopyUntouched() {
        home.mkdirs()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val library = File(home, ".nepenthe/journal-data.json")

        repo.upsertSession(session("s:1", "first"))
        store.save()
        // A recovery copy is written from an EXISTING journal, so take one more full save.
        repo.upsertSession(session("s:2", "second"))
        store.save()
        val backup = File(library.absolutePath + ".bak")
        assertTrue(backup.isFile, "a full save must create the recovery copy")
        val backupBefore = backup.readBytes()

        repo.upsertSession(session("s:3", "third"))
        store.save(fullBackup = false)

        assertContentEquals(
            backupBefore,
            backup.readBytes(),
            "a light save must not rewrite the whole recovery copy",
        )
        assertTrue(JournalAtRest.open(library.readBytes()).contains("third"),
            "the light save must still persist the new row")
    }

    @Test
    fun aLightSaveRecreatesAMissingRecoveryCopy() {
        home.mkdirs()
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        val repo = JournalRepository()
        val store = JournalStore(repo)
        val library = File(home, ".nepenthe/journal-data.json")

        repo.upsertSession(session("s:1", "first"))
        store.save()
        val backup = File(library.absolutePath + ".bak")
        backup.delete()

        repo.upsertSession(session("s:2", "second"))
        store.save(fullBackup = false)

        assertTrue(backup.isFile, "a missing recovery copy is recreated even on a light save")
    }
}
