package app.journal.ingest

import app.journal.data.JournalRepository
import app.journal.model.*
import kotlin.test.*

class DoseWikiIngestorTest {

    @BeforeTest
    fun setup() {
        DoseWikiIngestor.reset()
    }

    @AfterTest
    fun cleanup() {
        DoseWikiIngestor.reset()
    }

    @Test
    fun ingestsEffectsForMatchingSubstance() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("sub:test1", "TestLSD"))

        DoseWikiIngestor.ensureIngested(repo)

        val effects = repo.effects.value
        assertTrue(effects.isNotEmpty(), "should ingest effects for TestLSD")
        val effectNames = effects.map { it.name }
        assertTrue("Euphoria" in effectNames, "expected Euphoria effect")
        assertTrue("Stimulation" in effectNames, "expected Stimulation effect")
    }

    @Test
    fun secondCallIsNoopDueToIngestedFlag() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("sub:test1", "TestLSD"))

        DoseWikiIngestor.ensureIngested(repo)
        val countAfterFirst = repo.effects.value.size
        assertTrue(countAfterFirst > 0, "first call ingests effects")

        DoseWikiIngestor.ensureIngested(repo)
        assertEquals(countAfterFirst, repo.effects.value.size, "second call should be no-op")
    }

    @Test
    fun resetAllowsReingestion() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("sub:test1", "TestLSD"))

        DoseWikiIngestor.ensureIngested(repo)
        assertTrue(repo.effects.value.isNotEmpty(), "first call ingests")

        DoseWikiIngestor.reset()
        DoseWikiIngestor.ensureIngested(repo)
        assertTrue(repo.effects.value.isNotEmpty(), "after reset should still ingest")
    }

    @Test
    fun skipsUnmatchedSubstances() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("sub:unknown", "NonexistentCompound"))

        DoseWikiIngestor.ensureIngested(repo)
        assertEquals(0, repo.effects.value.size, "no match = no effects")
    }

    private fun sub(id: String, name: String) = Substance(
        id = id, name = name,
        createdAt = 0L, updatedAt = 0L,
        cachedAt = 0L, sourceVersion = "test"
    )
}
