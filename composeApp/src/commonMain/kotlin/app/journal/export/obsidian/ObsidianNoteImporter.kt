package app.journal.export.obsidian

import app.journal.data.AppJson
import app.journal.data.JournalRepository
import app.journal.model.*
import app.journal.util.currentTimeMillis

/**
 * Imports session notes from an Obsidian vault back into the journal.
 *
 * For each .md file in the vault subfolder:
 * 1. Check for the ```nepenthe canonical data block
 * 2. Parse the serialized ObsidianCanonicalBlock
 * 3. Match existing session by (startTime, title) — vault content wins
 * 4. Create or update session + children
 * 5. Notes without the fenced block are skipped (hand-written notes untouched)
 */
object ObsidianNoteImporter {

    private val json = AppJson.json

    /** Maximum number of child entities allowed per session in an import. */
    private const val MAX_DOSES_PER_SESSION = 500
    private const val MAX_NOTES_PER_SESSION = 200
    private const val MAX_TIMELINE_EVENTS_PER_SESSION = 500

    /** Earliest valid timestamp: 2000-01-01T00:00:00Z */
    private const val MIN_VALID_TIMESTAMP = 946684800000L

    /** Maximum allowed margin into the future (10 years from import time). */
    private const val TIMESTAMP_FUTURE_MARGIN_MS = 31536000000L * 10

    /**
     * Import all Obsidian notes from [vaultDir] into [repo].
     * Only processes files containing the ```nepenthe canonical block.
     *
     * @throws SecurityException if [vaultDir] contains path traversal components
     */
    fun importFromVault(
        repo: JournalRepository,
        vaultDir: String
    ): ObsidianImportResult {
        // H1: Reject ".." in vault directory path to prevent path traversal
        if (vaultDir.contains("..")) {
            return ObsidianImportResult(errors = listOf(
                "Vault directory path contains '..' — rejecting for security: $vaultDir"
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
                val matched = findMatchingSession(repo, session.startTime, session.title)

                if (matched != null) {
                    // Update existing session — vault version wins
                    val updatedSession = session.copy(
                        id = matched.id,
                        createdAt = matched.createdAt,
                        deviceOrigin = matched.deviceOrigin,
                        updatedAt = currentTimeMillis()
                    )
                    repo.upsertSession(updatedSession)

                    // Replace children: delete old, insert from vault
                    replaceChildren(repo, matched.id, block)
                    updated++
                } else {
                    // Create new session
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
     * Find an existing session matching (startTime, title).
     * Used instead of fragile internal IDs so the user can rename notes in Obsidian.
     */
    private fun findMatchingSession(
        repo: JournalRepository,
        startTime: Long,
        title: String
    ): Session? {
        return repo.sessions.value.find { s ->
            s.startTime == startTime && s.title == title
        }
    }

    /**
     * Replace all child entities (doses, notes, timeline events) for a session
     * with the ones from the canonical block.
     */
    private fun replaceChildren(
        repo: JournalRepository,
        sessionId: String,
        block: ObsidianCanonicalBlock
    ) {
        // Delete existing children, then insert from vault block
        repo.dosesForSession(sessionId).forEach { repo.deleteDose(it.id) }
        repo.notesForSession(sessionId).forEach { repo.deleteNote(it.id) }
        repo.eventsForSession(sessionId).forEach { repo.deleteTimelineEvent(it.id) }

        // Import doses from vault (with correct sessionId)
        for (dose in block.doses) {
            val importedDose = dose.copy(
                sessionId = sessionId,
                createdAt = if (dose.createdAt == 0L) currentTimeMillis() else dose.createdAt,
                updatedAt = currentTimeMillis(),
                deviceOrigin = "obsidian-import"
            )
            repo.upsertDose(importedDose)
        }

        // Import notes from vault
        for (note in block.notes) {
            val importedNote = note.copy(
                sessionId = sessionId,
                createdAt = if (note.createdAt == 0L) currentTimeMillis() else note.createdAt,
                updatedAt = currentTimeMillis(),
                deviceOrigin = "obsidian-import"
            )
            repo.upsertNote(importedNote)
        }

        // Import timeline events from vault
        for (event in block.timelineEvents) {
            val importedEvent = event.copy(
                sessionId = sessionId,
                createdAt = if (event.createdAt == 0L) currentTimeMillis() else event.createdAt,
                updatedAt = currentTimeMillis(),
                deviceOrigin = "obsidian-import"
            )
            repo.upsertTimelineEvent(importedEvent)
        }
    }

    /**
     * Extract the JSON content inside the first ```nepenthe ... ``` block.
     * Returns null if no such block exists (hand-written note — skip).
     * Handles both Unix (\n) and Windows (\r\n) line endings.
     */
    internal fun extractCanonicalBlock(text: String): String? {
        val startIdx = text.indexOf(BLOCK_OPEN)
        if (startIdx < 0) return null
        // Normalize line endings to \n
        val normalized = text.replace("\r\n", "\n")
        val contentStart = startIdx + BLOCK_OPEN.length
        val rest = normalized.substring(contentStart)
        val newlineIdx = rest.indexOf('\n')
        if (newlineIdx < 0) return null
        val afterNewline = rest.substring(newlineIdx).trimStart()
        val endIdx = afterNewline.indexOf("\n$BLOCK_CLOSE")
        if (endIdx < 0) return null
        return afterNewline.substring(0, endIdx)
    }

    // ---- Data validation ----

    /** Validate a parsed [ObsidianCanonicalBlock] before importing it. */
    private fun validateBlock(block: ObsidianCanonicalBlock): List<String> {
        val errors = mutableListOf<String>()
        val now = currentTimeMillis()
        val maxTimestamp = now + TIMESTAMP_FUTURE_MARGIN_MS
        val session = block.session

        // ID format
        if (!isValidId(session.id)) {
            errors.add("invalid session ID '${session.id}'")
        }

        // Timestamp bounds
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

        // Entity count limits
        if (block.doses.size > MAX_DOSES_PER_SESSION) {
            errors.add("too many doses: ${block.doses.size} > $MAX_DOSES_PER_SESSION")
        }
        if (block.notes.size > MAX_NOTES_PER_SESSION) {
            errors.add("too many notes: ${block.notes.size} > $MAX_NOTES_PER_SESSION")
        }
        if (block.timelineEvents.size > MAX_TIMELINE_EVENTS_PER_SESSION) {
            errors.add("too many timeline events: ${block.timelineEvents.size} > $MAX_TIMELINE_EVENTS_PER_SESSION")
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
