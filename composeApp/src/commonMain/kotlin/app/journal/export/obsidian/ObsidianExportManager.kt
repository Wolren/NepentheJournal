package app.journal.export.obsidian

import app.journal.data.JournalRepository
import app.journal.util.currentTimeMillis
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Orchestrates Obsidian vault export and import operations.
 *
 * Uses [ObsidianNoteRenderer] for markdown generation,
 * [ObsidianVaultOps] for file I/O, and [ObsidianNoteImporter] for import.
 */
object ObsidianExportManager {

    /**
     * Export a single session to the Obsidian vault.
     * @return the file path written, or null on failure
     */
    fun exportSession(
        repo: JournalRepository,
        sessionId: String,
        config: ObsidianExportConfig
    ): String? {
        val session = repo.getSession(sessionId) ?: return null
        return writeSessionNote(repo, session, config)
    }

    /**
     * Export all sessions to the Obsidian vault.
     */
    fun exportAllSessions(
        repo: JournalRepository,
        config: ObsidianExportConfig
    ): ObsidianExportResult {
        val start = currentTimeMillis()
        val sessions = repo.sessions.value
        val dir = config.resolvedDir()
        ObsidianVaultOps.ensureDir(dir)

        var written = 0
        val errors = mutableListOf<String>()

        for (session in sessions) {
            try {
                val path = writeSessionNote(repo, session, config)
                if (path != null) written++ else errors.add("No output for session ${session.id}")
            } catch (e: Exception) {
                errors.add("Session ${session.id}: ${e.message}")
            }
        }

        return ObsidianExportResult(
            written = written,
            errors = errors,
            totalDurationMs = currentTimeMillis() - start
        )
    }

    /**
     * Import all vault notes back into the journal.
     */
    fun importFromVault(
        repo: JournalRepository,
        config: ObsidianExportConfig
    ): ObsidianImportResult {
        val dir = config.resolvedDir()
        if (!ObsidianVaultOps.validateVaultPath(config.vaultPath)) {
            return ObsidianImportResult(errors = listOf("Vault path is not valid: ${config.vaultPath}"))
        }

        // H1: Verify resolved directory stays within vault after normalization
        val vaultNorm = ObsidianVaultOps.normalizePath(config.vaultPath)
        val dirNorm = ObsidianVaultOps.normalizePath(dir)
        if (!dirNorm.startsWith(vaultNorm.trimEnd('/').trimEnd('\\'))) {
            return ObsidianImportResult(errors = listOf(
                "Resolved import directory is outside the vault: $dirNorm not under $vaultNorm"
            ))
        }

        ObsidianVaultOps.ensureDir(dir)
        return ObsidianNoteImporter.importFromVault(repo, dir)
    }

    /**
     * Render and write a single session note to the vault.
     * @return the absolute file path written, or null if rendering produced no output
     */
    private fun writeSessionNote(
        repo: JournalRepository,
        session: app.journal.model.Session,
        config: ObsidianExportConfig
    ): String? {
        val doses = repo.dosesForSession(session.id)
        val notes = repo.notes.value.filter { it.sessionId == session.id }
        val events = repo.eventsForSession(session.id)

        val substanceNameResolver: (String) -> String? = { substanceId ->
            repo.getSubstance(substanceId)?.name
        }

        val note = renderSessionToObsidianNote(
            session = session,
            doses = doses,
            notes = notes,
            timelineEvents = events,
            substanceNameResolver = substanceNameResolver
        )

        if (note.content.isBlank()) return null

        val baseDir = config.resolvedDir()
        val dir = if (config.fileOrganization == "date") {
            val dt = Instant.fromEpochMilliseconds(session.startTime)
                .toLocalDateTime(TimeZone.currentSystemDefault())
            val yearDir = "${dt.year}".padStart(4, '0')
            val monthDir = "${dt.monthNumber}".padStart(2, '0')
            "$baseDir/$yearDir/$monthDir"
        } else baseDir

        // H1: Normalize and verify resolved path stays within vault directory
        val vaultNorm = ObsidianVaultOps.normalizePath(config.vaultPath)
        val dirNorm = ObsidianVaultOps.normalizePath(dir)
        if (!dirNorm.startsWith(vaultNorm.trimEnd('/').trimEnd('\\'))) {
            throw Exception(
                "Resolved export directory is outside vault: $dirNorm not under $vaultNorm"
            )
        }

        ObsidianVaultOps.ensureDir(dir)
        val filePath = "$dir/${note.fileName}"
        ObsidianVaultOps.writeFile(filePath, note.content)
        return filePath
    }
}
