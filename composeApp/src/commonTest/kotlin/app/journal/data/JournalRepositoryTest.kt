package app.journal.data

import app.journal.model.Substance
import app.journal.model.Dose
import kotlin.test.*

class JournalRepositoryTest {

    private fun sampleSubstance(id: String, name: String, classes: List<String> = listOf("Classical Psychedelic")) =
        Substance(
            id = id,
            createdAt = 0L,
            updatedAt = 0L,
            deviceOrigin = "test",
            name = name,
            aliases = listOf(name.lowercase()),
            substanceClass = classes,
            cachedAt = 0L,
            sourceVersion = "test"
        )

    private fun sampleDose(id: String, substanceId: String, sessionId: String, timestamp: Long, amount: Double = 100.0) =
        Dose(
            id = id,
            createdAt = 0L,
            updatedAt = 0L,
            deviceOrigin = "test",
            sessionId = sessionId,
            substanceId = substanceId,
            routeOfAdministration = "Oral",
            amount = amount,
            unit = "mg",
            timestamp = timestamp
        )

    @Test
    fun upsertReplacesExistingById() {
        val repo = JournalRepository()
        val a = sampleSubstance("sub:1", "LSD")
        repo.upsertSubstance(a)
        repo.upsertSubstance(a.copy(name = "Lucy"))
        assertEquals(1, repo.substances.value.size)
        assertEquals("Lucy", repo.substances.value.first().name)
    }

    @Test
    fun deleteSessionRemovesOrphanedDosesAndNotes() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(
            app.journal.model.Session(
                id = "s:1", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                title = "Trip", startTime = 1000L
            )
        )
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.deleteSession("s:1")
        assertTrue(repo.sessions.value.isEmpty())
        assertTrue(repo.doses.value.isEmpty())
    }

    @Test
    fun searchSubstancesMatchesNameAndAliasCaseInsensitive() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD", listOf("Lysergamide")).copy(aliases = listOf("Lucy")))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA", listOf("Empathogen")))
        val byName = repo.searchSubstances("mdma")
        assertEquals(1, byName.size)
        assertEquals("MDMA", byName.first().name)
        val byAlias = repo.searchSubstances("lucy")
        assertEquals(1, byAlias.size)
        assertEquals("LSD", byAlias.first().name)
    }

    @Test
    fun dosesForSessionFiltersBySessionId() {
        val repo = JournalRepository()
        repo.upsertDose(sampleDose("d:1", "sub:1", "s:1", 1000L))
        repo.upsertDose(sampleDose("d:2", "sub:1", "s:2", 1000L))
        assertEquals(1, repo.dosesForSession("s:1").size)
        assertEquals(1, repo.dosesForSession("s:2").size)
    }
}
