package app.journal.export.obsidian

import app.journal.serde.AppJson
import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.model.*
import app.journal.export.ExportImport
import app.journal.sync.SyncLimits
import app.journal.util.currentTimeMillis

/**
 * Imports session notes from an Obsidian vault back into the journal.
 *
 * For each .md file in the vault subfolder:
 * 1. Check for the ```nepenthe canonical data block
 * 2. Parse the serialized ObsidianCanonicalBlock
 * 3. Match existing session by (startTime, title): vault content wins
 * 4. Create or update session + children
 * 5. Notes without the fenced block are skipped (hand-written notes untouched)
 */
object ObsidianNoteImporter {

    private val json = AppJson.json

    private const val MAX_DOSES_PER_SESSION = 500
    private const val MAX_NOTES_PER_SESSION = 200
    private const val MAX_TIMELINE_EVENTS_PER_SESSION = 500

    private const val MIN_VALID_TIMESTAMP = ExportImport.MIN_VALID_TIMESTAMP

    private const val TIMESTAMP_FUTURE_MARGIN_MS = ExportImport.TIMESTAMP_FUTURE_MARGIN_MS

    /**
     * Import all Obsidian notes from [vaultDir] into [repo].
     * Only processes files containing the ```nepenthe canonical block.
     *
     * @throws SecurityException if [vaultDir] contains path traversal components
     */
    fun importFromVault(
        repo: IJournalRepository,
        vaultDir: String
    ): ObsidianImportResult {
        // H1: Reject ".." in vault directory path to prevent path traversal
        if (vaultDir.contains("..")) {
            return ObsidianImportResult(errors = listOf(
                "Vault directory path contains '..', rejecting for security: $vaultDir"
            ))
        }

        val mdFiles = ObsidianVaultOps.listMdFiles(vaultDir)
        var created = 0
        var updated = 0
        var skipped = 0
        val errors = mutableListOf<String>()

        for (filePath in mdFiles) {
            val text: String = try {
                ObsidianVaultOps.readFile(filePath)
            } catch (e: Exception) {
                errors.add("Cannot read $filePath: ${e.message}")
                continue
            }

            val canonicalJson = extractCanonicalBlock(text)
            if (canonicalJson == null) {
                skipped++
                continue
            }

            val block: ObsidianCanonicalBlock = try {
                json.decodeFromString(canonicalJson)
            } catch (e: Exception) {
                skipped++
                continue
            }

            // C5: Validate parsed block data before processing
            val validationErrors = validateBlock(block)
            if (validationErrors.isNotEmpty()) {
                errors.add("Import failed for $filePath: ${validationErrors.joinToString("; ")}")
                continue
            }

            try {
                val session = block.session
                val matched = findMatchingSession(repo, session)

                if (matched != null) {
                    val updatedSession = session.copy(
                        id = matched.id,
                        createdAt = matched.createdAt,
                        deviceOrigin = matched.deviceOrigin,
                        updatedAt = currentTimeMillis()
                    )
                    repo.upsertSession(updatedSession)

                    replaceChildren(repo, matched.id, block)
                    updated++
                } else {
                    val newSession = session.copy(
                        createdAt = currentTimeMillis(),
                        updatedAt = currentTimeMillis(),
                        deviceOrigin = "obsidian-import"
                    )
                    repo.upsertSession(newSession)
                    replaceChildren(repo, newSession.id, block)
                    created++
                }
            } catch (e: Exception) {
                errors.add("Import failed for $filePath: ${e.message}")
            }
        }

        return ObsidianImportResult(
            created = created,
            updated = updated,
            skipped = skipped,
            errors = errors
        )
    }

    /**
     * Find the session a block belongs to. The exported block carries the
     * session id, so an exact id wins even when another session shares the
     * same start time and title. The (startTime, title) match stays for notes
     * whose session was re-created with a new id.
     */
    private fun findMatchingSession(repo: IJournalRepository, session: Session): Session? {
        repo.getSession(session.id)?.let { return it }
        return repo.sessions.value.find { s ->
            s.startTime == session.startTime && s.title == session.title
        }
    }

