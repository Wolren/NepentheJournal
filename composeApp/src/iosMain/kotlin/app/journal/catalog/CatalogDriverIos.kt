package app.journal.catalog

import app.cash.sqldelight.db.SqlDriver

actual object CatalogDriver {

    actual val supported: Boolean = false

    actual fun open(): SqlDriver =
        throw UnsupportedOperationException("The iOS catalog bundle copy is not wired yet")
}
