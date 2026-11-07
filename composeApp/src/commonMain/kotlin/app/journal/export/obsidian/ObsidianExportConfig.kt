package app.journal.export.obsidian

/**
 * User preferences for Obsidian vault export.
 * Stored alongside other app preferences.
 */
data class ObsidianExportConfig(
    val vaultPath: String = "",
    val subfolder: String = "Nepenthe",
    val autoExport: Boolean = false,
    val fileOrganization: String = "flat",
    val vaultValidated: Boolean = false
) {
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