    /**
     * Replace the session's children from the canonical block. Children whose
     * id the block still carries are rewritten in place: deleting and recreating
     * them would record a tombstone for a row that exists again one line later.
     */
    private fun replaceChildren(
        repo: IJournalRepository,
        sessionId: String,
        block: ObsidianCanonicalBlock
    ) {
        val doseIds = block.doses.map { it.id }.toSet()
        val noteIds = block.notes.map { it.id }.toSet()
        val eventIds = block.timelineEvents.map { it.id }.toSet()
        repo.dosesForSession(sessionId).filter { it.id !in doseIds }.forEach { repo.deleteDose(it.id) }
        repo.notesForSession(sessionId).filter { it.id !in noteIds }.forEach { repo.deleteNote(it.id) }
        repo.eventsForSession(sessionId).filter { it.id !in eventIds }.forEach { repo.deleteTimelineEvent(it.id) }

        val now = currentTimeMillis()
        repo.applyBatch(
            doses = block.doses.map { dose ->
                dose.copy(
                    sessionId = sessionId,
                    createdAt = if (dose.createdAt == 0L) now else dose.createdAt,
                    updatedAt = now,
                    deviceOrigin = "obsidian-import"
                )
            },
            notes = block.notes.map { note ->
                note.copy(
                    sessionId = sessionId,
                    createdAt = if (note.createdAt == 0L) now else note.createdAt,
                    updatedAt = now,
                    deviceOrigin = "obsidian-import"
                )
            },
            timelineEvents = block.timelineEvents.map { event ->
                event.copy(
                    sessionId = sessionId,
                    createdAt = if (event.createdAt == 0L) now else event.createdAt,
                    updatedAt = now,
                    deviceOrigin = "obsidian-import"
                )
            },
            lastWriterWins = false,
        )
    }

    /**
     * Extract the JSON content inside the first ```nepenthe ... ``` block.
     * Returns null if no such block exists (hand-written note: skip).
     * Handles both Unix (\n) and Windows (\r\n) line endings.
     */
    internal fun extractCanonicalBlock(text: String): String? {
        val normalized = text.replace("\r\n", "\n")
        val startIdx = normalized.indexOf(BLOCK_OPEN)
        if (startIdx < 0) return null
        val contentStart = startIdx + BLOCK_OPEN.length
        val rest = normalized.substring(contentStart)
        val newlineIdx = rest.indexOf('\n')
        if (newlineIdx < 0) return null
        val afterNewline = rest.substring(newlineIdx).trimStart()
        val endIdx = afterNewline.indexOf("\n$BLOCK_CLOSE")
        if (endIdx < 0) return null
        return afterNewline.substring(0, endIdx)
    }

