package app.journal.util

import app.journal.data.JournalRepository
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

actual object ZipExporter {

    actual fun exportAll(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val file = File(outputPath)
        file.parentFile?.mkdirs()

        return try {
            ZipOutputStream(FileOutputStream(file)).use { zos ->
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
                }
            }
            5
        } catch (_: Exception) { 0 }
    }

    actual fun exportSessionsZip(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val content = CsvExporter.exportSessionsCsv(repo, filter)
        val file = File(outputPath)
        file.parentFile?.mkdirs()

        return try {
            ZipOutputStream(FileOutputStream(file)).use { zos ->
                val entry = ZipEntry("sessions.csv").apply { time = System.currentTimeMillis() }
                zos.putNextEntry(entry)
                zos.write(content.encodeToByteArray())
                zos.closeEntry()
            }
            1
        } catch (_: Exception) { 0 }
    }
}
