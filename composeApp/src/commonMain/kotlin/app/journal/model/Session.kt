package app.journal.model
import kotlinx.serialization.Serializable

@Serializable
data class CheckIn(
    val timestamp: Long,
    val overallIntensity: Float,
    val effectScores: Map<String, Float> = emptyMap(),
    val mood: String? = null,
    val notes: String? = null
)

/** Conflict: FIELD_LEVEL_MERGE */
@Serializable
data class Session(
    override val id: String,
    override val docType: String = "session",
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val title: String,
    val startTime: Long,
    val endTime: Long? = null,
    val tags: List<String> = emptyList(),
    val set: String? = null,
    val setting: String? = null,
    val intention: String? = null,
    val outcome: String? = null,
    val rating: Int? = null,
    val checkins: List<CheckIn> = emptyList(),
    val isArchived: Boolean = false
) : VaultDocument
