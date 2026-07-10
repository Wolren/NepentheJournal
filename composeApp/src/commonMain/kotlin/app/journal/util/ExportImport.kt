package app.journal.util

import app.journal.data.JournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Export/import manager for sessions and their doses.
 * Uses JSON format readable by any text editor.
 */
object ExportImport {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class SessionExportBundle(
        val version: Int = 1,
        val exportedAt: Long,
        val sessions: List<SessionWithDoses>,
        val source: String = "Nepenthe Journal"
    )

    @Serializable
    data class SessionWithDoses(
        val session: Session,
        val doses: List<Dose>
    )

    /**
     * Exports all sessions with their doses to a JSON string.
     */
    fun exportSessions(repo: JournalRepository): String {
        val now = currentTimeMillis()
        val bundles = repo.sessions.value.map { session ->
            SessionWithDoses(
                session = session,
                doses = repo.dosesForSession(session.id)
            )
        }
        val export = SessionExportBundle(
            exportedAt = now,
            sessions = bundles
        )
        return json.encodeToString(export)
    }

    /**
     * Imports sessions and doses from a JSON string into the repository.
     * Returns the number of sessions imported.
     * Sessions with existing IDs will be overwritten (updated).
     */
    fun importSessions(repo: JournalRepository, content: String): Int {
        val bundle = json.decodeFromString<SessionExportBundle>(content)
        bundle.sessions.forEach { swd ->
            repo.upsertSession(swd.session)
            swd.doses.forEach { dose ->
                val updatedDose = if (dose.sessionId != swd.session.id)
                    dose.copy(sessionId = swd.session.id) else dose
                repo.upsertDose(updatedDose)
            }
        }
        return bundle.sessions.size
    }
}
