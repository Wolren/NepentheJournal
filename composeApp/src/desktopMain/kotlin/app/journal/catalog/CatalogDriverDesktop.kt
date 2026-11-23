package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.journal.log.Log
import java.io.File

actual object CatalogDriver {

    actual val supported: Boolean = true

    actual fun open(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${resolvePath()}")

    /** Tests point nepenthe.catalog at the built file; the app extracts a versioned copy once. */
    private fun resolvePath(): String {
        System.getProperty("nepenthe.catalog")?.let { return it }
        val manifest = readResource("manifest.json")
        val version = CatalogSource.versionFrom(manifest) ?: "unversioned"
        val expectedSize = CatalogSource.sizeFrom(manifest)
        val dir = File(System.getProperty("java.io.tmpdir"), "nepenthe-catalog").apply { mkdirs() }
        val target = File(dir, "substances-$version.sqlite")
        if (!target.isUsable(expectedSize)) {
            if (target.isFile) {
                Log.withTag("Catalog").w { "Discarding catalog copy of ${target.length()} bytes, expected $expectedSize" }
            }
            // A kill mid-copy used to leave a short file that every later launch trusted.
            val tmp = File(dir, "${target.name}.tmp")
            tmp.delete()
            val stream = readResourceStream(CATALOG_FILE)
                ?: error("Bundled substance catalog is missing")
            tmp.outputStream().use { out -> stream.use { it.copyTo(out) } }
            check(expectedSize == null || tmp.length() == expectedSize) {
                "Bundled catalog is ${tmp.length()} bytes, manifest says $expectedSize"
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        pruneStaleCopies(dir, target.name)
        return target.absolutePath
    }

    /** A short copy from an interrupted launch must never be trusted; SQLite may only grow a good one. */
    private fun File.isUsable(expectedSize: Long?): Boolean =
        isFile && length() > 0L && (expectedSize == null || length() >= expectedSize)

    /** Each content version leaves a 16 MB copy behind, so keep only the one this build opens. */
    private fun pruneStaleCopies(dir: File, keep: String) {
        dir.listFiles()?.forEach { file ->
            // A copy also carries -wal, -shm and -journal siblings, so match the name prefix.
            val stale = file.name.startsWith("substances-") && !file.name.startsWith(keep)
            if (stale && file.isFile && file.delete()) {
                Log.withTag("Catalog").i { "Removed stale catalog copy ${file.name}" }
            }
        }
    }

    private fun readResource(name: String): String? =
        readResourceStream(name)?.use { it.readBytes().decodeToString() }

    private fun readResourceStream(name: String) =
        CatalogDriver::class.java.getResourceAsStream("/$name")

    private const val CATALOG_FILE = "substances.sqlite"
}
