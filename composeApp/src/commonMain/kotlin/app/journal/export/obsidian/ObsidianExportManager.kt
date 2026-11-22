package app.journal.export.obsidian

import app.journal.data.JournalRepository
import app.journal.data.IJournalRepository
import app.journal.util.currentTimeMillis
import app.journal.util.isDesktopPlatform
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
        repo: IJournalRepository,
        sessionId: String,
        config: ObsidianExportConfig
    ): String? {
        // The vault is a plain path a phone sandbox cannot reach; the settings card is desktop only.
        if (!isDesktopPlatform()) return null
        val session = repo.getSession(sessionId) ?: return null
        return writeSessionNote(repo, session, config)
    }

    /**
     * Export all sessions to the Obsidian vault.
     */
    fun exportAllSessions(
        repo: IJournalRepository,
        config: ObsidianExportConfig
    ): ObsidianExportResult {
        if (!isDesktopPlatform()) return ObsidianExportResult(
            written = 0,
            errors = listOf("Obsidian vault sync is desktop only"),
            totalDurationMs = 0
        )
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
        repo: IJournalRepository,
        config: ObsidianExportConfig
    ): ObsidianImportResult {
        if (!isDesktopPlatform()) {
            return ObsidianImportResult(errors = listOf("Obsidian vault sync is desktop only"))
        }
        val dir = config.resolvedDir()
        if (!ObsidianVaultOps.validateVaultPath(config.vaultPath)) {
            return ObsidianImportResult(errors = listOf("Vault path is not valid: ${config.vaultPath}"))
        }

        // A path prefix is not containment: /vault2 is not under /vault.
        val vaultNorm = ObsidianVaultOps.normalizePath(config.vaultPath)
        val dirNorm = ObsidianVaultOps.normalizePath(dir)
        if (!isInsideVault(config.vaultPath, dir)) {
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
        repo: IJournalRepository,
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

        // A path prefix is not containment: /vault2 is not under /vault.
        if (!isInsideVault(config.vaultPath, dir)) {
            throw Exception(
                "Resolved export directory is outside vault: $dir not under ${config.vaultPath}"
            )
        }

        ObsidianVaultOps.ensureDir(dir)
        val filePath = "$dir/${chooseNoteFileName(dir, note.fileName)}"
        ObsidianVaultOps.writeFile(filePath, note.content)
        return filePath
    }

    /**
     * The vault is the user's space: overwrite only a note that carries our
     * canonical block. A hand-written note that happens to own the name keeps
     * it, and the export lands on a numbered sibling instead.
     */
    private fun chooseNoteFileName(dir: String, fileName: String): String {
        val existing = runCatching { ObsidianVaultOps.listMdFiles(dir) }.getOrDefault(emptyList())
        return chooseNoteFileName(
            fileName = fileName,
            existingPaths = existing,
            readContent = { path -> runCatching { ObsidianVaultOps.readFile(path) }.getOrNull() },
        )
    }

    private fun isInsideVault(vaultPath: String, candidate: String): Boolean {
        val root = ObsidianVaultOps.normalizePath(vaultPath).trimEnd('/', '\\')
        val path = ObsidianVaultOps.normalizePath(candidate)
        return path == root || path.startsWith("$root/") || path.startsWith("$root\\")
    }
}

/**
 * Name choice for one exported note. [readContent] returns the current text of
 * an existing file, or null when it cannot be read. Names compare
 * case-insensitively because a case-only variant is the same file on Windows
 * and macOS. An own note keeps its existing spelling so it is not forked.
 */
internal fun chooseNoteFileName(
    fileName: String,
    existingPaths: List<String>,
    readContent: (String) -> String?,
): String {
    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')
    val target = existingPaths.firstOrNull { baseName(it).equals(fileName, ignoreCase = true) }
        ?: return fileName
    val isOurs = readContent(target)?.contains(BLOCK_OPEN) == true
    if (isOurs) return baseName(target)
    val takenLowercase = existingPaths.map { baseName(it).lowercase() }.toSet()
    val stem = fileName.removeSuffix(".md")
    for (suffix in 2..999) {
        val candidate = "$stem ($suffix).md"
        if (candidate.lowercase() !in takenLowercase) return candidate
    }
    throw IllegalStateException("No free note name beside $fileName")
}
