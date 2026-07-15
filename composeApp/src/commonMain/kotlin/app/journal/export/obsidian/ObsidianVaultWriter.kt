package app.journal.export.obsidian

/**
 * Platform-specific vault file operations.
 * Desktop actual uses java.io.File; Android actual uses SAF.
 */
expect object ObsidianVaultOps {
    /** Check that [dirPath] exists, is a directory, and is writable. */
    fun validateVaultPath(dirPath: String): Boolean

    /** List all .md files in [dirPath]. Returns absolute paths. */
    fun listMdFiles(dirPath: String): List<String>

    /** Read the full text of a file at [path]. */
    fun readFile(path: String): String

    /** Write [content] to [path], creating parent dirs if needed. */
    fun writeFile(path: String, content: String)

    /** Delete the file at [path]. Returns true if deleted. */
    fun deleteFile(path: String): Boolean

    /** Ensure [dirPath] exists (create if needed). Returns true if ready. */
    fun ensureDir(dirPath: String): Boolean

    /**
     * Normalize a file path, resolving relative segments ("..", ".").
     * On JVM this uses [java.io.File.normalize]; on other platforms it
     * provides equivalent string-based normalization.
     */
    fun normalizePath(path: String): String
}
