package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual object CatalogDriver {

    actual fun open(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${resolvePath()}")

    /** Tests point nepenthe.catalog at the built file; the app extracts a versioned copy once. */
    private fun resolvePath(): String {
        System.getProperty("nepenthe.catalog")?.let { return it }
        val version = CatalogSource.versionFrom(readResource("manifest.json")) ?: "unversioned"
        val dir = File(System.getProperty("java.io.tmpdir"), "nepenthe-catalog").apply { mkdirs() }
        val target = File(dir, "substances-$version.sqlite")
        if (!target.exists() || target.length() == 0L) {
            val stream = readResourceStream(CATALOG_FILE)
                ?: error("Bundled substance catalog is missing")
            target.outputStream().use { out -> stream.use { it.copyTo(out) } }
        }
        return target.absolutePath
    }

    private fun readResource(name: String): String? =
        readResourceStream(name)?.use { it.readBytes().decodeToString() }

    private fun readResourceStream(name: String) =
        CatalogDriver::class.java.getResourceAsStream("/$name")

    private const val CATALOG_FILE = "substances.sqlite"
}
