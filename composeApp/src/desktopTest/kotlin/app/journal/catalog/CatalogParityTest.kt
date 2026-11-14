package app.journal.catalog

import app.journal.data.JournalRepository
import app.journal.data.JournalSnapshot
import app.journal.ingest.DoseWikiIngestor
import app.journal.model.DoseWikiSubstance
import app.journal.serde.AppJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The catalog builder must reproduce what the app's startup ingest produced.
 * Both sides read the same production sources, so the row count and a sample
 * of ids have to match or the migration would orphan doses on the new database.
 */
class CatalogParityTest {

    private val catalogBuilt: Boolean = System.getProperty("nepenthe.catalog") != null

    private val sources: File? = System.getProperty("nepenthe.substanceSources")
        ?.let(::File)
        ?.takeIf { it.isDirectory }

    @Test
    fun builderMatchesTheRuntimeIngest() {
        if (!catalogBuilt) return
        val directory = assertNotNull(sources, "test sources directory is missing")
        val seedText = directory.resolve("psychonautwiki_seed.json").readText()
        val dosewikiText = directory.resolve("dosewiki_slim.json").readText()

        val repo = JournalRepository()
        repo.applySnapshot(AppJson.json.decodeFromString<JournalSnapshot>(seedText))
        val afterSeed = repo.substances.value.size
        val entries = AppJson.json.decodeFromString<List<DoseWikiSubstance>>(dosewikiText)
        DoseWikiIngestor.reset()
        DoseWikiIngestor.ingest(repo, entries)
        println("seed rows=$afterSeed dosewiki entries=${entries.size} after ingest=${repo.substances.value.size}")

        val runtime = repo.substances.value
        assertEquals(SubstanceCatalog.count().toInt(), runtime.size)

        val samples = listOf("LSD", "Ketamine", "Caffeine")
        for (name in samples) {
            val fromRepo = runtime.firstOrNull { it.name == name } ?: continue
            val fromCatalog = assertNotNull(
                SubstanceCatalog.byId(fromRepo.id),
                "$name is missing from the catalog",
            )
            assertEquals(fromRepo.name, fromCatalog.name)
        }
    }
}
