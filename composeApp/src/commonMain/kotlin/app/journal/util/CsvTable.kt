package app.journal.util

/**
 * Generic CSV table with headers and row mapping.
 * Eliminates repetitive per-entity CSV export functions.
 *
 * Usage:
 *   val sessionTable = CsvTable(sessionHeaders) { session: SessionDataRow -> listOf(...) }
 *   val csv = sessionTable.render(items)
 */
class CsvTable<T>(
    private val headers: List<String>,
    private val toRow: (T) -> List<String?>
) {
    /**
     * Render all [items] as a CSV string with header row.
     */
    fun render(items: List<T>): String = buildString {
        appendLine(csvLine(headers))
        for (item in items) appendLine(csvLine(toRow(item)))
    }

    companion object {
        /**
         * CSV field escaping per RFC 4180.
         * Fields containing commas, quotes, or newlines are quoted and inner quotes doubled.
         */
        fun escapeField(value: String?): String {
            val s = value ?: return ""
            return if (s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')) {
                "\"${s.replace("\"", "\"\"")}\""
            } else s
        }

        fun csvLine(fields: List<String?>): String =
            fields.joinToString(",") { escapeField(it) }
    }
}
