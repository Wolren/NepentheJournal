package app.journal.data

/**
 * Tabular export data classes (R/Pandas-friendly).
 * Extracted from JournalRepository to keep the repo focused on
 * entity management rather than presentation formats.
 */

data class SessionDataRow(
    val id: String, val title: String, val date: String,
    val startTime: String, val endTime: String?,
    val durationHours: Double?,
    val set: String?, val setting: String?, val intention: String?,
    val outcome: String?, val rating: Int?,
    val shulginRating: String?, val consumerName: String?,
    val isFavorite: Boolean, val isArchived: Boolean,
    val substanceNames: String, val doseCount: Int
)

data class DoseDataRow(
    val id: String, val sessionId: String, val substanceId: String,
    val substanceName: String, val route: String,
    val amount: Double, val unit: String, val timestamp: Long,
    val redosing: Boolean, val isEstimate: Boolean, val notes: String?
)

data class SubstanceDataRow(
    val id: String, val name: String, val aliases: String,
    val substanceClass: String, val cid: Long?,
    val molecularFormula: String?, val molecularWeight: String?,
    val iupacName: String?, val logP: Double?,
    val routes: String, val effects: String,
    val toxicity: String, val addictionPotential: String?
)
