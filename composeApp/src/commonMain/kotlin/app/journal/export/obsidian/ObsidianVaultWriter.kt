package app.journal.export.obsidian

/**
 * Platform-specific vault file operations.
 * Desktop uses java.io.File, iOS uses NSFileManager, and Android inherits the JVM file API.
 */
expect object ObsidianVaultOps {
    fun validateVaultPath(dirPath: String): Boolean

    /** List all .md files in [dirPath]. Returns absolute paths. */
    fun listMdFiles(dirPath: String): List<String>

    fun readFile(path: String): String

    fun writeFile(path: String, content: String)

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
