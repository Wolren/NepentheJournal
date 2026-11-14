package app.journal.catalog

import app.journal.data.DoseWikiLookup
import app.journal.data.JournalRepository
import app.journal.data.JournalSnapshot
import app.journal.ingest.DoseWikiIngestor
import app.journal.model.DoseWikiSubstance
import app.journal.serde.AppJson
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The catalog projection must render like the JSON ingest did, field by field.
 * A drifted field here is a silent UI regression after the migration.
 */
class CatalogMapperTest {

    private val available: Boolean = System.getProperty("nepenthe.catalog") != null &&
        System.getProperty("nepenthe.substanceSources") != null

    @Test
    fun mappedSubstancesMatchTheIngest() {
        if (!available) return
        val directory = File(System.getProperty("nepenthe.substanceSources"))
        val repo = JournalRepository()
        repo.applySnapshot(
            AppJson.json.decodeFromString<JournalSnapshot>(
                directory.resolve("psychonautwiki_seed.json").readText(),
            ),
        )
        DoseWikiIngestor.reset()
        DoseWikiIngestor.ingest(
            repo,
            AppJson.json.decodeFromString<List<DoseWikiSubstance>>(
                directory.resolve("dosewiki_slim.json").readText(),
            ),
        )
        val runtime = repo.substances.value.associateBy { it.id }
        val catalog = CatalogSource.loadSubstances().associateBy { it.id }

        assertEquals(runtime.keys, catalog.keys)
        for (name in listOf("LSD", "Ketamine", "Caffeine", "Cannabis", "MDMA")) {
            val expected = runtime.values.firstOrNull { it.name == name } ?: continue
            val actual = assertNotNull(catalog[expected.id], "$name is missing")
            assertEquals(expected.name, actual.name, "$name name")
            assertEquals(expected.dosageBands, actual.dosageBands, "$name dose bands")
            assertEquals(expected.durationProfile, actual.durationProfile, "$name duration")
            assertEquals(expected.halfLifeMinutes, actual.halfLifeMinutes, "$name half-life")
            assertEquals(expected.tmaxMinutes, actual.tmaxMinutes, "$name tmax")
            assertEquals(
                expected.routesOfAdministration.sorted(),
                actual.routesOfAdministration.sorted(),
                "$name routes",
            )
            assertEquals(expected.aliases.sorted(), actual.aliases.sorted(), "$name aliases")
            assertEquals(
                expected.substanceClass.sorted(),
                actual.substanceClass.sorted(),
                "$name classes",
            )
            assertEquals(expected.effects.sorted(), actual.effects.sorted(), "$name effects")
            assertEquals(expected.addictionPotential, actual.addictionPotential, "$name addiction")
            assertEquals(expected.cid, actual.cid, "$name cid")
        }
    }

    @Test
    fun durationLookupReadsTheCatalog() {
        if (!available) return
        val duration = assertNotNull(CatalogSource.durationFor("LSD"))
        assertTrue(duration.routes.orEmpty().isNotEmpty())
        DoseWikiLookup.reset()
        assertEquals(duration.routes?.size, DoseWikiLookup.getDuration("LSD")?.routes?.size)
    }
}
