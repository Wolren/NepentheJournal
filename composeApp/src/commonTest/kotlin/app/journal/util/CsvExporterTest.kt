package app.journal.util

import app.journal.data.*
import app.journal.model.*
import kotlin.test.*

class CsvExporterTest {

    private fun sampleSubstance(id: String, name: String) = Substance(
        id = id, name = name, createdAt = 0L, updatedAt = 0L,
        deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
        cachedAt = 0L, sourceVersion = "test"
    )

    private fun sampleSession(id: String) = Session(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        title = "Session $id", startTime = 2000L
    )

    private fun sampleDose(id: String, sessionId: String, substanceId: String) = Dose(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        sessionId = sessionId, substanceId = substanceId,
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 2000L
    )

    // ---- Sessions CSV ----

    @Test
    fun exportSessionsCsvContainsHeaders() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportSessionsCsv(repo)

        assertTrue(csv.startsWith("id,title,date,start_time,end_time,duration_hours,tags,set,setting,intention,outcome,rating,shulgin_rating,consumer,is_favorite,is_archived,substances,dose_count"))
        assertTrue(csv.contains("\n"))
    }

    @Test
    fun exportSessionsCsvIncludesSessionData() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))

        val csv = CsvExporter.exportSessionsCsv(repo)

        // Header present
        assertTrue(csv.startsWith("id,title,date,"))

        // Session row present
        assertTrue(csv.contains("s:1"))
        assertTrue(csv.contains("Session s:1"))
        // Substance name from dose should appear
        assertTrue(csv.contains("LSD"))
        // dose_count should be 1
        assertTrue(csv.contains(",1"))
    }

    @Test
    fun exportSessionsCsvEmptyRepo() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportSessionsCsv(repo)
        val lines = csv.trimEnd().split("\n")

        assertEquals(1, lines.size, "Only header row expected for empty repo")
        assertEquals(
            "id,title,date,start_time,end_time,duration_hours,tags,set,setting,intention,outcome,rating,shulgin_rating,consumer,is_favorite,is_archived,substances,dose_count",
            lines[0]
        )
    }

    @Test
    fun exportSessionsCsvWithFilterBySubstance() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))
        repo.upsertDose(sampleDose("d:2", "s:2", "sub:2"))

        val csv = CsvExporter.exportSessionsCsv(
            repo,
            CsvExporter.CsvExportFilter(substanceNames = listOf("LSD"))
        )
        assertTrue(csv.contains("s:1"), "Session s:1 (LSD) should be included")
        assertFalse(csv.contains("s:2"), "Session s:2 (MDMA) should be filtered out")
    }

    // ---- Doses CSV ----

    @Test
    fun exportDosesCsvContainsHeaders() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportDosesCsv(repo)

        assertTrue(csv.startsWith("id,session_id,substance_id,substance_name,route,amount,unit,timestamp_ms,is_redose,is_estimate,notes"))
    }

    @Test
    fun exportDosesCsvIncludesDoseData() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))

        val csv = CsvExporter.exportDosesCsv(repo)

        assertTrue(csv.contains("d:1"))
        assertTrue(csv.contains("s:1"))
        assertTrue(csv.contains("sub:1"))
        assertTrue(csv.contains("LSD"))
        assertTrue(csv.contains("Oral"))
        assertTrue(csv.contains("100.0"))
        assertTrue(csv.contains("mg"))
    }

    @Test
    fun exportDosesCsvEmptyRepo() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportDosesCsv(repo)
        val lines = csv.trimEnd().split("\n")

        assertEquals(1, lines.size, "Only header row expected for empty repo")
    }

    @Test
    fun exportDosesCsvWithFilterExcludesNonMatchingSession() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))
        repo.upsertDose(sampleDose("d:2", "s:2", "sub:2"))

        val csv = CsvExporter.exportDosesCsv(
            repo,
            CsvExporter.CsvExportFilter(substanceNames = listOf("LSD"))
        )
        assertTrue(csv.contains("d:1"), "Dose d:1 (LSD session) should be included")
        assertFalse(csv.contains("d:2"), "Dose d:2 (MDMA session) should be filtered out")
    }

    // ---- Substances CSV ----

    @Test
    fun exportSubstancesCsvContainsHeaders() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportSubstancesCsv(repo)

        assertTrue(csv.startsWith("id,name,aliases,class,cid,molecular_formula,molecular_weight,iupac_name,log_p,routes,effects,toxicity,addiction_potential"))
    }

    @Test
    fun exportSubstancesCsvIncludesSubstanceData() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))

        val csv = CsvExporter.exportSubstancesCsv(repo)

        assertTrue(csv.contains("sub:1"))
        assertTrue(csv.contains("LSD"))
    }

    @Test
    fun exportSubstancesCsvEmptyRepo() {
        val repo = JournalRepository()
        val csv = CsvExporter.exportSubstancesCsv(repo)
        val lines = csv.trimEnd().split("\n")
        assertEquals(1, lines.size, "Only header row expected for empty repo")
    }

    @Test
    fun exportSubstancesCsvMultipleSubstances() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSubstance(sampleSubstance("sub:2", "MDMA"))

        val csv = CsvExporter.exportSubstancesCsv(repo)
        val lines = csv.trimEnd().split("\n")

        // Header + 2 data rows
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("LSD"))
        assertTrue(lines[2].contains("MDMA"))
    }

    // ---- CSV formatting ----

    @Test
    fun csvEscapesCommasProperly() {
        val repo = JournalRepository()
        repo.upsertSubstance(sampleSubstance("sub:1", "LSD"))
        repo.upsertSession(
            sampleSession("s:1").copy(
                tags = listOf("tag,with,commas", "another")
            )
        )
        repo.upsertDose(sampleDose("d:1", "s:1", "sub:1"))

        val csv = CsvExporter.exportSessionsCsv(repo)
        // Tags should be quoted: "tag,with,commas;another"
        assertTrue(csv.contains("\"tag,with,commas;another\""),
            "Tags with commas should be RFC 4180 quoted")
    }

    @Test
    fun csvEscapesQuotesInFields() {
        val escaped = CsvTable.escapeField("contains \"quotes\" here")
        assertEquals("\"contains \"\"quotes\"\" here\"", escaped)
    }

    @Test
    fun csvEscapesNullAsEmpty() {
        assertEquals("", CsvTable.escapeField(null))
    }

    @Test
    fun csvEscapesCommaField() {
        val escaped = CsvTable.escapeField("a,b")
        assertEquals("\"a,b\"", escaped)
    }

    @Test
    fun csvEscapesNewlineField() {
        val escaped = CsvTable.escapeField("line1\nline2")
        assertEquals("\"line1\nline2\"", escaped)
    }

    @Test
    fun csvLineJoinsWithCommas() {
        val result = CsvTable.csvLine(listOf("a", "b", "c"))
        assertEquals("a,b,c", result)
    }
}
