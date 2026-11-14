package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.journal.NepentheApp
import app.journal.catalog.db.CatalogDatabase

actual object CatalogDriver {

    actual val supported: Boolean = true

    actual fun open(): SqlDriver {
        val name = copyAssetOnce()
        return AndroidSqliteDriver(CatalogDatabase.Schema, NepentheApp.appContext, name)
    }

    /** The driver opens a database file, so the read-only asset is copied in once per content version. */
    private fun copyAssetOnce(): String {
        val context = NepentheApp.appContext
        val version = CatalogSource.versionFrom(
            runCatching { context.assets.open(MANIFEST_FILE).bufferedReader().readText() }.getOrNull(),
        ) ?: "unversioned"
        val name = "catalog-$version.sqlite"
        val target = context.getDatabasePath(name)
        if (target.exists() && target.length() > 0L) return name
        target.parentFile?.mkdirs()
        context.assets.open(CATALOG_FILE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return name
    }

    private const val CATALOG_FILE = "substances.sqlite"
    private const val MANIFEST_FILE = "manifest.json"
}
