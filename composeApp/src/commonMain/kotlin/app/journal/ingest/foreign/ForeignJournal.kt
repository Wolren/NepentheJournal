package app.journal.ingest.foreign

import app.journal.model.StomachFullness

/** One dose from a foreign journal, already converted to epoch milliseconds. */
data class ForeignDose(
    val substance: String,
    val amount: Double,
    val unit: String,
    val route: String,
    val timestamp: Long,
    val notes: String? = null,
    val isEstimate: Boolean = false,
    val consumer: String? = null,
    val stomachFullness: StomachFullness? = null,
)

/** One session-sized group from a foreign journal: a PW experience, a DrugsPRO experience, a CSV group. */
data class ForeignExperience(
    val title: String? = null,
    val notes: String? = null,
    val doses: List<ForeignDose> = emptyList(),
)

/** A fully parsed foreign file, ready to apply. */
data class ForeignJournal(
    val format: ForeignFormat,
    val experiences: List<ForeignExperience>,
)

/** The apps and files [ForeignJournalImporter.import] reads. */
enum class ForeignFormat(val label: String) {
    PSYCHONAUTWIKI("PsychonautWiki Journal"),
    DRUGSPRO("DrugsPRO"),
    SPREADSHEET("CSV/TSV table"),
    ;

    /** deviceOrigin stamped on imported rows, so a later import stays identifiable. */
    val origin: String get() = "import:" + name.lowercase()
}

/** Counts and the failure reason for one import run. */
data class ForeignImportResult(
    val format: ForeignFormat? = null,
    val sessions: Int = 0,
    val doses: Int = 0,
    val substancesCreated: Int = 0,
    val duplicatesSkipped: Int = 0,
    val error: String? = null,
)
