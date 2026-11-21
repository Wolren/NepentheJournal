package app.journal.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class StomachFullness(val label: String) {
    EMPTY("Empty"),
    LIGHT("Light snack"),
    MODERATE("Moderate meal"),
    FULL("Full meal")
}

@Immutable
@Serializable
data class Dose(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    /** Per-field clock stamps; a missing field falls back to [clock]. Snapshot version 11. */
    override val fieldClocks: Map<String, String> = emptyMap(),
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
) : FieldVersioned
