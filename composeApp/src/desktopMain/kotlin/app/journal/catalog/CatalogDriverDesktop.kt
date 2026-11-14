package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

actual object CatalogDriver {

    actual fun open(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:${resolvePath()}")

    /** Tests point nepenthe.catalog at the built file; the app extracts its bundled copy once. */
    private fun resolvePath(): String {
        System.getProperty("nepenthe.catalog")?.let { return it }
        val target = File(File(System.getProperty("java.io.tmpdir"), "nepenthe-catalog").apply { mkdirs() },
            CATALOG_FILE)
        if (!target.exists() || target.length() == 0L) {
            val stream = CatalogDriver::class.java.getResourceAsStream("/$CATALOG_FILE")
                ?: error("Bundled substance catalog is missing")
            target.outputStream().use { out -> stream.use { it.copyTo(out) } }
        }
        return target.absolutePath
    }

    private const val CATALOG_FILE = "substances.sqlite"
}
