package app.journal.model
import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

enum class TimelineEventType {
    ONSET, COMEUP, PEAK, PLATEAU, OFFSET, AFTERGLOW, END,
    OBSERVATION, SAFETY_CHECK, SIDE_EFFECT, EMERGENCY, NOTE
}

@Immutable
@Serializable
data class TimelineEvent(
    override val id: String,
    
    override val createdAt: Long,
    override val updatedAt: Long,
    override val deviceOrigin: String,
    /** Hybrid logical clock stamp; blank on rows written before snapshot version 10. */
    override val clock: String = "",
    val sessionId: String,
    val timestamp: Long,
    val eventType: TimelineEventType,
    val label: String,
    val body: String? = null,
    val intensity: Float? = null
) : ClockStamped
