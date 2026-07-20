package app.journal.util

import app.journal.data.IJournalRepository
import app.journal.model.Dose
import app.journal.model.Session
import kotlinx.serialization.Serializable
import app.journal.data.AppJson
import kotlinx.serialization.encodeToString

/**
 * Export/import manager for sessions and their doses.
 * Uses JSON format readable by any text editor.
 */
object ExportImport {

    private val json = AppJson.pretty

    /** Earliest valid timestamp: any non-negative value after epoch. */
    const val MIN_VALID_TIMESTAMP = 0L

    /** Maximum allowed margin into the future (2 years from import time). */
    const val TIMESTAMP_FUTURE_MARGIN_MS = 31536000000L * 2

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
    fun exportSessions(repo: IJournalRepository): String {
        val now = currentTimeMillis()
        val bundles = repo.exportSessionBundles().map { (session, doses) ->
            SessionWithDoses(
                session = session,
                doses = doses
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
     *
     * Invalid sessions (bad timestamps, bad IDs, non-finite dose amounts)
     * are silently skipped to prevent data corruption.
     */
    fun importSessions(repo: IJournalRepository, content: String): Int {
        val bundle = try {
            json.decodeFromString<SessionExportBundle>(content)
        } catch (e: Exception) {
            return 0
        }

        val now = currentTimeMillis()
        val maxTimestamp = now + TIMESTAMP_FUTURE_MARGIN_MS
        var imported = 0

        bundle.sessions.forEach { swd ->
            val session = swd.session
            val validationErrors = mutableListOf<String>()

            // Validate session ID
            if (!isValidId(session.id)) {
                validationErrors.add("invalid session ID '${session.id}'")
            }

            // Validate timestamps
            if (session.createdAt !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                validationErrors.add("createdAt ${session.createdAt} out of valid range")
            }
            if (session.updatedAt !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                validationErrors.add("updatedAt ${session.updatedAt} out of valid range")
            }
            if (session.startTime !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                validationErrors.add("startTime ${session.startTime} out of valid range")
            }
            if (session.endTime != null && session.endTime !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                validationErrors.add("endTime ${session.endTime} out of valid range")
            }

            // Validate dose IDs and amounts
            swd.doses.forEachIndexed { idx, dose ->
                if (!isValidId(dose.id)) {
                    validationErrors.add("dose[$idx] has invalid ID '${dose.id}'")
                }
                if (dose.amount.isNaN() || dose.amount.isInfinite()) {
                    validationErrors.add("dose[$idx] amount is not finite: ${dose.amount}")
                } else if (dose.amount < 0.0) {
                    validationErrors.add("dose[$idx] amount is negative: ${dose.amount}")
                }
            }

            if (validationErrors.isNotEmpty()) {
                // Skip invalid session — prevents data corruption
                return@forEach
            }

            repo.upsertSession(session)
            swd.doses.forEach { dose ->
                val updatedDose = if (dose.sessionId != session.id)
                    dose.copy(sessionId = session.id) else dose
                repo.upsertDose(updatedDose)
            }
            imported++
        }

        return imported
    }

    /** Check that an ID is non-blank and contains no path-traversal characters. */
    private fun isValidId(id: String): Boolean {
        return id.isNotBlank() && id.none { c ->
            c == '/' || c == '\\' || c == '.' || c == '\n' || c == '\r' || c == '\t'
        }
    }
}