    private fun validateBlock(block: ObsidianCanonicalBlock): List<String> {
        val errors = mutableListOf<String>()
        val now = currentTimeMillis()
        val maxTimestamp = now + TIMESTAMP_FUTURE_MARGIN_MS
        val session = block.session

        if (!isValidId(session.id)) {
            errors.add("invalid session ID '${session.id}'")
        }

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
        if (session.title.length > SyncLimits.MAX_TITLE_LEN) {
            errors.add("session title too long (${session.title.length})")
        }
        val fields = listOf(
            "set" to session.set,
            "setting" to session.setting,
            "intention" to session.intention,
            "outcome" to session.outcome,
            "notes" to session.notes,
            "freeform" to session.freeform,
        )
        fields.forEach { (name, value) ->
            if ((value?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) {
                errors.add("session $name too long (${value?.length})")
            }
        }
        if (session.rating != null &&
            (session.rating < SyncLimits.MIN_SESSION_RATING || session.rating > SyncLimits.MAX_SESSION_RATING)
        ) {
            errors.add("session rating ${session.rating} out of range")
        }

        if (block.doses.size > MAX_DOSES_PER_SESSION) {
            errors.add("too many doses: ${block.doses.size} > $MAX_DOSES_PER_SESSION")
        }
        if (block.notes.size > MAX_NOTES_PER_SESSION) {
            errors.add("too many notes: ${block.notes.size} > $MAX_NOTES_PER_SESSION")
        }
        if (block.timelineEvents.size > MAX_TIMELINE_EVENTS_PER_SESSION) {
            errors.add("too many timeline events: ${block.timelineEvents.size} > $MAX_TIMELINE_EVENTS_PER_SESSION")
        }

        // Hand-edited blocks get the same value checks as a synced row, before any write.
        block.doses.forEach { dose ->
            if (!isValidId(dose.id)) errors.add("invalid dose ID '${dose.id}'")
            if (!dose.amount.isFinite() || dose.amount < 0.0 || dose.amount > SyncLimits.MAX_DOSE_AMOUNT) {
                errors.add("dose ${dose.id} has an invalid amount ${dose.amount}")
            }
            if (dose.unit.length > SyncLimits.MAX_UNIT_LEN) errors.add("dose ${dose.id} unit too long")
            if (dose.routeOfAdministration.length > SyncLimits.MAX_ROA_LEN) errors.add("dose ${dose.id} ROA too long")
            if (dose.substanceId.isBlank() || dose.substanceId.length > SyncLimits.MAX_ID_LEN) {
                errors.add("dose ${dose.id} has an invalid substance id")
            }
            childTime("dose ${dose.id} createdAt", dose.createdAt, maxTimestamp)?.let(errors::add)
            childTime("dose ${dose.id} updatedAt", dose.updatedAt, maxTimestamp)?.let(errors::add)
            if (dose.timestamp !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                errors.add("dose ${dose.id} timestamp out of valid range")
            }
        }
        block.notes.forEach { note ->
            if (!isValidId(note.id)) errors.add("invalid note ID '${note.id}'")
            if (note.body.length > SyncLimits.MAX_FIELD_LEN) errors.add("note ${note.id} body too long")
            if ((note.title?.length ?: 0) > SyncLimits.MAX_TITLE_LEN) errors.add("note ${note.id} title too long")
            childTime("note ${note.id} createdAt", note.createdAt, maxTimestamp)?.let(errors::add)
            childTime("note ${note.id} updatedAt", note.updatedAt, maxTimestamp)?.let(errors::add)
        }
        block.timelineEvents.forEach { event ->
            if (!isValidId(event.id)) errors.add("invalid timeline event ID '${event.id}'")
            if (event.label.length > SyncLimits.MAX_LABEL_LEN) errors.add("event ${event.id} label too long")
            if ((event.body?.length ?: 0) > SyncLimits.MAX_FIELD_LEN) errors.add("event ${event.id} body too long")
            if (event.timestamp !in MIN_VALID_TIMESTAMP..maxTimestamp) {
                errors.add("event ${event.id} timestamp out of valid range")
            }
            childTime("event ${event.id} createdAt", event.createdAt, maxTimestamp)?.let(errors::add)
            childTime("event ${event.id} updatedAt", event.updatedAt, maxTimestamp)?.let(errors::add)
        }

        return errors
    }

    /** Child timestamps may be absent (0) because the import fills them with the import time. */
    private fun childTime(label: String, ts: Long, maxTimestamp: Long): String? =
        if (ts != 0L && ts !in MIN_VALID_TIMESTAMP..maxTimestamp) "$label out of valid range" else null

    private fun isValidId(id: String): Boolean {
        return id.isNotBlank() && id.none { c ->
            c == '/' || c == '\\' || c == '.' || c == '\n' || c == '\r' || c == '\t'
        }
    }
}
