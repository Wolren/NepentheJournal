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
        // Exact set: TestLSD contributes Euphoria + Stimulation, the
        // auto-created dw:testmix contributes Clarity (audit C7: isNotEmpty
        // passed even when ingestion was broken).
        assertEquals(3, effects.size, "TestLSD (2) + TestMix (1) effects")
        val effectNames = effects.map { it.name }
        assertTrue("Euphoria" in effectNames, "expected Euphoria effect")
        assertTrue("Stimulation" in effectNames, "expected Stimulation effect")
    }

    @Test
    fun createsMissingSubstancesAsPrimary() {
        val repo = JournalRepository()

        DoseWikiIngestor.ensureIngested(repo)

        val created = repo.substances.value.firstOrNull { it.id == "dw:testlsd" }
        assertNotNull(created, "unmatched DoseWiki entry should be created as dw:testlsd")
        assertEquals("A powerful psychedelic substance for testing", created.summary)
        assertEquals(listOf("Euphoria", "Stimulation"), created.effects)
        assertTrue("dosewiki" in created.sources, "created row should be tagged dosewiki")
        assertEquals("dw-v1", created.sourceVersion)
        assertTrue(repo.substances.value.any { it.id == "dw:testmix" }, "TestMix should be created too")
    }

    @Test
    fun overwritesStaleSeedFields() {
        val repo = JournalRepository()
        repo.upsertSubstance(
            sub("sub:test1", "TestLSD").copy(
                summary = "OLD stale summary",
                substanceClass = listOf("placeholder"),
            )
        )

        DoseWikiIngestor.ensureIngested(repo)

        val updated = repo.substances.value.first { it.id == "sub:test1" }
        assertEquals(
            "A powerful psychedelic substance for testing",
            updated.summary,
            "DoseWiki summary should overwrite the stale seed value"
        )
        assertEquals("10-75 ug", updated.dosageBands["light"])
        assertEquals("75-150 ug", updated.dosageBands["common"])
        assertEquals("20-40 minutes", updated.durationProfile["onset"])
        assertTrue("Test Acid" in updated.aliases, "aliases should merge from identification")
        assertTrue("psychedelic" in updated.substanceClass, "classes should overwrite")
        assertTrue("dosewiki" in updated.sources, "matched row should gain the dosewiki tag")
        assertEquals("dw-v1", updated.sourceVersion)
        assertEquals(listOf("Euphoria", "Stimulation"), updated.effects)
    }

    @Test
    fun ingestsInteractionsWithReasons() {
        val repo = JournalRepository()

        DoseWikiIngestor.ensureIngested(repo)

        val pair = repo.interactions.value.firstOrNull {
            (it.substanceAId == "dw:testlsd" && it.substanceBId == "dw:testmix") ||
                (it.substanceAId == "dw:testmix" && it.substanceBId == "dw:testlsd")
        }
        assertNotNull(pair, "TestLSD-TestMix interaction should be ingested")
        assertEquals(InteractionRisk.DANGEROUS, pair.riskLevel)
        assertEquals("Test reason text", pair.description)
        assertEquals(listOf("dosewiki", "tripsit"), pair.sources)
        assertTrue(
            repo.interactions.value.none { it.substanceAId.contains("mphetamine") || it.substanceBId.contains("mphetamine") },
            "class-level entries like Amphetamines should not create placeholder interactions"
        )
    }

    @Test
    fun unmatchedSeedSubstanceLeftAlone() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("sub:unknown", "NonexistentCompound"))

        DoseWikiIngestor.ensureIngested(repo)

        val untouched = repo.substances.value.first { it.id == "sub:unknown" }
        assertNull(untouched.summary, "no DoseWiki match means no field changes")
        assertTrue(untouched.sources.isEmpty(), "no DoseWiki match means no source tag")
    }

    @Test
    fun parseInteractionEntrySplitsNameAndReason() {
        val (name, reason) = DoseWikiIngestor.parseInteractionEntry("Lithium (High seizure risk here)")
        assertEquals("Lithium", name)
        assertEquals("High seizure risk here", reason)
    }

    @Test
    fun parseInteractionEntryWithoutReason() {
        val (name, reason) = DoseWikiIngestor.parseInteractionEntry("Cannabis")
        assertEquals("Cannabis", name)
        assertNull(reason)
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
        val countAfterFirst = repo.effects.value.size
        assertTrue(countAfterFirst > 0, "first call ingests effects")

        DoseWikiIngestor.reset()
        DoseWikiIngestor.ensureIngested(repo)
        // Capture-and-compare (audit C7): the old assertion re-checked
        // isNotEmpty after reset, which passed even if re-ingestion was a
        // no-op. The count must be reproduced exactly.
        assertEquals(countAfterFirst, repo.effects.value.size,
            "re-ingestion after reset must reproduce the same effect count")
    }

    private fun sub(id: String, name: String) = Substance(
        id = id, name = name,
        createdAt = 0L, updatedAt = 0L,
        cachedAt = 0L, sourceVersion = "test"
    )
}
