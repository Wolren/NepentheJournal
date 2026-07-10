package app.journal.util

import app.journal.data.JournalRepository

/**
 * Platform-specific zip bundler for CSV export.
 * JVM actual uses java.util.zip; iOS/Android would use platform APIs.
 */
expect object ZipExporter {
    /**
     * Exports all entity types as CSV files inside a zip archive.
     * @return number of files written to the zip, or 0 on failure.
     */
    fun exportAll(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter? = null
    ): Int

    /**
     * Exports only sessions as CSV in a single-file zip.
     * @return 1 on success, 0 on failure.
     */
    fun exportSessionsZip(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter? = null
    ): Int
}
