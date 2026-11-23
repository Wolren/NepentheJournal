package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.journal.NepentheApp
import app.journal.catalog.db.CatalogDatabase
import app.journal.log.Log
import java.io.File

actual object CatalogDriver {

    actual val supported: Boolean = true

    actual fun open(): SqlDriver {
        val name = copyAssetOnce()
        return AndroidSqliteDriver(CatalogDatabase.Schema, NepentheApp.appContext, name)
    }

    /** The driver opens a database file, so the read-only asset is copied in once per content version. */
    private fun copyAssetOnce(): String {
        val context = NepentheApp.appContext
        val manifest = runCatching {
            context.assets.open(MANIFEST_FILE).bufferedReader().readText()
        }.getOrNull()
        val version = CatalogSource.versionFrom(manifest) ?: "unversioned"
        val expectedSize = CatalogSource.sizeFrom(manifest)
        val name = "catalog-$version.sqlite"
        val target = context.getDatabasePath(name)
        if (target.isUsable(expectedSize)) {
            target.parentFile?.let { pruneStaleCopies(it, name) }
            return name
        }
        target.parentFile?.mkdirs()
        if (target.isFile) {
            Log.withTag("Catalog").w { "Discarding catalog copy of ${target.length()} bytes, expected $expectedSize" }
        }
        // A kill mid-copy used to leave a short file that every later launch trusted.
        val tmp = File(target.parentFile, "$name.tmp")
        tmp.delete()
        context.assets.open(CATALOG_FILE).use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        check(expectedSize == null || tmp.length() == expectedSize) {
            "Bundled catalog is ${tmp.length()} bytes, manifest says $expectedSize"
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target.parentFile?.let { pruneStaleCopies(it, name) }
        return name
    }

    /** A short copy from an interrupted launch must never be trusted; SQLite may only grow a good one. */
    private fun File.isUsable(expectedSize: Long?): Boolean =
        isFile && length() > 0L && (expectedSize == null || length() >= expectedSize)

    /** Each content version leaves a 16 MB copy behind, so keep only the one this build opens. */
    private fun pruneStaleCopies(dir: File, keep: String) {
        dir.listFiles()?.forEach { file ->
            // A copy also carries -wal, -shm and -journal siblings, so match the name prefix.
            val stale = file.name.startsWith("catalog-") && !file.name.startsWith(keep)
            if (stale && file.isFile && file.delete()) {
                Log.withTag("Catalog").i { "Removed stale catalog copy ${file.name}" }
            }
        }
    }

    private const val CATALOG_FILE = "substances.sqlite"
    private const val MANIFEST_FILE = "manifest.json"
}
