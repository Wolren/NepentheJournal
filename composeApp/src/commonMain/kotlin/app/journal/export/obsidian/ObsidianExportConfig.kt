package app.journal.export.obsidian

/**
 * User preferences for Obsidian vault export.
 * Stored alongside other app preferences.
 */
data class ObsidianExportConfig(
    /** Absolute path to the Obsidian vault directory. */
    val vaultPath: String = "",
    /** Subfolder inside the vault where notes are written (e.g., "Nepenthe"). */
    val subfolder: String = "Nepenthe",
    /** Automatically export each session when it's saved. */
    val autoExport: Boolean = false,
    /** File organization: "flat" (all in one folder) or "date" (YYYY/MM subdirs). */
    val fileOrganization: String = "flat",
    /** Whether the vault path has been validated as writable. */
    val vaultValidated: Boolean = false
) {
    /** Resolve the full output directory path. */
    fun resolvedDir(): String {
        val base = vaultPath.trimEnd('/').trimEnd('\\')
        return if (subfolder.isBlank()) base else "$base/$subfolder"
    }

    companion object {
        val DEFAULT = ObsidianExportConfig()
    }
}

/**
 * Result of an Obsidian export operation.
 */
data class ObsidianExportResult(
    val written: Int,
    val errors: List<String> = emptyList(),
    val totalDurationMs: Long = 0
)

/**
 * Result of an Obsidian import operation.
 */
data class ObsidianImportResult(
    val created: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
)
