package app.journal.util

/**
 * Platform file picker for export and import.
 */
expect object FilePicker {
    suspend fun saveFile(
        defaultName: String,
        description: String,
        extensions: List<String>
    ): String?

    suspend fun openFile(
        description: String,
        extensions: List<String>
    ): String?

    /**
     * Lets the user pick a folder. Returns a plain filesystem path, or null
     * when cancelled or when the platform cannot resolve the pick to one
     * (some Android storage volumes only yield SAF URIs).
     */
    suspend fun openFolder(): String?
}
