package app.journal.ingest

import app.journal.data.JournalRepository
import app.journal.model.Substance
import kotlin.test.*

/**
 * Guards the real bundled taxonomy artifact: it must parse and tag
 * real substance names. Catches schema drift (like a mistyped field)
 * that inline-JSON unit tests cannot see.
 */
class DosewikiTaxonomyResourceTest {

    private fun sub(id: String, name: String) = Substance(
        id = id, name = name, createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
        routesOfAdministration = listOf("Oral"), effects = listOf("X"),
        dosageBands = emptyMap(), cachedAt = 0L, sourceVersion = "test",
    )

    @Test
    fun appliesRealBundledTags() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("cid:1", "LSD"))
        repo.upsertSubstance(sub("cid:2", "Diazepam"))
        assertEquals(2, DosewikiTaxonomy.applyTags(repo))
        val lsd = repo.substances.value.first { it.name == "LSD" }
        assertTrue(
            lsd.curatedSections.any { it.category == "psychedelic" && it.label == "Lysergamide" },
            "LSD tags: ${lsd.curatedSections}",
        )
        val diazepam = repo.substances.value.first { it.name == "Diazepam" }
        assertTrue(
            diazepam.curatedSections.any { it.label == "Benzodiazepines" },
            "Diazepam tags: ${diazepam.curatedSections}",
        )
    }

    @Test
    fun secondApplyIsNoop() {
        val repo = JournalRepository()
        repo.upsertSubstance(sub("cid:1", "LSD"))
        assertEquals(1, DosewikiTaxonomy.applyTags(repo))
        assertEquals(0, DosewikiTaxonomy.applyTags(repo))
    }
}
