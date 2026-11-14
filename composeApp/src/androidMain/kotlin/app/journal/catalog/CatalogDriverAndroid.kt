package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.journal.NepentheApp
import app.journal.catalog.db.CatalogDatabase

actual object CatalogDriver {

    actual fun open(): SqlDriver {
        copyAssetOnce()
        return AndroidSqliteDriver(CatalogDatabase.Schema, NepentheApp.appContext, CATALOG_FILE)
    }

    /** The driver opens a database file, so the read-only asset is copied into the databases dir once. */
    private fun copyAssetOnce() {
        val context = NepentheApp.appContext
        val target = context.getDatabasePath(CATALOG_FILE)
        if (target.exists() && target.length() > 0L) return
        target.parentFile?.mkdirs()
        context.assets.open(CATALOG_FILE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private const val CATALOG_FILE = "substances.sqlite"
}
