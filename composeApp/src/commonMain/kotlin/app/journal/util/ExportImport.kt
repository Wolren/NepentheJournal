package app.journal.util

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
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

    /** Refuse import payloads larger than 50 MB before decoding. */
    const val MAX_IMPORT_BYTES = 50L * 1024 * 1024

    /** Cap on sessions per import file so one file cannot exhaust memory. */
    const val MAX_IMPORT_SESSIONS = 5000

    /** Cap on doses attached to one session in an import file. */
    const val MAX_IMPORT_DOSES_PER_SESSION = 500

    /** Cap on free-text field lengths accepted from import files. */
    const val MAX_IMPORT_FIELD_LEN = 65536
    const val MAX_IMPORT_TITLE_LEN = 500
    const val MAX_IMPORT_ID_LEN = 128

    /** Earliest valid timestamp: 2000-01-01T00:00:00Z (rejects epoch junk). */
    const val MIN_VALID_TIMESTAMP = 946684800000L

    /** Maximum allowed margin into the future (1 day from import time). */
    const val TIMESTAMP_FUTURE_MARGIN_MS = 86400000L

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
        val bundles = repo.exportSessionBundles().map { bundle ->
            SessionWithDoses(
                session = bundle.session,
                doses = bundle.doses
            )
        }
        val export = SessionExportBundle(
            exportedAt = now,
            sessions = bundles
        )
        return json.encodeToString(export)
    }

    /**
     * Exports the full journal as a pretty-printed JSON string.
     * Includes all entities: sessions, substances, doses, notes, timeline events,
     * interactions, effects, custom units, and settings.
     */
    fun exportFullJournal(repo: JournalRepository): String {
        val snapshot = repo.fullSnapshot()
        return json.encodeToString(snapshot)
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
        if (content.length > MAX_IMPORT_BYTES) return 0
        val bundle = try {
            json.decodeFromString<SessionExportBundle>(content)
        } catch (e: Exception) {
            return 0
        }

        if (bundle.sessions.size > MAX_IMPORT_SESSIONS) return 0

        val now = currentTimeMillis()
        val maxTimestamp = now + TIMESTAMP_FUTURE_MARGIN_MS
        var imported = 0

        bundle.sessions.forEach { swd ->
            val session = swd.session
            val validationErrors = mutableListOf<String>()

            // Child count cap per session
            if (swd.doses.size > MAX_IMPORT_DOSES_PER_SESSION) {
                return@forEach
            }

            // Validate session ID
            if (!isValidId(session.id) || session.id.length > MAX_IMPORT_ID_LEN) {
                validationErrors.add("invalid session ID '${session.id}'")
            }

            // Validate field lengths
            if (session.title.length > MAX_IMPORT_TITLE_LEN) {
                validationErrors.add("session title too long")
            }
            if ((session.set?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
                (session.setting?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
                (session.intention?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
                (session.outcome?.length ?: 0) > MAX_IMPORT_FIELD_LEN
            ) {
                validationErrors.add("session text field too long")
            }
            if (session.rating != null && (session.rating < 1 || session.rating > 10)) {
                validationErrors.add("invalid session rating ${session.rating}")
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
                if (!isValidId(dose.id) || dose.id.length > MAX_IMPORT_ID_LEN) {
                    validationErrors.add("dose[$idx] has invalid ID '${dose.id}'")
                }
                if (!isValidId(dose.sessionId) || !isValidId(dose.substanceId)) {
                    validationErrors.add("dose[$idx] has invalid linkage")
                }
                if (dose.timestamp !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                    validationErrors.add("dose[$idx] timestamp ${dose.timestamp} out of valid range")
                }
                if (dose.routeOfAdministration.length > 50 || dose.unit.length > 20) {
                    validationErrors.add("dose[$idx] has oversized route/unit")
                }
                if ((dose.notes?.length ?: 0) > MAX_IMPORT_FIELD_LEN) {
                    validationErrors.add("dose[$idx] notes too long")
                }
                if (dose.amount.isNaN() || dose.amount.isInfinite()) {
                    validationErrors.add("dose[$idx] amount is not finite: ${dose.amount}")
                } else if (dose.amount < 0.0 || dose.amount > 1_000_000.0) {
                    validationErrors.add("dose[$idx] amount out of range: ${dose.amount}")
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
