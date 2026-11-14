package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.journal.catalog.db.CatalogDatabase
import app.journal.util.readBundledResource
import co.touchlab.sqliter.DatabaseConfiguration
import platform.Foundation.NSBundle
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

actual object CatalogDriver {

    actual val supported: Boolean = true

    actual fun open(): SqlDriver {
        val basePath = copyOnce()
        return NativeSqliteDriver(
            schema = CatalogDatabase.Schema,
            name = CATALOG_FILE,
            onConfiguration = { config ->
                config.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = basePath))
            },
        )
    }

    /** The driver opens a database file, so the read-only asset is copied once per content version. */
    private fun copyOnce(): String {
        val docs = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
            .firstOrNull() as? String ?: "."
        val version = CatalogSource.versionFrom(readBundledResource("/$MANIFEST_FILE")) ?: "unversioned"
        val directory = "$docs/.nepenthe/catalog/$version"
        val target = "$directory/$CATALOG_FILE"
        if (!fileManager.fileExistsAtPath(target)) {
            fileManager.createDirectoryAtPath(
                directory, withIntermediateDirectories = true, attributes = null, error = null,
            )
            val source = NSBundle.mainBundle.pathForResource("substances", ofType = "sqlite")
                ?: return directory
            fileManager.copyItemAtPath(source, toPath = target, error = null)
        }
        return directory
    }

    private val fileManager = NSFileManager.defaultManager
    private const val CATALOG_FILE = "substances.sqlite"
    private const val MANIFEST_FILE = "manifest.json"
}
