package app.journal.export

import app.journal.data.*
import app.journal.model.*
import app.journal.serde.AppJson
import app.journal.util.currentTimeMillis
import kotlinx.serialization.encodeToString
import kotlin.test.*

class ExportImportTest {

    private fun sampleSession(id: String) = Session(
        id = id, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
        title = "Session $id", startTime = 1_700_000_000_000L
    )

    private fun sampleDose(id: String, sessionId: String) = Dose(
        id = id, createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L, deviceOrigin = "test",
        sessionId = sessionId, substanceId = "sub:1",
        routeOfAdministration = "Oral", amount = 100.0, unit = "mg", timestamp = 1_700_000_000_000L
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
    fun exportImportMultipleSessionsWithDoses() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSubstance(Substance(
            id = "sub:2", name = "MDMA", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Empathogen"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo.upsertSession(sampleSession("s:1"))
        repo.upsertSession(sampleSession("s:2"))
        repo.upsertDose(sampleDose("d:1", "s:1").copy(substanceId = "sub:1", amount = 100.0))
        repo.upsertDose(sampleDose("d:2", "s:1").copy(substanceId = "sub:2", amount = 120.0))
        repo.upsertDose(sampleDose("d:3", "s:2").copy(substanceId = "sub:1", amount = 50.0))

        val json = ExportImport.exportSessions(repo)
        val repo2 = JournalRepository()
        repo2.upsertSubstance(Substance(
            id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        repo2.upsertSubstance(Substance(
            id = "sub:2", name = "MDMA", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Empathogen"),
            cachedAt = 0L, sourceVersion = "test"
        ))
        val count = ExportImport.importSessions(repo2, json)

        assertEquals(2, count)
        assertEquals(2, repo2.sessions.value.size)
        assertEquals(3, repo2.doses.value.size)
        assertEquals(2, repo2.dosesForSession("s:1").size)
        assertEquals(1, repo2.dosesForSession("s:2").size)
    }

    @Test
    fun exportEmptyReturnsValidJson() {
        val repo = JournalRepository()
        val json = ExportImport.exportSessions(repo)
        // Parse the payload (audit C7): substring matching never proved the document decodes or that the fields hold sensible values.
        val bundle = app.journal.serde.AppJson.json
            .decodeFromString<ExportImport.SessionExportBundle>(json)
        assertTrue(bundle.sessions.isEmpty(), "empty repo must export zero session bundles")
        assertTrue(bundle.exportedAt > 0L, "exportedAt must carry a real timestamp")
        assertEquals("Nepenthe Journal", bundle.source)
        assertTrue(bundle.version >= 1, "bundle version must be set, got ${bundle.version}")
    }

    @Test
    fun importOverwritesExistingSession() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        val json = ExportImport.exportSessions(repo)

        repo.upsertSession(sampleSession("s:1").copy(title = "Modified"))
        ExportImport.importSessions(repo, json)
        assertEquals("Session s:1", repo.sessions.value.first().title)
    }

    @Test
    fun importFixesDoseSessionIdMismatch() {
        val repo = JournalRepository()
        repo.upsertSession(sampleSession("s:1"))
        val bundleJson = """{
            "version": 1,
            "exportedAt": 1000,
            "sessions": [{"session": {"id":"s:1","docType":"session","createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test","title":"S1","startTime":1700000000000},"doses":[{"id":"d:1","docType":"dose","createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test","sessionId":"s:wrong","substanceId":"sub:1","routeOfAdministration":"Oral","amount":100.0,"unit":"mg","timestamp":1700000000000}]}],
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

    @Test
    fun importSkipsNegativeTimestamp() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"), cachedAt = 0L, sourceVersion = "test"))
        val bundleJson = """{"version":1,"exportedAt":1000,"sessions":[{"session":{"id":"s:1","docType":"session","createdAt":-1,"updatedAt":1000,"deviceOrigin":"test","title":"Bad","startTime":2000},"doses":[]}]}"""
        val count = ExportImport.importSessions(repo, bundleJson)
        assertEquals(0, count, "session with negative createdAt should be skipped")
    }

    @Test
    fun importSkipsFutureTimestamp() {
        val repo = JournalRepository()
        val farFuture = currentTimeMillis() + 31536000000L * 3  // 3 years: exceeds 2-year margin
        val bundleJson = """{"version":1,"exportedAt":1000,"sessions":[{"session":{"id":"s:2","docType":"session","createdAt":$farFuture,"updatedAt":1000,"deviceOrigin":"test","title":"Future","startTime":2000},"doses":[]}]}"""
        val count = ExportImport.importSessions(repo, bundleJson)
        assertEquals(0, count, "session with excessively future timestamp should be skipped")
    }

    @Test
    fun importSkipsNanDoseAmount() {
        val repo = JournalRepository()
        repo.upsertSubstance(Substance(id = "sub:1", name = "LSD", createdAt = 0L, updatedAt = 0L,
            deviceOrigin = "test", substanceClass = listOf("Classical Psychedelic"), cachedAt = 0L, sourceVersion = "test"))
        repo.upsertSession(sampleSession("s:1"))
        val bundleJson = """{"version":1,"exportedAt":1000,"sessions":[{"session":{"id":"s:1","docType":"session","createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test","title":"NaN Dose","startTime":1700000000000},"doses":[{"id":"d:1","docType":"dose","createdAt":1700000000000,"updatedAt":1700000000000,"deviceOrigin":"test","sessionId":"s:1","substanceId":"sub:1","routeOfAdministration":"Oral","amount":NaN,"unit":"mg","timestamp":1700000000000}]}]}"""
        val count = ExportImport.importSessions(repo, bundleJson)
        assertEquals(0, count, "session with NaN dose should be skipped")
    }

    @Test
    fun importSkipsInvalidIdWithPathTraversal() {
        val repo = JournalRepository()
        val bundleJson = """{"version":1,"exportedAt":1000,"sessions":[{"session":{"id":"../../etc/passwd","docType":"session","createdAt":1000,"updatedAt":1000,"deviceOrigin":"test","title":"Bad","startTime":2000},"doses":[]}]}"""
        val count = ExportImport.importSessions(repo, bundleJson)
        assertEquals(0, count, "session with path traversal ID should be skipped")
    }

    @Test
    fun fullJournalRoundtripKeepsReferenceAndUserSubstances() {
        val source = JournalRepository()
        source.upsertSubstance(Substance(
            id = "cid:5761", name = "LSD", createdAt = 1L, updatedAt = 1L,
            deviceOrigin = "system", cachedAt = 1L, sourceVersion = "catalog"
        ))
        source.upsertSubstance(Substance(
            id = "dw:mine", name = "My Blend", createdAt = 1L, updatedAt = 1L,
            deviceOrigin = "desktop", cachedAt = 1L, sourceVersion = "user"
        ))
        source.upsertSession(sampleSession("s:2"))
        source.upsertDose(sampleDose("d:2", "s:2").copy(substanceId = "dw:mine"))

        val json = ExportImport.exportFullJournal(source)
        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, json)

        assertNull(result.error)
        assertEquals(2, target.substances.value.size)
        assertEquals(1, target.sessions.value.size)
        assertEquals("My Blend", target.substances.value.first { it.id == "dw:mine" }.name)
    }

    @Test
    fun fullJournalImportDropsInvalidNotesAndEvents() {
        val ts = 1_700_000_000_000L
        val source = JournalRepository()
        source.upsertSession(sampleSession("s:ok"))
        source.upsertNote(Note(
            id = "n:ok", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
            sessionId = "s:ok", body = "fine",
        ))
        source.upsertTimelineEvent(TimelineEvent(
            id = "e:ok", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
            sessionId = "s:ok", timestamp = ts, eventType = TimelineEventType.ONSET, label = "fine",
        ))
        val snapshot = AppJson.json.decodeFromString<JournalSnapshot>(ExportImport.exportFullJournal(source))
        val corrupted = snapshot.copy(
            notes = snapshot.notes + Note(
                id = "n:bad", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                sessionId = "s:ok", body = "epoch junk",
            ) + Note(
                id = "n:orphan", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
                sessionId = "s:missing", body = "orphan",
            ),
            timelineEvents = snapshot.timelineEvents + TimelineEvent(
                id = "e:bad", createdAt = 0L, updatedAt = 0L, deviceOrigin = "test",
                sessionId = "s:ok", timestamp = 0L, eventType = TimelineEventType.ONSET, label = "epoch junk",
            ) + TimelineEvent(
                id = "e:orphan", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
                sessionId = "s:missing", timestamp = ts, eventType = TimelineEventType.ONSET, label = "orphan",
            ),
        )

        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, AppJson.json.encodeToString(corrupted))

        assertNull(result.error)
        assertEquals(1, result.sessions)
        assertEquals(1, result.notes, "an epoch note must be dropped")
        assertEquals(1, result.timelineEvents, "an epoch event must be dropped")
        assertEquals(listOf("n:ok"), target.notes.value.map { it.id })
        assertEquals(listOf("e:ok"), target.timelineEvents.value.map { it.id })
    }

    @Test
    fun fullJournalImportRejectsUnboundedReferenceTables() {
        val ts = 1_700_000_000_000L
        val base = AppJson.json.decodeFromString<JournalSnapshot>(
            ExportImport.exportFullJournal(JournalRepository())
        )

        val substances = List(ExportImport.MAX_IMPORT_SUBSTANCES + 1) { i ->
            Substance(
                id = "sub:$i", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
                name = "Substance $i", cachedAt = 0L, sourceVersion = "test",
            )
        }
        val substanceTarget = JournalRepository()
        val substanceResult = ExportImport.importFullJournal(
            substanceTarget,
            AppJson.json.encodeToString(base.copy(substances = substances)),
        )
        assertNotNull(substanceResult.error, "an oversized substance list must be refused")
        assertTrue(substanceTarget.substances.value.isEmpty(), "nothing may be applied")

        val interactions = List(ExportImport.MAX_IMPORT_INTERACTIONS + 1) { i ->
            Interaction(
                id = "i:$i", createdAt = ts, updatedAt = ts, deviceOrigin = "test",
                substanceAId = "sub:a", substanceBId = "sub:b", riskLevel = InteractionRisk.LOW,
            )
        }
        val interactionTarget = JournalRepository()
        val interactionResult = ExportImport.importFullJournal(
            interactionTarget,
            AppJson.json.encodeToString(base.copy(interactions = interactions)),
        )
        assertNotNull(interactionResult.error, "an oversized interaction list must be refused")
        assertTrue(interactionTarget.interactions.value.isEmpty(), "nothing may be applied")
    }

    @Test
    fun fullJournalImportAcceptsAUtf8Bom() {        val source = JournalRepository()
        source.upsertSession(sampleSession("s:bom"))
        val json = ExportImport.exportFullJournal(source)

        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, "\uFEFF$json")

        assertNull(result.error, "a UTF-8 BOM must not make a valid backup unreadable: ${result.error}")
        assertEquals(1, target.sessions.value.size)
    }

    @Test
    fun sessionBundleImportAcceptsAUtf8Bom() {
        val source = JournalRepository()
        source.upsertSession(sampleSession("s:bom2"))
        val json = ExportImport.exportSessions(source)

        val target = JournalRepository()
        val count = ExportImport.importSessions(target, "\uFEFF$json")

        assertEquals(1, count, "a UTF-8 BOM must not make a valid session bundle unreadable")
        assertEquals("s:bom2", target.sessions.value.first().id)
    }
}
