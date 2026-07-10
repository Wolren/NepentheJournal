package app.journal.util

import app.journal.data.JournalRepository
import app.journal.data.SessionDataRow
import app.journal.data.DoseDataRow
import app.journal.data.SubstanceDataRow
import app.journal.model.*

/**
 * CSV export for analysis workflows.
 * Produces R/Pandas-friendly output with proper escaping, consistent date
 * formats, and empty-string nulls.
 *
 * Each entity type gets its own CSV. Filtered subset and zip bundle
 * supported via [CsvExportFilter].
 */
object CsvExporter {

    /** Filter criteria for session subset export. All null = everything. */
    data class CsvExportFilter(
        val dateFrom: String? = null,
        val dateTo: String? = null,
        val substanceNames: List<String> = emptyList(),
        val tags: List<String> = emptyList()
    )

    // ---- CSV helpers ----

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

    private fun csvLine(vararg fields: String?): String =
        fields.joinToString(",") { escapeField(it) }

    private fun csvLineList(fields: List<String?>): String =
        fields.joinToString(",") { escapeField(it) }

    // ---- Sessions CSV ----

    private val sessionHeaders = listOf(
        "id", "title", "date", "start_time", "end_time",
        "duration_hours", "tags", "set", "setting",
        "intention", "outcome", "rating", "shulgin_rating",
        "consumer", "is_favorite", "is_archived",
        "substances", "dose_count"
    )

    private fun sessionToRow(df: SessionDataRow): List<String?> = listOf(
        df.id, df.title, df.date, df.startTime, df.endTime,
        df.durationHours?.toString(), df.tags, df.set, df.setting,
        df.intention, df.outcome, df.rating?.toString(), df.shulginRating,
        df.consumerName, if (df.isFavorite) "true" else "false",
        if (df.isArchived) "true" else "false",
        df.substanceNames, df.doseCount.toString()
    )

    /**
     * Exports all sessions matching [filter] as CSV string.
     */
    fun exportSessionsCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val sb = StringBuilder()
        sb.appendLine(csvLineList(sessionHeaders))

        val allRows = repo.sessionsDataFrame()
        val filtered = if (filter == null) allRows else applyFilter(repo, allRows, filter)

        for (row in filtered) {
            sb.appendLine(csvLineList(sessionToRow(row)))
        }
        return sb.toString()
    }

    // ---- Doses CSV ----

    private val doseHeaders = listOf(
        "id", "session_id", "substance_id", "substance_name",
        "route", "amount", "unit", "timestamp_ms",
        "is_redose", "is_estimate", "notes"
    )

    private fun doseToRow(df: DoseDataRow): List<String?> = listOf(
        df.id, df.sessionId, df.substanceId, df.substanceName,
        df.route, df.amount.toString(), df.unit, df.timestamp.toString(),
        if (df.redosing) "true" else "false",
        if (df.isEstimate) "true" else "false",
        df.notes
    )

    fun exportDosesCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val sb = StringBuilder()
        sb.appendLine(csvLineList(doseHeaders))

        val allRows = repo.dosesDataFrame()
        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }

        for (row in allRows) {
            if (filteredSessionIds == null || row.sessionId in filteredSessionIds) {
                sb.appendLine(csvLineList(doseToRow(row)))
            }
        }
        return sb.toString()
    }

    // ---- Substances CSV ----

    private val substanceHeaders = listOf(
        "id", "name", "aliases", "class", "cid",
        "molecular_formula", "molecular_weight", "iupac_name",
        "log_p", "routes", "effects", "toxicity",
        "addiction_potential"
    )

    private fun substanceToRow(df: SubstanceDataRow): List<String?> = listOf(
        df.id, df.name, df.aliases, df.substanceClass,
        df.cid?.toString(), df.molecularFormula, df.molecularWeight,
        df.iupacName, df.logP?.toString(), df.routes, df.effects,
        df.toxicity, df.addictionPotential
    )

    fun exportSubstancesCsv(repo: JournalRepository): String {
        val sb = StringBuilder()
        sb.appendLine(csvLineList(substanceHeaders))

        for (row in repo.substancesDataFrame()) {
            sb.appendLine(csvLineList(substanceToRow(row)))
        }
        return sb.toString()
    }

    // ---- Timeline events CSV ----

    private val eventHeaders = listOf(
        "id", "session_id", "timestamp_ms", "event_type",
        "label", "body", "intensity"
    )

    private fun eventToRow(event: TimelineEvent): List<String?> = listOf(
        event.id, event.sessionId, event.timestamp.toString(),
        event.eventType.name, event.label, event.body,
        event.intensity?.toString()
    )

    fun exportTimelineEventsCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val sb = StringBuilder()
        sb.appendLine(csvLineList(eventHeaders))

        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }

        for (event in repo.timelineEvents.value) {
            if (filteredSessionIds == null || event.sessionId in filteredSessionIds) {
                sb.appendLine(csvLineList(eventToRow(event)))
            }
        }
        return sb.toString()
    }

    // ---- Notes CSV ----

    private val noteHeaders = listOf(
        "id", "session_id", "title", "body", "tags",
        "is_pinned", "created_at", "updated_at"
    )

    private fun noteToRow(note: Note): List<String?> = listOf(
        note.id, note.sessionId, note.title, note.body,
        note.tags.joinToString(";"), if (note.isPinned) "true" else "false",
        note.createdAt.toString(), note.updatedAt.toString()
    )

    fun exportNotesCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val sb = StringBuilder()
        sb.appendLine(csvLineList(noteHeaders))

        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }

        for (note in repo.notes.value) {
            if (filteredSessionIds == null || note.sessionId in filteredSessionIds) {
                sb.appendLine(csvLineList(noteToRow(note)))
            }
        }
        return sb.toString()
    }

    // ---- Filter helpers ----

    /**
     * Resolves a filter into the set of session IDs that match.
     * Empty filter = all sessions.
     */
    private fun resolveSessionIds(repo: JournalRepository, filter: CsvExportFilter): Set<String> {
        var ids = repo.sessionsDataFrame().map { it.id }.toSet()

        if (filter.dateFrom != null || filter.dateTo != null) {
            val dateIds = repo.sessionIdsOnDateRange(filter.dateFrom, filter.dateTo).toSet()
            ids = ids.intersect(dateIds)
        }

        if (filter.substanceNames.isNotEmpty()) {
            val subIds = repo.substances.value
                .filter { sub ->
                    filter.substanceNames.any { name ->
                        sub.name.contains(name, ignoreCase = true) ||
                        sub.aliases.any { it.contains(name, ignoreCase = true) }
                    }
                }
                .map { it.id }
                .toSet()
            val sessionsWithSub = mutableSetOf<String>()
            for (sid in subIds) {
                sessionsWithSub.addAll(repo.sessionIdsForSubstance(sid))
            }
            ids = ids.intersect(sessionsWithSub)
        }

        if (filter.tags.isNotEmpty()) {
            val taggedIds = repo.sessionIdsWithAnyTag(filter.tags)
            ids = ids.intersect(taggedIds)
        }

        return ids
    }

    private fun applyFilter(repo: JournalRepository, rows: List<SessionDataRow>, filter: CsvExportFilter): List<SessionDataRow> {
        val matchingIds = resolveSessionIds(repo, filter)
        return rows.filter { it.id in matchingIds }
    }
}
