package app.journal.data

import app.journal.model.*
import kotlin.test.*
import java.io.File

class JournalStoreTest {

    private fun withTempHome(test: (String) -> Unit) {
        val tmpDir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-test-${System.nanoTime()}")
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

    @Test
    fun saveAndLoadRoundtrip() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            substanceClass = listOf("Classical Psychedelic"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Visual"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Test Session", startTime = 1000L
        ))

        val store = JournalStore(repo)
        val path = store.dataPath()
        store.save()
        assertTrue(File(path).exists(), "Journal file should exist after save")

        val repo2 = JournalRepository()
        val store2 = JournalStore(repo2)
        store2.load()
        assertEquals(1, repo2.substances.value.size)
        assertEquals("LSD", repo2.substances.value.first().name)
        assertEquals(1, repo2.sessions.value.size)
        assertEquals("Test Session", repo2.sessions.value.first().title)
    }

    @Test
    fun saveAndLoadMultipleEntityTypes() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "MDMA", createdAt = 0L, updatedAt = 0L, deviceOrigin = "system",
            substanceClass = listOf("Empathogen"), routesOfAdministration = listOf("Oral"),
            effects = listOf("Euphoria"), dosageBands = emptyMap(),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Session", startTime = 1000L
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            sessionId = "s:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 120.0, unit = "mg", timestamp = 1000L
        ))
        repo.setShulginRating(true)
        repo.setObsidianAutoExport(true)

        JournalStore(repo).save()

        val repo2 = JournalRepository()
        JournalStore(repo2).load()
        assertEquals(1, repo2.substances.value.size)
        assertEquals(1, repo2.sessions.value.size)
        assertEquals(1, repo2.doses.value.size)
        assertTrue(repo2.useShulginRating.value)
        assertTrue(repo2.obsidianAutoExport.value)
    }

    @Test
    fun loadNonExistentFileIsNoOp() = withTempHome { _ ->
        val repo = JournalRepository()
        JournalStore(repo).load() // should not throw
        assertTrue(repo.sessions.value.isEmpty())
    }

    @Test
    fun saveOverwritesExistingFile() = withTempHome { _ ->
        val repo = JournalRepository()
        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "First", startTime = 1000L
        ))
        JournalStore(repo).save()

        repo.upsertSession(Session(
            id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
            title = "Second", startTime = 2000L
        ))
        JournalStore(repo).save()

        val repo2 = JournalRepository()
        JournalStore(repo2).load()
        assertEquals(1, repo2.sessions.value.size)
        assertEquals("Second", repo2.sessions.value.first().title)
    }

    @Test
    fun dataPathReturnsNonEmpty() = withTempHome { _ ->
        val repo = JournalRepository()
        val path = JournalStore(repo).dataPath()
        assertTrue(path.isNotBlank())
        assertTrue(path.endsWith(".json"), "dataPath should end with .json: $path")
    }
}
