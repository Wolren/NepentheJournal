package app.journal.data

import app.journal.model.*
import app.journal.serde.AppJson
import app.journal.sync.EntityTimePolicy
import app.journal.util.ExportImport
import kotlinx.serialization.encodeToString
import kotlin.test.*

/**
 * Export/import contract tests: full-journal backup round-trip
 * (importFullJournal) and the SessionExportBundle version gate
 * (importSessionsDetailed), plus the EntityTimePolicy aliases.
 */
class FullJournalImportTest {

    private fun substance(id: String) = Substance(
        id = id, name = "Sub $id", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
        cachedAt = 1L, sourceVersion = "test"
    )

    private fun populatedRepo(): JournalRepository {
        val repo = JournalRepository()
        repo.upsertSubstance(substance("sub:1"))
        repo.upsertSession(Session(
            id = "s:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            deviceOrigin = "test", title = "Backup Session", startTime = 1_700_000_000_000L
        ))
        repo.upsertDose(Dose(
            id = "d:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            deviceOrigin = "test", sessionId = "s:1", substanceId = "sub:1",
            routeOfAdministration = "Oral", amount = 100.0, unit = "mg",
            timestamp = 1_700_000_000_000L
        ))
        repo.upsertNote(Note(
            id = "n:1", createdAt = 1_700_000_000_000L, updatedAt = 1_700_000_000_000L,
            deviceOrigin = "test", body = "hello", title = "memo"
        ))
        repo.setRatingScaleMode(RatingScaleMode.NUMERIC)
        repo.setObsidianAutoExport(true)
        return repo
    }

    @Test
    fun fullJournalBackupRoundTrips() {
        val source = populatedRepo()
        val backup = ExportImport.exportFullJournal(source)

        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, backup)

        assertNull(result.error, "round-trip backup must import cleanly: ${result.error}")
        assertEquals(1, result.sessions)
        assertEquals(1, result.substances)
        assertEquals(1, result.doses)
        assertEquals(1, result.notes)
        assertEquals(1, target.sessions.value.size)
        assertEquals("Backup Session", target.sessions.value.first().title)
        assertEquals(1, target.doses.value.size)
        assertEquals(1, target.notes.value.size)
        assertEquals(1, target.substances.value.size)
        // Preferences ride along through AppJson.apply
        assertEquals(RatingScaleMode.NUMERIC, target.ratingScaleMode.value)
        assertTrue(target.obsidianAutoExport.value)
    }

    @Test
    fun importingSessionBundleAsFullJournalIsRejectedWithClearError() {
        val source = populatedRepo()
        val sessionBundle = ExportImport.exportSessions(source)

        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, sessionBundle)

        assertNotNull(result.error, "a SessionExportBundle is not a full-journal backup")
        assertTrue(result.error!!.contains("backup", ignoreCase = true),
            "error should say what was expected: ${result.error}")
        assertTrue(target.sessions.value.isEmpty(), "nothing may be applied on rejection")
    }

    @Test
    fun newerFullJournalVersionIsRejected() {
        val source = populatedRepo()
        val snapshot = source.fullSnapshot()
        val futureBackup = AppJson.json.encodeToString(
            snapshot.copy(version = JournalSnapshot.CURRENT_VERSION + 900)
        )

        val target = JournalRepository()
        val result = ExportImport.importFullJournal(target, futureBackup)

        assertNotNull(result.error)
        assertTrue(result.error!!.contains("newer"), "error must explain the version gap: ${result.error}")
        assertTrue(target.sessions.value.isEmpty(), "a newer-version backup must not be applied")
    }

    @Test
    fun garbageFullJournalInputIsRejected() {
        val result = ExportImport.importFullJournal(JournalRepository(), "{definitely not json")
        assertNotNull(result.error)
    }

    @Test
    fun sessionBundleVersionMismatchIsRejectedWithClearError() {
        val source = populatedRepo()
        // exportSessions uses the pretty printer, so tolerate both spacing forms.
        val json = ExportImport.exportSessions(source)
            .replace("\"version\":1", "\"version\":99")
            .replace("\"version\": 1", "\"version\": 99")

        val target = JournalRepository()
        val detailed = ExportImport.importSessionsDetailed(target, json)

        assertEquals(0, detailed.count)
        assertNotNull(detailed.error)
        assertTrue(detailed.error!!.contains("version"), "error must mention the version: ${detailed.error}")
        assertTrue(target.sessions.value.isEmpty())

        // Compatibility wrapper keeps its Int contract: still 0, never a throw.
        assertEquals(0, ExportImport.importSessions(target, json))
    }

    @Test
    fun sessionBundleGarbageIsRejectedWithClearError() {
        val detailed = ExportImport.importSessionsDetailed(JournalRepository(), "not json at all")
        assertEquals(0, detailed.count)
        assertNotNull(detailed.error)
    }

    @Test
    fun currentSessionBundleStillImportsThroughDetailedApi() {
        val source = populatedRepo()
        val json = ExportImport.exportSessions(source)
        val target = JournalRepository()
        target.upsertSubstance(substance("sub:1"))

        val detailed = ExportImport.importSessionsDetailed(target, json)
        assertNull(detailed.error)
        assertEquals(1, detailed.count)
        assertEquals(1, target.sessions.value.size)
    }

    @Test
    fun timestampBoundsAliasEntityTimePolicy() {
        assertEquals(EntityTimePolicy.MIN_ENTITY_TIMESTAMP, ExportImport.MIN_VALID_TIMESTAMP)
        assertEquals(EntityTimePolicy.FUTURE_MARGIN_MS, ExportImport.TIMESTAMP_FUTURE_MARGIN_MS)
        // The literals the aliases must pin (contract section e).
        assertEquals(946684800000L, ExportImport.MIN_VALID_TIMESTAMP)
        assertEquals(86_400_000L, ExportImport.TIMESTAMP_FUTURE_MARGIN_MS)
    }

    @Test
    fun sessionExportVersionConstantMatchesBundleDefault() {
        assertEquals(1, ExportImport.SESSION_EXPORT_VERSION)
        assertEquals(ExportImport.SESSION_EXPORT_VERSION, ExportImport.SessionExportBundle(
            exportedAt = 0L, sessions = emptyList()
        ).version)
    }
}
