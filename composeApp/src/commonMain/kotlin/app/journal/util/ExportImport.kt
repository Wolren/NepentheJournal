package app.journal.util

import app.journal.data.IJournalRepository
import app.journal.data.JournalRepository
import app.journal.data.JournalSnapshot
import app.journal.log.Log
import app.journal.model.Dose
import app.journal.model.Session
import app.journal.serde.AppJson
import app.journal.sync.EntityTimePolicy
import kotlinx.serialization.Serializable
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

    /**
     * Earliest valid timestamp: 2000-01-01T00:00:00Z (rejects epoch junk).
     * Alias of the shared policy; never restate the literal here.
     */
    const val MIN_VALID_TIMESTAMP = EntityTimePolicy.MIN_ENTITY_TIMESTAMP

    /**
     * Maximum allowed margin into the future (1 day from import time).
     * Alias of the shared policy; never restate the literal here.
     */
    const val TIMESTAMP_FUTURE_MARGIN_MS = EntityTimePolicy.FUTURE_MARGIN_MS

    /** Format version written by [exportSessions] and required on import. */
    const val SESSION_EXPORT_VERSION = 1

    /** User-facing outcome of a session-bundle import attempt. */
    data class ImportResult(
        /** Number of sessions imported. Always 0 when [error] is non-null. */
        val count: Int,
        /** User-facing rejection reason, or null when the import succeeded. */
        val error: String? = null
    )

    /** User-facing outcome of a full-journal backup import attempt. */
    data class FullJournalImportResult(
        val sessions: Int = 0,
        val substances: Int = 0,
        val doses: Int = 0,
        val notes: Int = 0,
        val timelineEvents: Int = 0,
        /** User-facing rejection reason, or null when the import applied. */
        val error: String? = null
    )

    @Serializable
    data class SessionExportBundle(
        val version: Int = SESSION_EXPORT_VERSION,
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
     *
     * Format: a JournalSnapshot (backup format). Restore it with
     * [importFullJournal]; the session-only [importSessions] path expects a
     * [SessionExportBundle] and will reject it.
     */
    fun exportFullJournal(repo: IJournalRepository): String {
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
     *
     * Compatibility wrapper over [importSessionsDetailed]; callers that need
     * the rejection reason (version mismatch, parse failure, caps) call that.
     */
    fun importSessions(repo: IJournalRepository, content: String): Int =
        importSessionsDetailed(repo, content).count

    /**
     * [importSessions] with a clear result: every rejection path (size cap,
     * parse failure, version mismatch, session cap) returns a user-facing
     * error instead of a bare 0 that is indistinguishable from "0 valid".
     */
    fun importSessionsDetailed(repo: IJournalRepository, content: String): ImportResult {
        if (content.length > MAX_IMPORT_BYTES) {
            return ImportResult(0, "Import file too large (max 50 MB)")
        }
        val bundle = try {
            json.decodeFromString<SessionExportBundle>(content)
        } catch (e: Exception) {
            return ImportResult(0, "Not a valid session export file")
        }

        // Version gate (audit "Schema versioning"): never decode-and-apply a
        // bundle written in a format this build does not understand.
        if (bundle.version != SESSION_EXPORT_VERSION) {
            Log.withTag("ExportImport").w {
                "Rejecting session export: version ${bundle.version}, expected $SESSION_EXPORT_VERSION"
            }
            return ImportResult(
                0,
                "Unsupported export version ${bundle.version} (this app reads version $SESSION_EXPORT_VERSION)"
            )
        }

        if (bundle.sessions.size > MAX_IMPORT_SESSIONS) {
            return ImportResult(0, "Too many sessions in one file (max $MAX_IMPORT_SESSIONS)")
        }

        val now = currentTimeMillis()
        val maxTimestamp = now + TIMESTAMP_FUTURE_MARGIN_MS
        var imported = 0

        bundle.sessions.forEach { swd ->
            val validationErrors = validateSessionWithDoses(swd.session, swd.doses, maxTimestamp)
            if (validationErrors.isNotEmpty()) {
                // Skip invalid session: prevents data corruption
                return@forEach
            }

            repo.upsertSession(swd.session)
            swd.doses.forEach { dose ->
                val updatedDose = if (dose.sessionId != swd.session.id)
                    dose.copy(sessionId = swd.session.id) else dose
                repo.upsertDose(updatedDose)
            }
            imported++
        }

        return ImportResult(imported, null)
    }

    /**
     * Imports a full-journal backup (the JournalSnapshot written by
     * [exportFullJournal]) into the repository. This closes the export/import
     * asymmetry where "Export full journal" produced a file the only import
     * button could not read (it expected a [SessionExportBundle]).
     *
     * Validation mirrors [importSessions]: size cap, decode, entity caps, and
     * per-session/per-dose sanity checks (invalid records are dropped, not
     * applied). The snapshot's version is checked before anything is applied:
     * files newer than [JournalSnapshot.CURRENT_VERSION] are rejected with a
     * clear error. Sessions, doses, notes, events, the substance library,
     * tombstones, and preferences then apply through [AppJson.apply], the same
     * path a disk load uses (blind upsert by ID; entities absent from the
     * backup stay untouched).
     */
    fun importFullJournal(repo: IJournalRepository, content: String): FullJournalImportResult {
        if (content.length > MAX_IMPORT_BYTES) {
            return FullJournalImportResult(error = "Backup file too large (max 50 MB)")
        }
        val snapshot = try {
            json.decodeFromString<JournalSnapshot>(content)
        } catch (e: Exception) {
            return FullJournalImportResult(error = "Not a valid full-journal backup file")
        }
        if (snapshot.version > JournalSnapshot.CURRENT_VERSION) {
            Log.withTag("ExportImport").w {
                "Rejecting full-journal backup: version ${snapshot.version}, app supports ${JournalSnapshot.CURRENT_VERSION}"
            }
            return FullJournalImportResult(
                error = "Backup version ${snapshot.version} was written by a newer app " +
                    "(this app reads up to version ${JournalSnapshot.CURRENT_VERSION})"
            )
        }
        if (snapshot.sessions.size > MAX_IMPORT_SESSIONS) {
            return FullJournalImportResult(error = "Too many sessions in backup (max $MAX_IMPORT_SESSIONS)")
        }

        val maxTimestamp = currentTimeMillis() + TIMESTAMP_FUTURE_MARGIN_MS
        val dosesBySession = snapshot.doses.groupBy { it.sessionId }
        val validSessionIds = snapshot.sessions
            .filter { session -> validateSessionWithDoses(session, dosesBySession[session.id].orEmpty(), maxTimestamp).isEmpty() }
            .map { it.id }
            .toSet()
        val keptSessions = snapshot.sessions.filter { it.id in validSessionIds }
        val keptDoses = snapshot.doses.filter { dose ->
            dose.sessionId in validSessionIds && validateDose(dose, maxTimestamp).isEmpty()
        }
        val keptNotes = snapshot.notes
        val keptEvents = snapshot.timelineEvents

        AppJson.apply(
            repo,
            snapshot.copy(
                sessions = keptSessions,
                doses = keptDoses,
                notes = keptNotes,
                timelineEvents = keptEvents
            )
        )
        return FullJournalImportResult(
            sessions = keptSessions.size,
            substances = snapshot.substances.size,
            doses = keptDoses.size,
            notes = keptNotes.size,
            timelineEvents = keptEvents.size
        )
    }

    // ---- Shared validation (session bundle + full-journal backup) ----

    /**
     * All validation errors for one session plus its doses: empty list means
     * the session and every dose may be applied. Shared by [importSessionsDetailed]
     * and [importFullJournal] so both paths enforce the same rules.
     */
    private fun validateSessionWithDoses(session: Session, doses: List<Dose>, maxTimestamp: Long): List<String> {
        val errors = mutableListOf<String>()

        // Child count cap per session
        if (doses.size > MAX_IMPORT_DOSES_PER_SESSION) {
            errors.add("too many doses (max $MAX_IMPORT_DOSES_PER_SESSION)")
            return errors
        }

        // Validate session ID
        if (!isValidId(session.id) || session.id.length > MAX_IMPORT_ID_LEN) {
            errors.add("invalid session ID '${session.id}'")
        }

        // Validate field lengths
        if (session.title.length > MAX_IMPORT_TITLE_LEN) {
            errors.add("session title too long")
        }
        if ((session.set?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
            (session.setting?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
            (session.intention?.length ?: 0) > MAX_IMPORT_FIELD_LEN ||
            (session.outcome?.length ?: 0) > MAX_IMPORT_FIELD_LEN
        ) {
            errors.add("session text field too long")
        }
        if (session.rating != null && (session.rating < 1 || session.rating > 10)) {
            errors.add("invalid session rating ${session.rating}")
        }

        // Validate timestamps
        if (session.createdAt !in MIN_VALID_TIMESTAMP..maxTimestamp) {
            errors.add("createdAt ${session.createdAt} out of valid range")
        }
        if (session.updatedAt !in MIN_VALID_TIMESTAMP..maxTimestamp) {
            errors.add("updatedAt ${session.updatedAt} out of valid range")
        }
        if (session.startTime !in MIN_VALID_TIMESTAMP..maxTimestamp) {
            errors.add("startTime ${session.startTime} out of valid range")
        }
        if (session.endTime != null && session.endTime !in MIN_VALID_TIMESTAMP..maxTimestamp) {
            errors.add("endTime ${session.endTime} out of valid range")
        }

        // Validate dose IDs and amounts
        doses.forEachIndexed { idx, dose ->
            errors.addAll(validateDose(dose, maxTimestamp).map { "dose[$idx] $it" })
        }
        return errors
    }

    /** Validation errors for a single dose; empty list means the dose is importable. */
    private fun validateDose(dose: Dose, maxTimestamp: Long): List<String> {
        val errors = mutableListOf<String>()
        if (!isValidId(dose.id) || dose.id.length > MAX_IMPORT_ID_LEN) {
            errors.add("has invalid ID '${dose.id}'")
        }
        if (!isValidId(dose.sessionId) || !isValidId(dose.substanceId)) {
            errors.add("has invalid linkage")
        }
        if (dose.timestamp !in MIN_VALID_TIMESTAMP..maxTimestamp) {
            errors.add("timestamp ${dose.timestamp} out of valid range")
        }
        if (dose.routeOfAdministration.length > 50 || dose.unit.length > 20) {
            errors.add("has oversized route/unit")
        }
        if ((dose.notes?.length ?: 0) > MAX_IMPORT_FIELD_LEN) {
            errors.add("notes too long")
        }
        if (dose.amount.isNaN() || dose.amount.isInfinite()) {
            errors.add("amount is not finite: ${dose.amount}")
        } else if (dose.amount < 0.0 || dose.amount > 1_000_000.0) {
            errors.add("amount out of range: ${dose.amount}")
        }
        return errors
    }

    /** Check that an ID is non-blank and contains no path-traversal characters. */
    private fun isValidId(id: String): Boolean {
        return id.isNotBlank() && id.none { c ->
            c == '/' || c == '\\' || c == '.' || c == '\n' || c == '\r' || c == '\t'
        }
    }
}
