package app.journal.ingest.foreign

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * CSV, TSV or semicolon table with a header row: the catch-all path for any app
 * that exports doses but no journal JSON. Column names are matched case-blind.
 */
internal object SpreadsheetParser {

    /** Null when the header carries no substance or time column, so the file is not a dose table. */
    fun parse(content: String, tz: TimeZone): ForeignJournal? {
        val rows = splitRows(content)
        val headerIndex = rows.indexOfFirst { row -> row.any { it.isNotBlank() } }
        if (headerIndex < 0) return null
        val cols = columns(rows[headerIndex])
        if (cols.substance < 0 || cols.time < 0) return null
        val groups = LinkedHashMap<String, MutableList<ForeignDose>>()
        val titles = mutableMapOf<String, String?>()

        for (row in rows.drop(headerIndex + 1)) {
            if (row.all { it.isBlank() }) continue
            val dose = dose(row, cols, tz) ?: continue
            val sessionCell = cols.session.takeIf { it >= 0 }?.let { row.getOrNull(it)?.trim() }
            val key = sessionCell?.takeIf { it.isNotBlank() } ?: dayKey(dose.timestamp, tz)
            if (key !in groups) {
                titles[key] = titleFor(sessionCell)
                groups[key] = mutableListOf()
            }
            groups[key]?.add(dose)
            if (groups.size > MAX_GROUPS) break
        }

        val experiences = groups.map { (key, doses) ->
            ForeignExperience(
                title = titles[key],
                doses = doses.sortedBy { it.timestamp }.take(MAX_DOSES_PER_GROUP),
            )
        }.filter { it.doses.isNotEmpty() }
            .sortedBy { it.doses.first().timestamp }
        return ForeignJournal(ForeignFormat.SPREADSHEET, experiences)
    }

    private fun dose(row: List<String>, cols: Columns, tz: TimeZone): ForeignDose? {
        val substance = row.getOrNull(cols.substance)?.trim()?.ifBlank { null } ?: return null
        val (amount, unitFromAmount) = parseAmount(row.getOrNull(cols.amount)) ?: return null
        if (amount <= 0.0) return null
        val timestamp = parseTimestamp(row.getOrNull(cols.time), tz) ?: return null
        val unit = row.getOrNull(cols.unit)?.trim()?.ifBlank { null } ?: unitFromAmount
        return ForeignDose(
            substance = substance,
            amount = amount,
            unit = RouteNames.unit(unit),
            route = RouteNames.normalize(row.getOrNull(cols.route)),
            timestamp = timestamp,
            notes = row.getOrNull(cols.notes)?.trim()?.ifBlank { null },
            isEstimate = flag(row.getOrNull(cols.estimate)),
        )
    }

    /** "100 mg" -> (100.0, "mg"); "3,6 g" -> (3.6, "g"); "25" -> (25.0, null). */
    private fun parseAmount(raw: String?): Pair<Double, String?>? {
        val text = raw?.trim()?.replace(',', '.')?.ifEmpty { null } ?: return null
        text.toDoubleOrNull()?.let { return it to null }
        val match = AMOUNT.find(text) ?: return null
        val amount = match.groupValues[1].toDoubleOrNull() ?: return null
        return amount to match.groupValues[2].ifBlank { null }
    }

    private fun flag(raw: String?): Boolean =
        raw?.trim()?.lowercase() in setOf("1", "true", "yes", "y")

    /** An opaque group id ("s:1", "session-42") groups rows but is not a session title. */
    private fun titleFor(cell: String?): String? {
        val value = cell?.trim()?.ifBlank { null } ?: return null
        return value.takeIf { !ID_LIKE.matches(it) }
    }

    private fun dayKey(timestamp: Long, tz: TimeZone): String {
        val local = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(tz)
        return local.year.toString().padStart(4, '0') + "-" +
            (local.month.ordinal + 1).toString().padStart(2, '0') + "-" +
            local.dayOfMonth.toString().padStart(2, '0')
    }

    private fun columns(header: List<String>): Columns {
        val names = header.map(::normalizeHeader)
        fun find(aliases: Set<String>) = names.indexOfFirst { it in aliases }
        return Columns(
            substance = find(SUBSTANCE),
            amount = find(AMOUNT_COLUMN),
            unit = find(UNIT),
            route = find(ROUTE),
            time = find(TIME),
            notes = find(NOTES),
            session = find(SESSION),
            estimate = find(ESTIMATE),
        )
    }

    private fun normalizeHeader(raw: String): String =
        raw.trim().lowercase()
            .replace(Regex("[_\\-.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun delimiter(content: String): Char {
        val header = content.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return when {
            header.contains('\t') -> '\t'
            header.count { it == ';' } > header.count { it == ',' } -> ';'
            else -> ','
        }
    }

    private fun splitRows(content: String): List<List<String>> {
        val text = content.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val delimiter = delimiter(text)
        val rows = mutableListOf<List<String>>()
        val field = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                quoted && ch == '"' && text.getOrNull(index + 1) == '"' -> {
                    field.append('"'); index++
                }
                ch == '"' -> quoted = !quoted
                !quoted && ch == delimiter -> {
                    row.add(field.toString()); field.clear()
                }
                !quoted && ch == '\n' -> {
                    row.add(field.toString()); field.clear()
                    rows.add(row); row = mutableListOf()
                }
                else -> field.append(ch)
            }
            index++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }

    private data class Columns(
        val substance: Int,
        val amount: Int,
        val unit: Int,
        val route: Int,
        val time: Int,
        val notes: Int,
        val session: Int,
        val estimate: Int,
    )

    private const val MAX_GROUPS = 5000
    private const val MAX_DOSES_PER_GROUP = 500

    private val AMOUNT = Regex("^([0-9]+(?:[.,][0-9]+)?)\\s*([^0-9\\s].*)$")

    private val ID_LIKE = Regex("^[A-Za-z]{1,10}[:-][A-Za-z0-9:._-]+$")

    private val SUBSTANCE = setOf("substance", "substance name", "name", "drug", "compound", "chemical")
    private val AMOUNT_COLUMN = setOf("dose", "dosage", "amount", "quantity", "dose amount")
    private val UNIT = setOf("unit", "units")
    private val ROUTE = setOf("route", "roa", "route of administration", "administration route", "method")
    private val TIME = setOf(
        "timestamp", "timestamp ms", "timestamp s", "time", "datetime", "date",
        "taken at", "taken", "start time", "logged at",
    )
    private val NOTES = setOf("notes", "note", "comment", "comments", "description")
    private val SESSION = setOf("session", "session id", "session title", "experience", "experience id")
    private val ESTIMATE = setOf("is estimate", "estimate", "estimated")
}
