package app.journal.model
import kotlinx.serialization.Serializable

enum class TimelineEventType {
    ONSET, COMEUP, PEAK, PLATEAU, OFFSET, AFTERGLOW, END,
    OBSERVATION, SAFETY_CHECK, SIDE_EFFECT, EMERGENCY, NOTE
}

/** Conflict: APPEND_ONLY */
@Serializable
data class TimelineEvent(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    val sessionId: String,
    val timestamp: Long,
    val eventType: TimelineEventType,
    val label: String,
    val body: String? = null,
    val relatedEffectIds: List<String> = emptyList(),
    val intensity: Float? = null
) : VaultDocument
