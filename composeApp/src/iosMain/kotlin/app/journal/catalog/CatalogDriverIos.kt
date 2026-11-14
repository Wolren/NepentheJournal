package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver

actual object CatalogDriver {

    actual val supported: Boolean = false

    actual fun open(): SqlDriver = throw UnsupportedOperationException(
        "iOS catalog driver not wired. On SQLDelight 2.4.1 this compiles: " +
            "NativeSqliteDriver(schema = CatalogDatabase.Schema, name = \"substances.sqlite\", " +
            "onConfiguration = { config -> config.copy(extendedConfig = " +
            "DatabaseConfiguration.Extended(basePath = copiedDirectory)) }). " +
            "Copy the bundled asset there once per manifest content_version first."
    )
}
