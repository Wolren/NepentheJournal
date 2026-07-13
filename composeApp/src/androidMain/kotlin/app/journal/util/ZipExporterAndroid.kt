package app.journal.util

import android.net.Uri
import app.journal.NepentheApp
import app.journal.data.JournalRepository
import app.journal.log.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android actual for ZipExporter.
 *
 * Handles both filesystem paths (for internal use) and content:// URIs
 * (for user-picked save locations). Content URI support works by writing
 * the zip to a temp file first, then copying via ContentResolver.
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
            5
        } catch (_: Exception) { 0 }
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
            1
        } catch (_: Exception) { 0 }
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
            count
        } catch (_: Exception) { 0 } finally {
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
            count
        } catch (_: Exception) { 0 } finally {
            tmpFile.delete()
        }
    }

    private fun copyToUri(source: File, uriString: String) {
        val uri = Uri.parse(uriString)
        NepentheApp.appContext.contentResolver.openOutputStream(uri)?.use { os ->
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
