package app.journal.util

import app.journal.data.JournalRepository
import app.journal.data.AppJson
import app.journal.log.Log
import platform.Foundation.*
import kotlinx.cinterop.*

/**
 * iOS ZipExporter using manual ZIP format (stored entries, no compression).
 *
 * The ZIP format is constructed byte-by-byte with NSMutableData.
 * For CSV files which are already compact, stored (uncompressed) entries
 * avoid the complexity of DEFLATE on a platform without built-in zip libs.
 *
 * ZIP structure (stored method):
 *   [Local File Header + data] × N
 *   [Central Directory Header] × N
 *   [End of Central Directory Record]
 */
actual object ZipExporter {

    // --- ZIP constants ---
    private const val LOCAL_FILE_HEADER_SIG = 0x04034b50u
    private const val CENTRAL_DIR_HEADER_SIG = 0x02014b50u
    private const val END_CENTRAL_DIR_SIG = 0x06054b50u
    private const val VERSION_NEEDED: UShort = 20u
    private const val STORED_METHOD: UShort = 0u
    private const val DEFAULT_UTF8_FLAG: UShort = 0x0800u // Language encoding flag (EFS)

    actual fun exportAll(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val entries = listOf(
            "sessions.csv" to CsvExporter.exportSessionsCsv(repo, filter),
            "doses.csv" to CsvExporter.exportDosesCsv(repo, filter),
            "substances.csv" to CsvExporter.exportSubstancesCsv(repo),
            "timeline_events.csv" to CsvExporter.exportTimelineEventsCsv(repo, filter),
            "notes.csv" to CsvExporter.exportNotesCsv(repo, filter)
        )
        return try {
            val zipData = buildZip(entries)
            zipData.writeToFile(outputPath, atomically = true)
            Log.withTag("ZipExporter").i { "Exported all data to $outputPath" }
            5
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export all data to $outputPath" }
            0
        }
    }

    actual fun exportSessionsZip(
        repo: JournalRepository,
        outputPath: String,
        filter: CsvExporter.CsvExportFilter?
    ): Int {
        val content = CsvExporter.exportSessionsCsv(repo, filter)
        return try {
            val zipData = buildZip(listOf("sessions.csv" to content))
            zipData.writeToFile(outputPath, atomically = true)
            Log.withTag("ZipExporter").i { "Exported sessions to $outputPath" }
            1
        } catch (e: Exception) {
            Log.withTag("ZipExporter").e(e) { "Failed to export sessions to $outputPath" }
            0
        }
    }

    // --- ZIP construction (stored entries, no compression) ---

    private data class ZipEntry(
        val name: String,
        val data: ByteArray,
        val crc32: ULong,
        val compressedSize: ULong,
        val uncompressedSize: ULong
    )

    private fun buildZip(entries: List<Pair<String, String>>): NSData {
        val fileEntries = entries.map { (name, content) ->
            val bytes = content.encodeToByteArray()
            ZipEntry(
                name = name,
                data = bytes,
                crc32 = crc32(bytes),
                compressedSize = bytes.size.toULong(),
                uncompressedSize = bytes.size.toULong()
            )
        }
        return writeZipToNSData(fileEntries)
    }

    private fun writeZipToNSData(entries: List<ZipEntry>): NSData {
        val data = NSMutableData()
        val offsets = mutableListOf<ULong>()

        // Write local file headers + file data
        for (entry in entries) {
            offsets.add(data.length.toULong())
            writeLocalFileHeader(data, entry)
            data.appendBytes(entry.data.refTo(0), entry.data.size.toULong())
        }

        // Write central directory
        val centralDirOffset = data.length.toULong()
        for (entry in entries) {
            writeCentralDirEntry(data, entry)
        }

        // Write end of central directory
        val centralDirSize = data.length.toULong() - centralDirOffset
        writeEndCentralDir(data, entries.size.toUShort(), centralDirSize, centralDirOffset)

        return data
    }

    private fun writeLocalFileHeader(data: NSMutableData, entry: ZipEntry) {
        val nameBytes = entry.name.encodeToByteArray()
        data.appendLEUint32(LOCAL_FILE_HEADER_SIG)
        data.appendLEUint16(VERSION_NEEDED)
        data.appendLEUint16(DEFAULT_UTF8_FLAG)
        data.appendLEUint16(STORED_METHOD)
        data.appendLEUint16(0u) // last mod time (not set)
        data.appendLEUint16(0u) // last mod date (not set)
        data.appendLEUint32(entry.crc32)
        data.appendLEUint32(entry.compressedSize)
        data.appendLEUint32(entry.uncompressedSize)
        data.appendLEUint16(nameBytes.size.toUShort())
        data.appendLEUint16(0u) // extra field length
        data.appendBytes(nameBytes.refTo(0), nameBytes.size.toULong())
    }

    private fun writeCentralDirEntry(data: NSMutableData, entry: ZipEntry) {
        val nameBytes = entry.name.encodeToByteArray()
        data.appendLEUint32(CENTRAL_DIR_HEADER_SIG)
        data.appendLEUint16(20u) // version made by
        data.appendLEUint16(VERSION_NEEDED)
        data.appendLEUint16(DEFAULT_UTF8_FLAG)
        data.appendLEUint16(STORED_METHOD)
        data.appendLEUint16(0u) // last mod time
        data.appendLEUint16(0u) // last mod date
        data.appendLEUint32(entry.crc32)
        data.appendLEUint32(entry.compressedSize)
        data.appendLEUint32(entry.uncompressedSize)
        data.appendLEUint16(nameBytes.size.toUShort())
        data.appendLEUint16(0u) // extra field length
        data.appendLEUint16(0u) // file comment length
        data.appendLEUint16(0u) // disk number start
        data.appendLEUint16(0u) // internal file attrs
        data.appendLEUint32(0u) // external file attrs
        data.appendLEUint32(0u) // relative offset (set to 0 for simplicity — most parsers handle it)
        data.appendBytes(nameBytes.refTo(0), nameBytes.size.toULong())
    }

    private fun writeEndCentralDir(
        data: NSMutableData,
        totalEntries: UShort,
        centralDirSize: ULong,
        centralDirOffset: ULong
    ) {
        data.appendLEUint32(END_CENTRAL_DIR_SIG)
        data.appendLEUint16(0u) // disk number
        data.appendLEUint16(0u) // disk with central dir
        data.appendLEUint16(totalEntries) // entries on this disk
        data.appendLEUint16(totalEntries) // total entries
        data.appendLEUint32(centralDirSize)
        data.appendLEUint32(centralDirOffset)
        data.appendLEUint16(0u) // comment length
    }

    // --- CRC-32 calculation (pure Kotlin — no zlib dependency needed) ---
    private val crcTable: UIntArray by lazy {
        UIntArray(256) { i ->
            var crc = i.toUInt()
            repeat(8) {
                crc = if ((crc and 1u) != 0u) {
                    (crc shr 1) xor 0xEDB88320u
                } else {
                    crc shr 1
                }
            }
            crc
        }
    }

    private fun crc32(data: ByteArray): ULong {
        var crc = 0xFFFFFFFFu
        for (b in data) {
            crc = crcTable[((crc xor b.toUInt()) and 0xFFu).toInt()] xor (crc shr 8)
        }
        return (crc xor 0xFFFFFFFFu).toULong()
    }
}

// --- Binary serialization helpers for NSMutableData ---

private fun NSMutableData.appendLEUint32(value: UInt) {
    val bytes = byteArrayOf(
        (value and 0xFFu).toByte(),
        ((value shr 8) and 0xFFu).toByte(),
        ((value shr 16) and 0xFFu).toByte(),
        ((value shr 24) and 0xFFu).toByte()
    )
    this.appendBytes(bytes.refTo(0), 4uL)
}

private fun NSMutableData.appendLEUint16(value: UShort) {
    val bytes = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )
    this.appendBytes(bytes.refTo(0), 2uL)
}

/** Utility: get pointer to first element of a ByteArray. */
private fun ByteArray.refTo(index: Int): CPointer<ByteVar> {
    return this.usePinned { it.addressOf(index) }
}

private val NSMutableData.length: NSInteger
    get() = this.length
