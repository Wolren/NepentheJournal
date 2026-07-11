package app.journal.util

import app.journal.data.*
import app.journal.model.*
import kotlin.test.*

class ExportImportTest {

    private fun sampleSession(id: String) = Session(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        title = "Session $id", startTime = 2000L
    )

    private fun sampleDose(id: String, sessionId: String) = Dose(
        id = id, createdAt = 1000L, updatedAt = 1000L, deviceOrigin = "test",
        sessionId = sessionId, substanceId = "sub:1",
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 2000L
    )

    @Test
    fun exportImportRoundtrip() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertDose(sampleDose("d:1", "s:1"))

        val json = ExportImport.exportSessions(repo)
        assertTrue(json.contains("s:1"))
        assertTrue(json.contains("Nepenthe Journal"))

        // Import into a fresh repo
        val repo2 = JournalRepository()
        repo2.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        val count = ExportImport.importSessions(repo2, json)
        assertEquals(1, count)
        assertEquals("Session s:1", repo2.sessions.value.first().title)
        assertEquals(1, repo2.doses.value.size)
    }

    @Test
    fun exportEmptyReturnsValidJson() {
        val repo = JournalRepository()
        val json = ExportImport.exportSessions(repo)
        assertTrue(json.contains("sessions"))
        assertTrue(json.contains("exportedAt"))
    }

    @Test
    fun importOverwritesExistingSession() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        val json = ExportImport.exportSessions(repo)

        // Modify, then re-import
        repo.upsertSession(sampleSession("s:1").copy(title = "Modified"))
        ExportImport.importSessions(repo, json)
        assertEquals("Session s:1", repo.sessions.value.first().title)
    }

    @Test
    fun importFixesDoseSessionIdMismatch() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        // Dose references a session that doesn't exist yet — import should fix via copy(sessionId = ...)
        val bundleJson = """{
            "version": 1,
            "exportedAt": 1000,
            "sessions": [{"session": {"id":"s:1","docType":"session","createdAt":1000,"updatedAt":1000,"deviceOrigin":"test","title":"S1","startTime":2000},"doses":[{"id":"d:1","docType":"dose","createdAt":1000,"updatedAt":1000,"deviceOrigin":"test","sessionId":"s:wrong","substanceId":"sub:1","routeOfAdministration":"Oral","amount":100.0,"unit":"mg","timestamp":2000}]}],
            "source": "Nepenthe Journal"
        }"""
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        ExportImport.importSessions(repo, bundleJson)
        assertEquals("s:1", repo.doses.value.first().sessionId)
    }
}
