package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver

actual object CatalogDriver {
    actual fun open(): SqlDriver =
        throw UnsupportedOperationException("The Android catalog asset is not wired yet")
}
