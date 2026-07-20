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
        val tags: String = ""
    )

    // ---- Table definitions ----

    private val sessionTable = CsvTable(listOf(
        "id", "title", "date", "start_time", "end_time",
        "duration_hours", "set", "setting",
        "intention", "outcome", "rating", "shulgin_rating",
        "consumer", "is_favorite", "is_archived",
        "substances", "dose_count"
    )) { df: SessionDataRow ->
        listOf(
            df.id, df.title, df.date, df.startTime, df.endTime,
            df.durationHours?.toString(), df.set, df.setting,
            df.intention, df.outcome, df.rating?.toString(), df.shulginRating,
            df.consumerName, if (df.isFavorite) "true" else "false",
            if (df.isArchived) "true" else "false",
            df.substanceNames, df.doseCount.toString()
        )
    }

    private val doseTable = CsvTable(listOf(
        "id", "session_id", "substance_id", "substance_name",
        "route", "amount", "unit", "timestamp_ms",
        "is_redose", "is_estimate", "notes"
    )) { df: DoseDataRow ->
        listOf(
            df.id, df.sessionId, df.substanceId, df.substanceName,
            df.route, df.amount.toString(), df.unit, df.timestamp.toString(),
            if (df.redosing) "true" else "false",
            if (df.isEstimate) "true" else "false",
            df.notes
        )
    }

    private val substanceTable = CsvTable(listOf(
        "id", "name", "aliases", "class", "cid",
        "molecular_formula", "molecular_weight", "iupac_name",
        "log_p", "routes", "effects", "toxicity",
        "addiction_potential"
    )) { df: SubstanceDataRow ->
        listOf(
            df.id, df.name, df.aliases, df.substanceClass,
            df.cid?.toString(), df.molecularFormula, df.molecularWeight,
            df.iupacName, df.logP?.toString(), df.routes, df.effects,
            df.toxicity, df.addictionPotential
        )
    }

    private val eventTable = CsvTable(listOf(
        "id", "session_id", "timestamp_ms", "event_type",
        "label", "body", "intensity"
    )) { event: TimelineEvent ->
        listOf(
            event.id, event.sessionId, event.timestamp.toString(),
            event.eventType.name, event.label, event.body,
            event.intensity?.toString()
        )
    }

    private val noteTable = CsvTable(listOf(
        "id", "session_id", "title", "body",
        "is_pinned", "created_at", "updated_at"
    )) { note: Note ->
        listOf(
            note.id, note.sessionId, note.title, note.body,
            if (note.isPinned) "true" else "false",
            note.createdAt.toString(), note.updatedAt.toString()
        )
    }

    // ---- Public export functions ----

    fun exportSessionsCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val allRows = repo.sessionsDataFrame()
        val filtered = if (filter == null) allRows else applyFilter(repo, allRows, filter)
        return sessionTable.render(filtered)
    }

    fun exportDosesCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val allRows = repo.dosesDataFrame()
        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }
        val filtered = if (filteredSessionIds != null)
            allRows.filter { it.sessionId in filteredSessionIds }
        else allRows
        return doseTable.render(filtered)
    }

    fun exportSubstancesCsv(repo: JournalRepository): String =
        substanceTable.render(repo.substancesDataFrame())

    fun exportTimelineEventsCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }
        val filtered = if (filteredSessionIds != null)
            repo.timelineEvents.value.filter { it.sessionId in filteredSessionIds }
        else repo.timelineEvents.value
        return eventTable.render(filtered)
    }

    fun exportNotesCsv(repo: JournalRepository, filter: CsvExportFilter? = null): String {
        val filteredSessionIds = filter?.let { resolveSessionIds(repo, it) }
        val filtered = if (filteredSessionIds != null)
            repo.notes.value.filter { it.sessionId in filteredSessionIds }
        else repo.notes.value
        return noteTable.render(filtered)
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

        return ids
    }

    private fun applyFilter(repo: JournalRepository, rows: List<SessionDataRow>, filter: CsvExportFilter): List<SessionDataRow> {
        val matchingIds = resolveSessionIds(repo, filter)
        return rows.filter { it.id in matchingIds }
    }
}
