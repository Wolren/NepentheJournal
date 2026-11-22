package app.journal.export.obsidian

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Note
import app.journal.model.Session
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A full vault export walks every session. Reading the whole notes flow inside
 * that loop makes the export quadratic in notes; the session index exists for
 * exactly this lookup and must be used instead.
 */
class ObsidianExportIndexUseTest {

    private val dir = File(System.getProperty("java.io.tmpdir") ?: ".", "nepenthe-export-index-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private class CountingRepo(private val delegate: IJournalRepository) : IJournalRepository by delegate {
        var notesFlowReads = 0
        var notesForSessionCalls = 0

        override val notes: StateFlow<List<Note>>
            get() {
                notesFlowReads++
                return delegate.notes
            }

        override fun notesForSession(sessionId: String): List<Note> {
            notesForSessionCalls++
            return delegate.notesForSession(sessionId)
        }
    }

    private fun session(id: String) = Session(
        id = id, createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
        title = "Session $id", startTime = 1_720_800_000_000L,
    )

    @Test
    fun aFullExportUsesTheSessionNoteIndex() {
        val repo = JournalRepository()
        repo.upsertSession(session("s:1"))
        repo.upsertSession(session("s:2"))
        repo.upsertNote(
            Note(
                id = "n:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                sessionId = "s:1", body = "first note",
            )
        )
        repo.upsertDose(
            Dose(
                id = "d:1", createdAt = 1L, updatedAt = 1L, deviceOrigin = "test",
                sessionId = "s:1", substanceId = "sub:missing",
                routeOfAdministration = "Oral", amount = 10.0, unit = "mg", timestamp = 1_720_800_000_000L,
            )
        )
        val counting = CountingRepo(repo)
        val config = ObsidianExportConfig(vaultPath = dir.absolutePath, subfolder = "Notes")

        val result = ObsidianExportManager.exportAllSessions(counting, config)

        assertEquals(2, result.written, "errors: ${result.errors}")
        assertEquals(0, counting.notesFlowReads, "the export must not scan the whole notes flow per session")
        assertEquals(2, counting.notesForSessionCalls, "each session note set comes from the index")
        assertTrue(File(dir, "Notes").listFiles().orEmpty().any { it.name.endsWith(".md") })
    }
}
