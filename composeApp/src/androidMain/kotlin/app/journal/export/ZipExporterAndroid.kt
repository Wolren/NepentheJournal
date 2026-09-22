package app.journal.export

import android.net.Uri
import app.journal.NepentheApp
import app.journal.data.JournalRepository
import app.journal.log.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Typed failure for Android zip export.
 *
 * Thrown instead of returning 0 so callers can distinguish a failed export
 * (exception) from an empty one (a valid 0 or small count).
 */
class ZipExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Android actual for ZipExporter.
 *
 * Handles both filesystem paths (for internal use) and content:// URIs
 * (for user-picked save locations). Content URI support works by writing
 * the zip to a temp file first, then copying via ContentResolver.
 *
 * Every export throws [ZipExportException] on failure and never returns 0
 * to signal an error, so a 0 count always means an empty export.
 */
actual object ZipExporter {

    actual fun exportAll(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        return if (outputPath.startsWith("content://")) {
            exportAllContentUri(repo, outputPath, filter)
        } else {
            exportAllFile(repo, outputPath, filter)
        }
    }

    actual fun exportSessionsZip(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        return if (outputPath.startsWith("content://")) {
            exportSessionsContentUri(repo, outputPath, filter)
        } else {
            exportSessionsFile(repo, outputPath, filter)
        }
    }

    // ---- File-based export ----

    private fun exportAllFile(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        return try {
            buildAllZip(repo, filter, FileOutputStream(file))
            Log.withTag("ZipExporter").i { "Exported all data to $outputPath" }
            5
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export all data to $outputPath" }
            throw ZipExportException("Failed to export all data to $outputPath: ${e.message}", e)
        }
    }

    private fun exportSessionsFile(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val file = File(outputPath)
        file.parentFile?.mkdirs()
        return try {
            buildSessionsZip(repo, filter, FileOutputStream(file))
            Log.withTag("ZipExporter").i { "Exported sessions to $outputPath" }
            1
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export sessions to $outputPath" }
            throw ZipExportException("Failed to export sessions to $outputPath: ${e.message}", e)
        }
    }

    // ---- Content URI export (write to temp, copy to URI) ----

    private fun exportAllContentUri(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val tmpDir = File(NepentheApp.appContext.cacheDir, "zip_exports")
        tmpDir.mkdirs()
        val tmpFile = File(tmpDir, "nepenthe-export.zip")
        return try {
            val count = buildAllZip(repo, filter, FileOutputStream(tmpFile))
            copyToUri(tmpFile, outputPath)
            Log.withTag("ZipExporter").i { "Exported all data to $outputPath" }
            count
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export all data to $outputPath" }
            throw ZipExportException("Failed to export all data to $outputPath: ${e.message}", e)
        } finally {
            tmpFile.delete()
        }
    }

    private fun exportSessionsContentUri(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val tmpDir = File(NepentheApp.appContext.cacheDir, "zip_exports")
        tmpDir.mkdirs()
        val tmpFile = File(tmpDir, "sessions.zip")
        return try {
            val count = buildSessionsZip(repo, filter, FileOutputStream(tmpFile))
            copyToUri(tmpFile, outputPath)
            Log.withTag("ZipExporter").i { "Exported sessions to $outputPath" }
            count
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export sessions to $outputPath" }
            throw ZipExportException("Failed to export sessions to $outputPath: ${e.message}", e)
        } finally {
            tmpFile.delete()
        }
    }

    private fun copyToUri(source: File, uriString: String) {
        val uri = Uri.parse(uriString)
        val out = NepentheApp.appContext.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Cannot open output stream for export destination")
        out.use { os ->
            source.inputStream().use { `is` ->
                `is`.copyTo(os)
            }
        }
    }

    // ---- Shared zip building ----

    private fun buildAllZip(
        repo: JournalRepository,
        filter: CsvExporter.CsvExportFilter?,
        out: java.io.OutputStream
    ): Int {
        var count = 0
        ZipOutputStream(out).use { zos ->
            val entries = listOf(
                "sessions.csv" to CsvExporter.exportSessionsCsv(repo, filter),
                "doses.csv" to CsvExporter.exportDosesCsv(repo, filter),
                "substances.csv" to CsvExporter.exportSubstancesCsv(repo),
                "timeline_events.csv" to CsvExporter.exportTimelineEventsCsv(repo, filter),
                "notes.csv" to CsvExporter.exportNotesCsv(repo, filter)
            )
            for ((name, content) in entries) {
                val entry = ZipEntry(name).apply { time = System.currentTimeMillis() }
                zos.putNextEntry(entry)
                zos.write(content.encodeToByteArray())
                zos.closeEntry()
                count++
            }
        }
        return count
    }

    private fun buildSessionsZip(
        repo: JournalRepository,
        filter: CsvExporter.CsvExportFilter?,
        out: java.io.OutputStream
    ): Int {
        val content = CsvExporter.exportSessionsCsv(repo, filter)
        ZipOutputStream(out).use { zos ->
            val entry = ZipEntry("sessions.csv").apply { time = System.currentTimeMillis() }
            zos.putNextEntry(entry)
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        return 1
    }
}
