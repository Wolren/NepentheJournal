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
}
