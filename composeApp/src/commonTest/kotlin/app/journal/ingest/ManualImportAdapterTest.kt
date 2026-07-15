package app.journal.ingest

import app.journal.model.Substance
import kotlin.test.*
import kotlinx.coroutines.runBlocking

class ManualImportAdapterTest {

    private class FakeIngestRepo : IngestRepository {
        val substances = mutableListOf<Substance>()
        var upsertedCount = 0
        override fun upsertSubstance(substance: Substance) {
            substances.removeAll { it.id == substance.id }
            substances.add(substance)
            upsertedCount++
        }
        override fun upsertInteraction(interaction: app.journal.model.Interaction) {}
        override fun upsertEffect(effect: app.journal.model.Effect) {}
    }

    @Test
    fun importsValidJsonArray() = runBlocking {
        val repo = FakeIngestRepo()
        val adapter = ManualImportAdapter(repo)
        val json = """[{"name":"LSD"},{"name":"MDMA"}]"""
        val report = adapter.importFromJson(json, "test")
        assertEquals(2, report.substancesUpserted)
        assertEquals(2, repo.substances.size)
        assertEquals("test:lsd", repo.substances[0].id)
        assertEquals("LSD", repo.substances[0].name)
        assertEquals("test:mdma", repo.substances[1].id)
    }

    @Test
    fun skipsEntriesWithoutName() = runBlocking {
        val repo = FakeIngestRepo()
        val adapter = ManualImportAdapter(repo)
        val json = """[{"name":"LSD"},{"unknown":"foo"},{"name":"MDMA"}]"""
        val report = adapter.importFromJson(json, "test")
        assertEquals(2, report.substancesUpserted)
        assertEquals(0, report.errors.size)
    }

    @Test
    fun handlesMalformedJson() = runBlocking {
        val repo = FakeIngestRepo()
        val adapter = ManualImportAdapter(repo)
        val json = """not json at all"""
        val report = adapter.importFromJson(json, "test")
        assertEquals(0, report.substancesUpserted)
        assertTrue(report.errors.isNotEmpty())
    }

    @Test
    fun handlesEmptyArray() = runBlocking {
        val repo = FakeIngestRepo()
        val adapter = ManualImportAdapter(repo)
        val json = """[]"""
        val report = adapter.importFromJson(json, "test")
        assertEquals(0, report.substancesUpserted)
        assertEquals(0, repo.substances.size)
    }

    @Test
    fun reportsPartialFailureOnInvalidElement() = runBlocking {
        val repo = FakeIngestRepo()
        val adapter = ManualImportAdapter(repo)
        val json = """[{"name":"LSD"},"not an object",{"name":"MDMA"}]"""
        val report = adapter.importFromJson(json, "test")
        assertEquals(2, report.substancesUpserted)
    }
}
