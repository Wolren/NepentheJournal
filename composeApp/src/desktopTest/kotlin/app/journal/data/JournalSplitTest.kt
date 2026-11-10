package app.journal.data

import app.journal.model.*
import app.journal.serde.AppJson
import java.io.File
import kotlin.test.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class JournalSplitTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-split-${System.nanoTime()}")
        tmpDir.mkdirs()
        val origHome = System.getProperty("user.home")
        System.setProperty("user.home", tmpDir.absolutePath)
        try {
            test(tmpDir.absolutePath)
        } finally {
            System.setProperty("user.home", origHome)
            tmpDir.deleteRecursively()
        }
    }

    private fun substance(name: String = "LSD") = Substance(
        id = "sub:$name",
        name = name,
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test",
        substanceClass = listOf("Classical Psychedelic"),
        routesOfAdministration = listOf("Oral"),
        effects = listOf("Visual"),
        dosageBands = emptyMap(),
        cachedAt = 0L,
        sourceVersion = "test",
    )

    private fun session(title: String) = Session(
        id = "s:$title",
        createdAt = 1_700_000_000_000L,
        updatedAt = 1_700_000_000_000L,
        deviceOrigin = "test",
        title = title,
        startTime = 1_700_000_000_000L,
    )

    @Test
    fun saveWritesTheLibraryBesideTheUserFile() = withTempHome { home ->
        val repo = JournalRepository()
        repo.upsertSubstance(substance())
        repo.upsertSession(session("first"))
        val store = JournalStore(repo)
        store.save()

        val userFile = File(store.dataPath())
        val userSnapshot = AppJson.json.decodeFromString<JournalSnapshot>(userFile.readJournal())
        assertTrue(userSnapshot.substances.isEmpty(), "the user file must not carry the library")
        assertEquals(1, userSnapshot.sessions.size)

        val libraryFile = File(home, ".nepenthe/journal-library.json")
        assertTrue(libraryFile.exists(), "the library file must exist")
        val librarySnapshot = AppJson.json.decodeFromString<JournalSnapshot>(libraryFile.readJournal())
        assertEquals(1, librarySnapshot.substances.size)
        assertTrue(librarySnapshot.sessions.isEmpty(), "the library file must not carry user data")
    }

    @Test
    fun onlyALibraryChangeRewritesTheLibraryFile() = withTempHome { home ->
        val repo = JournalRepository()
        repo.upsertSubstance(substance())
        val store = JournalStore(repo)
        store.save()
        val libraryFile = File(home, ".nepenthe/journal-library.json")
        val firstBytes = libraryFile.readBytes()

        repo.upsertSession(session("dose-day"))
        store.save()
        assertContentEquals(firstBytes, libraryFile.readBytes(), "a user-data save must not rewrite the library")

        repo.upsertSubstance(substance("Psilocybin"))
        store.save()
        assertFalse(firstBytes.contentEquals(libraryFile.readBytes()), "a library change must rewrite the library")
    }

    @Test
    fun loadMergesTheUserAndLibraryFiles() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(substance())
        repo.upsertSession(session("first"))
        JournalStore(repo).save()

        val reloaded = JournalRepository()
        JournalStore(reloaded).load()
        assertEquals(listOf("LSD"), reloaded.substances.value.map { it.name })
        assertEquals(listOf("first"), reloaded.sessions.value.map { it.title })
    }

    @Test
    fun aLegacyFullFileSplitsOnTheNextSave() = withTempHome { home ->
        val dir = File(home, ".nepenthe").apply { mkdirs() }
        val legacyRepo = JournalRepository()
        legacyRepo.upsertSubstance(substance())
        legacyRepo.upsertSession(session("legacy"))
        File(dir, "journal-data.json").writeText(AppJson.json.encodeToString(legacyRepo.fullSnapshot()))

        val repo = JournalRepository()
        val store = JournalStore(repo)
        store.load()
        assertEquals(1, repo.substances.value.size, "the legacy file must load its library")

        store.save(fullBackup = false)
        val userSnapshot = AppJson.json.decodeFromString<JournalSnapshot>(File(store.dataPath()).readJournal())
        assertTrue(userSnapshot.substances.isEmpty(), "the save must move the library out of the user file")
        val library = AppJson.json.decodeFromString<JournalSnapshot>(
            File(dir, "journal-library.json").readJournal()
        )
        assertEquals(1, library.substances.size, "the save must write the library file")
    }
}
