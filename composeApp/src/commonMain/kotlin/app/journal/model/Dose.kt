package app.journal.model
import kotlinx.serialization.Serializable

/** Stomach fullness at time of ingestion. Affects absorption rate. */
enum class StomachFullness(val label: String) {
    EMPTY("Empty"),
    LIGHT("Light snack"),
    MODERATE("Moderate meal"),
    FULL("Full meal")
}

/** Conflict: APPEND_ONLY. Device-keyed IDs prevent structural conflicts */
@Serializable
data class Dose(
    override val id: String,
    override val docType: String = "dose",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val sessionId: String,
    val substanceId: String,
    val routeOfAdministration: String,
    val amount: Double,
    val unit: String,
    val timestamp: Long,
    val redosing: Boolean = false,
    val notes: String? = null,
    val isDoseEstimate: Boolean = false,
    val estimatedDoseStandardDeviation: Double? = null,
    val customUnitId: String? = null,
    val stomachFullness: StomachFullness? = null
) : VaultDocument
